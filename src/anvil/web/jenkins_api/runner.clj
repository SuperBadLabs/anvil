(ns anvil.web.jenkins-api.runner
  "Bridges the Jenkins REST shim's build-trigger endpoint to anvil's
   parser + runtime + dispatcher.

   TX9 turns on real subprocess execution: each build gets a fresh
   workspace under `target/anvil-builds/<job>/<n>/` and the dispatcher
   is constructed with `:execute? true`, so `sh` calls actually fork
   processes and capture stdout/stderr into the build's console log.

   Concurrency: triggered builds spawn via `future` inside the trigger
   handler. v1 is naive (any number of concurrent builds allowed); a
   per-job concurrency limit lands when the executor's work-scheduler
   is plumbed in fully."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.classification :as classify]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.ir :as ir]
            [anvil.compat.jenkins.libraries :as libraries]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.env :as jenkins-env]
            [anvil.compat.jenkins.scm :as scm]
            [anvil.compat.jenkins.scm-lifecycle :as scm-lifecycle]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.log-tail :as log-tail]
            [anvil.parameters-defaults :as params-defaults]
            [anvil.features :as features]))

;; ---------------------------------------------------------------------------
;; v0.6.2 security — runtime-param redaction for the
;; [:parameters/defaults-applied] effect
;;
;; The runtime params come from the REST trigger body (e.g. a POST to
;; `/jenkins/job/<j>/buildWithParameters?PASSWORD=hunter2` or the JSON
;; equivalent) and may carry secrets. Translator + operator defaults
;; live in the Jenkinsfile / anvil.edn — public config, safe to log.
;;
;; The dispatcher's `:secrets` masker only covers values that flowed
;; through `withCredentials` — trigger-time params bypass that masker.
;; The effect payload is persisted to the build's DB row AND rendered
;; into `/consoleText`, so without redaction here a REST trigger with
;; {"PASSWORD": "hunter2"} leaks the secret to disk + log.
;;
;; Redaction shape: keep names (so operators can audit which params
;; were supplied), replace each value with `<provided>` (non-blank
;; string / non-nil) or `<empty>` (blank / nil). Values are NEVER
;; rendered, regardless of their type.
;; ---------------------------------------------------------------------------

(defn ^:no-doc redact-runtime-values
  "Replace every value in `m` with `<provided>` or `<empty>` indicator.
   Returns `{}` for nil / non-map input."
  [m]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k
            (cond
              (nil? v) "<empty>"
              (and (string? v) (str/blank? ^String v)) "<empty>"
              :else "<provided>")))
   {}
   (or m {})))

(defn ^:no-doc redact-effective-runtime-keys
  "Walk `effective-parameters`, replacing values whose KEY came from
   `runtime-params` (set lookup) with the redaction indicator. Keys
   that came purely from translator / operator defaults retain their
   original (public) value. Returns `{}` for nil input."
  [effective-parameters runtime-params]
  (let [runtime-keys (set (keys (or runtime-params {})))]
    (reduce-kv
     (fn [acc k v]
       (assoc acc k
              (if (contains? runtime-keys k)
                (cond
                  (nil? v) "<empty>"
                  (and (string? v) (str/blank? ^String v)) "<empty>"
                  :else "<provided>")
                v)))
     {}
     (or effective-parameters {}))))

(defn- flatten-pipeline
  "Squash a Jenkins pipeline IR's stages + post hooks into the linear
   stage list the reference orchestrator walks. Wraps each stage's
   steps with the agent enter/leave synthetic markers from TX5."
  [pipeline-ir]
  (let [stages (:stages pipeline-ir)
        with-agents (-> pipeline-ir
                        (assoc :stages stages)
                        agent/wrap-pipeline-with-agent-events)
        post-cleanup (get-in pipeline-ir [:post :cleanup])
        post-always  (get-in pipeline-ir [:post :always])]
    (concat
     (mapv (fn [stage]
             {:name (:name stage)
              :steps (concat (:steps stage)
                             (get-in stage [:post :always] []))})
           (:stages with-agents))
     (when (seq post-cleanup) [{:name "<post.cleanup>" :steps post-cleanup}])
     (when (seq post-always)  [{:name "<post.always>"  :steps post-always}]))))

(defn ensure-workspace!
  "Allocate (and create on disk) the workspace directory for a build."
  [job-name build-number]
  (let [d (io/file "target" "anvil-builds" job-name (str build-number))]
    (.mkdirs d)
    d))

(defn ensure-log-file!
  "Allocate the build's log file inside `target/anvil-builds/<job>/<n>/`.
   The dispatcher's :execute? sh handler streams stdout+stderr directly
   into this file rather than buffering — eliminates the memory
   pressure for long builds."
  [^java.io.File workspace]
  (let [parent (io/file (.getParentFile workspace) "logs")
        f (io/file parent (str (.getName workspace) ".log"))]
    (.mkdirs parent)
    f))

;; ---------------------------------------------------------------------------
;; TU5.3: in-flight registry + kill action
;;
;; v1 kill is best-effort: we interrupt the runner thread. The dispatcher
;; doesn't yet check an abort flag between steps (that's a chengis-core
;; addition tracked for v1.1), so an in-flight `sh` will run to its own
;; completion before the InterruptedException surfaces — but the next
;; step won't fire, and the build records as :failure with the catch
;; below converting the exception to a recorded build-end. For queued
;; (not-yet-running) items, queue/cancel! is the clean path and the
;; build never starts at all.
;; ---------------------------------------------------------------------------

(defonce ^:private in-flight (atom {}))

(defn in-flight-snapshot
  "Vector of {:job-name :build-number :started-at} for everything
   currently being run by run-build!. Used by /executors view."
  []
  (->> @in-flight
       (map (fn [[[j n] info]] (assoc info :job-name j :build-number n)))
       (sort-by :started-at)
       vec))

(defn kill!
  "Interrupt the runner thread for [job-name build-number]. Returns
   true if a thread was found + interrupted, false otherwise."
  [job-name build-number]
  (when-let [{:keys [thread]} (get @in-flight [job-name build-number])]
    (.interrupt ^Thread thread)
    true))

(defn run-build!
  "Run a build for `job-name` (which must be registered) with optional
   `:parameters` and an opt-in `:execute?` (defaults true; TX9).

   The function is synchronous: it parses, runs, and returns when the
   build is complete. The caller (the handler) typically allocates a
   build number BEFORE invoking us so the queue/build-URL response can
   include the number before the build finishes."
  [job-name {:keys [parameters build-number execute?]
             :or {execute? true}}]
  (let [job (jobs/find-job job-name)]
    (when-not job
      (throw (ex-info "no such job" {:job-name job-name})))

    (let [number (or build-number
                     (jobs/record-build-start! job-name {:parameters parameters}))
          workspace (ensure-workspace! job-name number)
          workspace-path (.getAbsolutePath workspace)
          log-file (ensure-log-file! workspace)
          log-path (.getAbsolutePath log-file)
          ;; AN8-2: parse the Jenkinsfile up front so we can extract
          ;; the declarative `parameters { choice(...) }` defaults
          ;; and merge them under operator-side defaults and the
          ;; runtime-supplied parameters (runtime wins). When the
          ;; feature flag is off, only runtime params are used —
          ;; preserving v0.5.x behavior. Parsing is idempotent; the
          ;; pipeline-ir is re-used below.
          source-pre (:jenkinsfile-source job)
          pipeline-ir-pre (when source-pre
                            (try (t/parse source-pre (str job-name "/Jenkinsfile"))
                                 (catch Throwable _ nil)))
          params-feature-on? (try (features/enabled? :parameters-defaults)
                                  (catch Throwable _ false))
          translator-defaults (when params-feature-on?
                                (ir/default-parameters pipeline-ir-pre))
          operator-defaults (when params-feature-on?
                              (params-defaults/for-job job-name))
          effective-parameters (if params-feature-on?
                                 (params-defaults/resolve-build-parameters
                                  {:translator-defaults translator-defaults
                                   :operator-defaults operator-defaults
                                   :runtime-params parameters})
                                 (or parameters {}))
          ;; Jenkins env vars populated for the build.
          env-vars (jenkins-env/build-env
                    {:build-number number
                     :build-id (str number)
                     :job-name job-name
                     :workspace workspace-path
                     :extra-env effective-parameters})]
      ;; Wild-corpus follow-up: if the job has :scm configured, fetch
      ;; the source into the workspace BEFORE any step runs. Without
      ;; this, Jenkinsfiles whose first sh references workspace files
      ;; (`./mvnw`, `mvn -f sub/pom.xml`, …) fail with "not found" or
      ;; "no POM in directory" because anvil's checkout scm is a stub.
      ;; Idempotent — subsequent builds in the same workspace fast-fetch.
      ;; No SCM configured → no-op (preserves existing-job behavior).
      (when-let [scm-cfg (:scm job)]
        (let [r (scm/provision! workspace scm-cfg)]
          (log/info (str "anvil.scm: " (:result r)
                         (when-let [s (:sha r)] (str " @ " (subs s 0 (min 8 (count s)))))
                         (when-let [e (:error r)] (str " ERROR: " e))))))

      ;; v0.3 T7.3 — Pre-step mise/asdf provisioning. Closed by default
      ;; behind :anvil.features/mise; with the flag off, this is a no-op.
      (when (try ((requiring-resolve 'anvil.features/enabled?) :mise)
                 (catch Throwable _ false))
        (try
          (let [provision! (requiring-resolve 'anvil.tools.mise/provision!)
                r (provision! workspace-path)]
            (log/info (str "anvil.mise: " (:backend r) " → " (:status r))))
          (catch Throwable t
            (log/warn t "anvil.mise: provision failed; continuing"))))
      ;; TU2.1: spawn the log-tail thread BEFORE the dispatcher kicks
      ;; off so the first subprocess line is observed live. The thread
      ;; tails `log-file` and publishes per-line :console-line events
      ;; on the [:build job-name number] bus topic. Stopped in the
      ;; finally so a thrown build still cleans up.
      ;; TU5.3: also register ourselves in the in-flight map so
      ;; runner/kill! can interrupt this thread.
      (let [stop-tail (log-tail/start! job-name number log-file)
            key [job-name number]]
        (swap! in-flight assoc key
               {:thread (Thread/currentThread)
                :started-at (java.time.Instant/now)})
        (try
          (let [source (:jenkinsfile-source job)
                ;; AN8-2: reuse the up-front parse if we did one for
                ;; parameter-default extraction. Falls through to a
                ;; fresh parse otherwise (preserving v0.5.x behavior
                ;; when the pre-parse path failed).
                base-ir (or pipeline-ir-pre
                            (t/parse source (str job-name "/Jenkinsfile")))
                ;; TX11B: expand matrix combinations (scripted-Pipeline files
                ;; with .combinations { … } blocks need their templated
                ;; stages materialized before dispatch).
                expanded-ir (mx/expand-matrices base-ir source)
                ;; AN8-4: declarative pipelines get an implicit
                ;; `checkout scm` step prepended to stage 1 when the
                ;; feature flag is on AND the Jenkinsfile doesn't
                ;; already have an explicit checkout. The dispatcher's
                ;; h-checkout actually calls scm/provision! against the
                ;; job's :scm config (idempotent w/ the runner's
                ;; pre-build provision — short-circuits on existing .git).
                pipeline-ir (scm-lifecycle/inject-implicit-checkout
                             expanded-ir
                             {:scm (:scm job)
                              :flag-enabled?
                              (try ((requiring-resolve 'anvil.features/enabled?)
                                    :scm-checkout-lifecycle)
                                   (catch Throwable _ false))})
                flat-stages (flatten-pipeline pipeline-ir)
                flat {:stages (vec flat-stages)}
                dispatcher (ad/make {:execute? execute?})
                ;; AN8-2: surface the parameters resolution in the
                ;; effect log so operators see exactly which defaults
                ;; flowed in from where. No-op when feature off.
                ;;
                ;; v0.6.2 security: runtime-param VALUES are redacted
                ;; (names only) because they originate in the REST
                ;; trigger body and may carry secrets. The effect
                ;; payload is persisted + rendered into /consoleText;
                ;; without this redaction, a REST trigger of
                ;; {"PASSWORD": "hunter2"} would land on disk and in
                ;; the build's console log. See `redact-runtime-values`
                ;; + `redact-effective-runtime-keys` above for the
                ;; redaction shape (names preserved, values replaced
                ;; with `<provided>` / `<empty>` indicators).
                _ (when params-feature-on?
                    (swap! (:effects dispatcher)
                           conj
                           [:parameters/defaults-applied
                            {:job-name job-name
                             :translator-defaults (or translator-defaults {})
                             :operator-defaults (or operator-defaults {})
                             :runtime-params (redact-runtime-values parameters)
                             :effective (redact-effective-runtime-keys
                                         effective-parameters parameters)}]))
                ;; AN5-2 + AN7-4: probe every `@Library` coordinate.
                ;; AN7-4 extends AN5-2: tries Git resolution first
                ;; (clones from :anvil.libs/remotes in anvil.edn, cached
                ;; at ~/.anvil/libs/<name>/<ref>/) before falling back to
                ;; the local ANVIL_LIBRARIES_DIR lookup. Pushes
                ;; :library-loaded / :library-unresolved effects for the
                ;; AN5-1 classifier. No-op when IR has no :libraries.
                _ (libraries/load-with-remote-into-effects!
                   pipeline-ir (:effects dispatcher))
                stash-root (.getAbsolutePath
                            (io/file (.getParentFile workspace) "stashes"))
                ctx {:dispatcher dispatcher
                     :env env-vars
                     :cwd workspace-path
                     :workspace workspace-path
                     :stash-root stash-root   ;; TX11C
                     :log-file log-file
                     :build-number number
                     :job-name job-name
                     :scm (:scm job)          ;; AN8-4: implicit checkout handler reads this
                     :parameters effective-parameters}
                result (d/run-pipeline flat dispatcher ctx)
                effects @(:effects dispatcher)
                ;; CC2-EX2 wire-up (AN4-1): instead of the lossy
                ;;   (case status :ok :success :failed :failure :success)
                ;; — which classified an empty walk as success simply
                ;; because nothing threw — fold the effects into a
                ;; chengis.engine.result observation and ask the honest
                ;; classifier. Empty-walk builds reclassify as :neutral;
                ;; silently-skipped docker/k8s agents reclassify as
                ;; :unsupported; non-zero shell exits as :failure.
                ;; AN5-1: pass pipeline-ir so the classifier can run
                ;; the walk-shape synthesizer and turn silent failures
                ;; (empty walks, unresolved @Library) into honest
                ;; :unsupported classifications with named :rule.
                classified (classify/classify-build
                            result effects
                            {:pipeline-ir pipeline-ir})
                build-result (:result classified)
                synth-effects (:synthetic-effects classified)
                ;; Persist enriched effects so the build-page Raw-effects
                ;; fold and the AN4-5 banner stay consistent with what
                ;; the classifier actually classified on.
                effects-for-persist (vec (concat effects synth-effects))
                _ (log/info (str "anvil.classify: " job-name " #" number
                                 " → " build-result
                                 " (rule=" (:rule classified) ") "
                                 (:explain classified)
                                 (when (seq synth-effects)
                                   (str " [+" (count synth-effects)
                                        " synthetic AN5-1]"))))]
            (jobs/record-build-end! job-name number
                                    {:result build-result
                                     :effects effects-for-persist
                                     :log-path log-path
                                     :classify-rule (:rule classified)
                                     :classify-explain (:explain classified)})
            {:build-number number
             :result build-result
             :rule (:rule classified)
             :explain (:explain classified)
             ;; Count after enrichment so the API response matches what
             ;; the UI's Raw-effects fold renders and what
             ;; record-build-end! persisted on the build row. AN5-1
             ;; synthetic effects are real effects from the operator's
             ;; perspective — they shape the classification, they
             ;; persist, they render.
             :effect-count (count effects-for-persist)
             :synthetic-effect-count (count synth-effects)
             :workspace workspace-path
             :log-path log-path})

          (catch InterruptedException _
            ;; TU5.3: kill! was called on this thread. Record build
            ;; as :aborted (distinct from :failure so the UI shows
            ;; a gray badge, not red).
            (jobs/record-build-end! job-name number
                                    {:result :aborted
                                     :effects [[:exception "build aborted by operator"]]
                                     :log-path log-path})
            {:build-number number
             :result :aborted
             :error "interrupted"
             :workspace workspace-path
             :log-path log-path})

          (catch Exception e
            (jobs/record-build-end! job-name number
                                    {:result :failure
                                     :effects [[:exception (.getMessage e)]]
                                     :log-path log-path})
            {:build-number number
             :result :failure
             :error (.getMessage e)
             :workspace workspace-path
             :log-path log-path})

          (finally
            (swap! in-flight dissoc key)
            (stop-tail)))))))
