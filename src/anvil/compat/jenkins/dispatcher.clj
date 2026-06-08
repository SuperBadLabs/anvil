(ns anvil.compat.jenkins.dispatcher
  "AnvilJenkinsDispatcher — implements the chengis-core StepDispatcher
   protocol for :jenkins/* step types.

   Dispatch policy:
     - Leaf steps (sh, echo, junit, archiveArtifacts, deleteDir, …)
       record a side effect to the shared atom and return :ok.
     - Scope-wrapping steps (dir, withEnv, timeout, retry) push context,
       recursively dispatch the body, then restore.
     - parallel dispatches each branch sequentially in v1 (true concurrency
       comes when this layer is plumbed into chengis.engine.executor's
       work scheduler — TX9).
     - :jenkins/script delegates to runtime/run-script-block, which sets
       up Pipeline DSL globals that route BACK through this dispatcher.
       Side effects from script bodies therefore interleave correctly
       with declarative side effects.
     - :jenkins/unknown records the call name + args so the migration UX
       (TX7) can report 'we don't have an adapter yet for X'.

   Productized from spike #4 (`chengis.spike.anvil-integration`) but
   re-platformed onto `chengis.engine.dispatcher/StepDispatcher` rather
   than the spike's internal protocol."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.process :as bp]
            [taoensso.timbre :as log]
            [chengis.engine.dispatcher :as d]
            [anvil.agents.registry :as agent-registry]
            [anvil.compat.jenkins.runtime :as runtime]
            [anvil.compat.jenkins.plugins :as plugins]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.scm :as scm]
            [anvil.compat.jenkins.shared-libs :as shared-libs]
            [anvil.tools.images :as tools-images]
            [anvil.features :as features]))

(declare ^:private dispatch-fn run-body propagate-or-ok h-sh)

;; ---------------------------------------------------------------------------
;; Credential masking
;;
;; withCredentials binds secret values to env-var names. We need to redact
;; those values from any logged effect (commands, output, echoed strings)
;; so they don't leak into stored logs / observability.
;;
;; The dispatcher holds a `:secrets` atom containing the current set of
;; secret strings. The masker replaces any occurrence with "****" before
;; the effect lands in :effects.
;; ---------------------------------------------------------------------------

(defn- mask-string
  "Replace every occurrence of every secret in `s` with \"****\"."
  [secrets s]
  (if (and (string? s) (seq secrets))
    (reduce (fn [acc secret]
              (if (and (string? secret) (seq secret))
                (str/replace acc secret "****")
                acc))
            s
            secrets)
    s))

(defn- mask-value
  "Recursively walk a value, masking any string fragments it contains.
   Used to redact logged effect payloads before they're stored."
  [secrets v]
  (cond
    (string? v)   (mask-string secrets v)
    (map? v)      (into (empty v) (for [[k val] v] [k (mask-value secrets val)]))
    (vector? v)   (mapv #(mask-value secrets %) v)
    (sequential? v) (mapv #(mask-value secrets %) v)
    :else          v))

(defn- log-effect
  "Append a side effect to the dispatcher's atom, redacting any secrets."
  [d ev]
  (let [secrets @(:secrets d)
        ev' (mask-value secrets ev)]
    (swap! (:effects d) conj ev')))

;; ---------------------------------------------------------------------------
;; Real subprocess execution
;;
;; When the dispatcher is constructed with `:execute? true`, the sh / bat
;; handlers actually spawn subprocesses instead of just recording the
;; intent. babashka.process handles the work cross-platform.
;;
;; If `:execute? false` (the default), the handlers stay in record-only
;; mode — which is what every test up to TX8 relied on. Real-execution
;; tests opt in explicitly.
;; ---------------------------------------------------------------------------

(defn build-docker-args
  "Construct the `docker run` argv that wraps a shell command for
   execution in a Docker container. Pure function — testable without
   actually invoking Docker.

   Mounts the workspace into the container at the same path (so paths
   echoed by sh make sense both inside and outside). Honors :args from
   the agent's docker spec (extra docker run args).

   AN7-3: optional `file-mounts` is a seq of {:host-path :container-path}
   maps; each becomes a `-v host:container:ro` flag appended after the
   workspace mount."
  ([docker-spec cmd workspace env]
   (build-docker-args docker-spec cmd workspace env nil))
  ([{:keys [image extra-args]} cmd workspace env file-mounts]
   (let [base (cond-> ["docker" "run" "--rm" "-i"
                       "-v" (str workspace \: workspace)
                       "-w" (str workspace)]
                extra-args (into (clojure.string/split extra-args #"\s+"))
                (seq file-mounts) (into (mapcat (fn [{:keys [host-path container-path]}]
                                                  ["-v" (str host-path ":" container-path ":ro")])
                                                file-mounts))
                (seq env) (into (mapcat (fn [[k v]] ["-e" (str k \= v)]) env)))
         argv (conj (vec base) image "sh" "-c" cmd)]
     argv)))

(defn- docker-spec
  "Extract a docker {:image :args} spec from the ctx :active-agent if any."
  [ctx]
  (let [agent (:active-agent ctx)]
    (when (and (map? agent) (:docker agent))
      {:image (-> agent :docker :image)
       :extra-args (-> agent :docker :args)})))

(defn- shell-execute
  "Run `cmd` in `cwd` with extra env vars. Returns a map
   {:exit INT :stdout STRING :stderr STRING :streamed? BOOL}.
   Never throws — runtime errors become exit=-1 with the message in stderr.

   ROUTING (AN5-3b)
   ================
   When `ctx :active-agent` declares a `:docker {:image ...}` shape, the
   call routes through `chengis.engine.backend.docker/DockerBackend` via
   `anvil.compat.jenkins.backend-wiring/execute-via-backend`. That gives
   us the chengis-core lifecycle (prepare-workspace → execute-step →
   cleanup) with proper image-pull handling and SIGTERM→SIGKILL cancel
   grace. The bridge maps the chengis-core return shape back to anvil's
   {:exit :stdout :stderr :streamed?} so nothing downstream changes.

   When the active-agent is non-docker (label, any, none, or absent),
   we stay on the inline TX9 subprocess path — same babashka.process
   spawn, same log-file streaming, same timeout enforcement, same
   credential masking. That path is what 99% of v0.3.x builds use and
   has been hardened across PRs #164 / TX9-13; we don't gain anything
   by routing it through the backend protocol yet (LocalShell is just a
   wrapper around the same subprocess primitive). AN5-3c can swap it
   once docker has soaked.

   Timeout: when ctx has :timeout-deadline (epoch-ms long), the inline
   path enforces it via babashka.process + destroyForcibly; the backend
   path passes the remaining-ms through chengis-core's :timeout field
   which the backend honors with its own kill logic.

   Log-file streaming: when ctx has :log-file (java.io.File), the inline
   path redirects stdout+stderr into it via ProcessBuilder$Redirect.
   The backend path passes :log-file through; chengis-core's
   DockerBackend handles streaming inside the container.

   Mask-values: secrets active in the dispatcher get passed through to
   the backend as :mask-values. The inline path's masking is handled
   higher up (in h-sh) when buffered."
  [cmd {:keys [cwd env timeout-deadline log-file active-agent] :as ctx}]
  (let [docker (docker-spec ctx)]
    (cond
      ;; AN5-3b: docker agent → chengis-core DockerBackend via the
      ;; backend-wiring bridge. The bridge handles lifecycle + result
      ;; shape mapping; nothing downstream changes.
      (some? docker)
      ((requiring-resolve 'anvil.compat.jenkins.backend-wiring/execute-via-backend)
       cmd ctx)

      ;; Non-docker — stay on the TX9 inline path. No change in behavior.
      :else
      (try
        (let [deadline-ms (when (number? timeout-deadline)
                            (max 1 (- timeout-deadline (System/currentTimeMillis))))
              ;; In streaming mode, both stdout AND stderr are redirected
              ;; to the same on-disk file in append mode. We use
              ;; ProcessBuilder$Redirect/appendTo for both streams so
              ;; multi-step pipelines accumulate cleanly.
              stream? (some? log-file)
              append-redirect (when stream?
                                (java.lang.ProcessBuilder$Redirect/appendTo log-file))
              base-opts (cond-> {:continue true}
                          stream?       (assoc :out append-redirect
                                               :err append-redirect)
                          (not stream?) (assoc :out :string :err :string)
                          cwd           (assoc :dir (str cwd))
                          (seq env)     (assoc :extra-env env))
              argv ["sh" "-c" cmd]
              pb (apply bp/process base-opts argv)
              completed (if deadline-ms
                          (let [fut (future @pb)]
                            (let [r (deref fut deadline-ms ::timeout)]
                              (if (= r ::timeout)
                                (do (.destroyForcibly ^Process (:proc pb))
                                    ::timeout)
                                r)))
                          @pb)]
          (cond
            (= completed ::timeout)
            {:exit 124 :stdout ""
             :stderr "[anvil] timeout — subprocess killed by anvil's timeout enforcement"
             :streamed? stream?}

            stream?
            {:exit (:exit completed) :stdout "" :stderr "" :streamed? true}

            :else
            {:exit (:exit completed)
             :stdout (or (:out completed) "")
             :stderr (or (:err completed) "")
             :streamed? false}))
        (catch Exception e
          {:exit -1 :stdout ""
           :stderr (str "[anvil] shell-execute exception: " (.getMessage e))
           :streamed? false})))))

;; ---------------------------------------------------------------------------
;; v0.5 T1.3 — step-level cache intercept
;;
;; When :anvil.features/cache is on AND the active-agent is docker,
;; shell-execute is wrapped by `cached-execute`:
;;
;;   1. Derive the cache key from (image-digest, command, env, input-tree=[])
;;      using anvil.cache.key/derive.
;;   2. Fetch the local store via anvil.cache.lookup/fetch.
;;   3. Cache hit  → replay stdout/stderr/exit from the store; publish
;;      :cache-hit on the per-build bus topic (non-fatal if bus unavailable).
;;   4. Cache miss → run shell-execute normally; publish :cache-miss; if
;;      exit 0, store the result for next time.
;;
;; Input-tree is empty for this first cut (T1.3). Full workspace
;; fingerprinting (step-inputs glob → sha256 pairs) is T1.5 scope; the
;; missing tree means the cache key changes only when image/command/env
;; change — correct for pure dependency-resolution steps, over-caching for
;; steps that read workspace files. The cache-invariants receipt (T1.4)
;; documents this honestly.
;;
;; The image "digest" used here is docker inspect's .Id (sha256:…).
;; We try `docker inspect --format={{.Id}} IMAGE` and fall back to the
;; raw tag string if docker is unavailable (tests, no-docker CI).
;; Using the tag as a proxy is weaker than a content-addressed digest but
;; still produces a DIFFERENT key when the agent config changes.
;; ---------------------------------------------------------------------------

(defn- resolve-docker-digest
  "Try to get the content digest (sha256:…) for a docker image tag via
   `docker inspect`. Returns the tag string itself on failure so the
   cache key still differs between different images — it's just
   not content-addressed when docker isn't on PATH."
  [tag]
  (try
    (let [result (bp/process {:out :string :err :string}
                              "docker" "inspect" "--format={{.Id}}" tag)
          out (str/trim (:out @result))]
      (if (and (zero? (:exit @result)) (str/starts-with? out "sha256:"))
        out
        tag))
    (catch Throwable _ tag)))

(defn- cache-feature-on? []
  (try
    ((requiring-resolve 'anvil.features/enabled?) :cache)
    (catch Throwable _ false)))

(defn- publish-cache-event!
  "Publish a :cache-hit or :cache-miss event to the per-build bus topic.
   Best-effort — a missing bus or bad ctx never aborts the step."
  [event-type ctx cache-key extra]
  (try
    (let [publish!    (requiring-resolve 'anvil.events.bus/publish!)
          topic-build (requiring-resolve 'anvil.events.topics/topic-build)
          jn  (:job-name ctx)
          bn  (:build-number ctx)]
      (when (and jn bn)
        (publish! (topic-build jn bn)
                  (merge {:type event-type
                          :job-name jn
                          :build-number bn
                          :cache-key cache-key}
                         extra))))
    (catch Throwable t
      (log/debug t "anvil.dispatcher: cache event publish failed (non-fatal)"))))

(defn- cached-execute
  "Cache-aware wrapper around shell-execute. Called when :execute? is true,
   the :cache feature flag is on, and the active-agent is docker-shaped.

   Returns the same {:exit :stdout :stderr :streamed?} map as shell-execute.

   ## Streaming-mode bypass (Copilot review on #81)

   When `ctx` carries a `:log-file` (streaming mode, used by the Jenkins
   API runner so `/consoleText` and the live log-tail thread see output
   as it happens), `shell-execute` redirects both stdout and stderr to
   that file at the OS level and returns empty `:stdout`/`:stderr`
   buffers. That breaks the cache two ways:

     - On cache miss, we'd store empty stdout/stderr — future cache hits
       would silently replay nothing.
     - On cache hit, we'd return cached stdout/stderr but never write
       them to the log-file — live consoleText consumers see no output.

   The minimal correct fix is to disable the cache in streaming mode.
   A future revision can tee: stream to the log-file AND buffer for
   caching. Until then, prefer correctness over hit-rate."
  [cmd ctx]
  (let [docker-spec (docker-spec ctx)
        image-tag   (when docker-spec (:image docker-spec))]
    (cond
      ;; Streaming mode — see docstring. Skip cache entirely.
      (:log-file ctx) (shell-execute cmd ctx)

      ;; Not a docker step — fall through to plain shell-execute
      (not image-tag) (shell-execute cmd ctx)

      :else
      (do
      (let [image-digest (resolve-docker-digest image-tag)
            env          (:env ctx {})
            cache-key    (try
                           ((requiring-resolve 'anvil.cache.key/derive)
                            {:image-digest image-digest
                             :command cmd
                             :env env
                             :input-tree []})
                           (catch Throwable t
                             (log/warn t "anvil.dispatcher: cache key derivation failed; skipping cache")
                             nil))]
        (if-not cache-key
          ;; key derivation failed — run without cache
          (shell-execute cmd ctx)
          (let [fetch (requiring-resolve 'anvil.cache.lookup/fetch)
                hit   (fetch cache-key)]
            (if hit
              ;; ---------- CACHE HIT ----------
              (let [saved-ms (or (:wall-ms hit) 0)]
                (log/debug (str "anvil.cache: HIT key=" (subs cache-key 0 12)
                                "… image=" image-tag " cmd=" (subs cmd 0 (min 60 (count cmd)))))
                (publish-cache-event! :cache-hit ctx cache-key
                                      {:saved-ms saved-ms})
                {:exit      (or (:exit hit) 0)
                 :stdout    (or (:stdout hit) "")
                 :stderr    (or (:stderr hit) "")
                 :streamed? false
                 :cache-hit? true
                 :cache-key cache-key})
              ;; ---------- CACHE MISS ----------
              (let [_ (log/debug (str "anvil.cache: MISS key=" (subs cache-key 0 12)
                                      "… image=" image-tag))
                    _ (publish-cache-event! :cache-miss ctx cache-key {})
                    t0 (System/currentTimeMillis)
                    {:keys [exit stdout stderr streamed?] :as result}
                    (shell-execute cmd ctx)
                    wall-ms (- (System/currentTimeMillis) t0)]
                ;; Only store on zero exit; a failed step's artifacts are
                ;; not a valid cached result (the next run may succeed under
                ;; different conditions).
                (when (zero? exit)
                  (let [record! (requiring-resolve 'anvil.cache.lookup/record!)]
                    (try
                      (record! cache-key
                               {:stdout       (or stdout "")
                                :stderr       (or stderr "")
                                :exit         exit
                                :wall-ms      wall-ms
                                :image-digest image-digest
                                :command      cmd
                                :now          (System/currentTimeMillis)})
                      (catch Throwable t
                        (log/warn t "anvil.dispatcher: cache write failed (non-fatal)")))))
                result)))))))))

(defn- ok [ctx & {:keys [output stdout status-code]}]
  (cond-> {:status :ok :ctx ctx}
    output      (assoc :output output)
    stdout      (assoc :stdout stdout)
    status-code (assoc :status-code status-code)))

(defn- canned-stdout
  "Look up a canned stdout response for `cmd` — used by tests that simulate
   `sh(returnStdout: true)` without running real subprocesses."
  [d cmd]
  (get @(:sh-canned d) cmd ""))

;; ---------------------------------------------------------------------------
;; Leaf step handlers
;; ---------------------------------------------------------------------------

(defn- h-sh [d step ctx]
  (let [original-cmd (:script step)
        cwd (:cwd ctx "/workspace")
        ;; AN5-4: when :anvil.features/mvn-deploy-degrade is on, detect
        ;; standalone `deploy` lifecycle phases in mvn commands and
        ;; rewrite them to `package` so wild-corpus builds produce
        ;; jars in target/ instead of crashing at the deploy step
        ;; with HTTP 401 from apache.snapshots/etc.
        mvn-degrade-on? (try
                          ((requiring-resolve 'anvil.features/enabled?)
                           :mvn-deploy-degrade)
                          (catch Throwable _ false))
        degrade ((requiring-resolve
                   'anvil.compat.jenkins.deploy-degrade/maybe-degrade)
                  original-cmd mvn-degrade-on?)
        cmd (if (:degraded? degrade) (:rewritten degrade) original-cmd)
        _ (when (:degraded? degrade)
            (log-effect d [:mvn/deploy-degraded
                           {:original (:original degrade)
                            :rewritten (:rewritten degrade)
                            :reason (:reason degrade)}]))]
    (cond
      ;; Real execution mode (TX9 phase 1). Spawn a subprocess, capture
      ;; i/o. In buffered mode (no ctx :log-file) we emit per-line
      ;; :stdout / :stderr effect tuples; in streaming mode (ctx
      ;; :log-file present) we just record a :sh tuple with byte counts
      ;; and the file holds the actual log content.
      (:execute? d)
      (let [;; Force buffered mode (drop :log-file) when either:
            ;; - Codex P1 (PR #164): secrets are active and streaming-
            ;;   to-disk would bypass the masker, leaking secrets to
            ;;   /consoleText; OR
            ;; - Codex P2 (PR #164): the caller requested
            ;;   returnStdout (which models Jenkins's
            ;;   `sh(script:'…', returnStdout: true)`) — in stream
            ;;   mode shell-execute returns stdout="" and the
            ;;   downstream `def v = sh(…, returnStdout: true).trim()`
            ;;   gets an empty string instead of the real output.
            secrets-active? (and (:secrets d) (seq @(:secrets d)))
            want-stdout? (or (:return-stdout? step) (:return-status? step))
            safe-ctx (if (and (or secrets-active? want-stdout?) (:log-file ctx))
                       (dissoc ctx :log-file)
                       ctx)
            ;; v0.5 T1.3 — cache intercept. When :anvil.features/cache is on
            ;; AND we have a docker active-agent, route through cached-execute
            ;; which checks the local CAS store before spawning a subprocess.
            ;; When :cache is off (default) or secrets are active (don't cache
            ;; steps with secret env — the cached stdout might leak the secret
            ;; to a different build's operator) we fall through to shell-execute.
            use-cache? (and (cache-feature-on?)
                            (not secrets-active?)
                            (some? (docker-spec safe-ctx)))
            {:keys [exit stdout stderr streamed? cache-hit?]}
            (if use-cache?
              (cached-execute cmd safe-ctx)
              (shell-execute cmd safe-ctx))]
        (log-effect d [:sh (cond-> {:cmd cmd :cwd cwd
                                    :exit exit
                                    :streamed? (boolean streamed?)
                                    :stdout-bytes (count stdout)
                                    :stderr-bytes (count stderr)}
                              cache-hit? (assoc :cache-hit? true))])
        (when (and (not streamed?) (seq stdout))
          (doseq [line (str/split-lines stdout)]
            (log-effect d [:stdout line])))
        (when (and (not streamed?) (seq stderr))
          (doseq [line (str/split-lines stderr)]
            (log-effect d [:stderr line])))
        (cond
          (:return-stdout? step)
          (assoc (ok ctx)
                 :stdout (str/trim-newline stdout)
                 :status (if (zero? exit) :ok :failed)
                 :exit exit)

          (:return-status? step)
          (assoc (ok ctx) :status-code exit)

          (zero? exit)
          (ok ctx :output cmd)

          :else
          {:status :failed :error :sh-non-zero :exit exit
           :output cmd :ctx ctx}))

      ;; Record-only mode (default). Existing behavior — every test
      ;; up to TX8 relies on this. canned-stdout supports tests that
      ;; want to simulate returnStdout without subprocess execution.
      :else
      (let [;; v0.4 T1.5 — test hook for retry/loop semantics: the
            ;; :sh-fail-attempts atom maps cmd-string → number of
            ;; failures to emit before succeeding (decremented on
            ;; each call). When the count hits zero the cmd starts
            ;; returning :ok again. Lets tests model a flaky command
            ;; that succeeds after N retries without spinning a real
            ;; subprocess. Untouched by production (atom is always
            ;; nil unless the test fixture passes it).
            fail-atom (:sh-fail-attempts d)
            remaining (when fail-atom (get @fail-atom cmd 0))
            fail-this? (and remaining (pos? remaining))
            ;; v0.4 T1.5 — :attempt field only present when the sh
            ;; runs inside a retry wrapper, so the historic effect
            ;; shape stays exactly `{:cmd cmd :cwd cwd}` for every
            ;; non-retry caller (TX4-TU6 tests all assert on this).
            base-effect {:cmd cmd :cwd cwd}
            sh-effect (if-let [a (:current-retry-attempt ctx)]
                        (assoc base-effect :attempt a)
                        base-effect)]
        (log-effect d [:sh sh-effect])
        (when fail-this?
          (swap! fail-atom update cmd dec))
        (cond
          fail-this?
          {:status :failed :error :sh-non-zero :exit 1 :output cmd :ctx ctx}

          (:return-stdout? step) (ok ctx :stdout (canned-stdout d cmd))
          (:return-status? step) (ok ctx :status-code 0)
          :else                  (ok ctx :output cmd))))))

(defn- h-bat [d step ctx]
  (log-effect d [:bat {:cmd (:script step)}])
  (ok ctx :output (:script step)))

(defn- h-echo [d step ctx]
  (log-effect d [:echo (str (:message step))])
  (ok ctx))

(defn- h-junit
  "v0.3 T1.6 — upgrade `junit` from a recorder-only stub to a real
   scan+persist+publish handler when the build context supplies a
   workspace + job/build identity AND the :junit feature flag is on.

   Falls back to the legacy recorder behavior when:
     - the feature flag is off (T0.2 closed-by-default), or
     - the context lacks :workspace / :job-name / :build-number
       (unit tests that don't configure a real workspace).
   This preserves the dispatcher's testability while making the
   step do real work in production once the flag flips."
  [d step ctx]
  (let [results-glob (or (:results step) (:arg step))
        ws  (:workspace ctx)
        jn  (:job-name ctx)
        bn  (:build-number ctx)
        feat-on? (try
                   ((requiring-resolve 'anvil.features/enabled?) :junit)
                   (catch Throwable _ false))]
    (if (and feat-on? ws jn bn results-glob)
      (let [scan (requiring-resolve 'anvil.compat.junit/scan-build-artifacts)
            record! (requiring-resolve 'anvil.storage.test-results/record-build-results!)
            publish! (requiring-resolve 'anvil.events.bus/publish!)
            topic-build (requiring-resolve 'anvil.events.topics/topic-build)
            evt-test-completed (requiring-resolve 'anvil.events.topics/evt-test-completed)
            globs (mapv str/trim (str/split (str results-glob) #","))
            tree (scan ws {:globs globs})
            ;; v0.4 T1.5 — when the junit step runs inside a retry
            ;; wrapper, h-retry threads `:current-retry-attempt` into
            ;; ctx. Pass it through so record-build-results! produces
            ;; per-attempt rows (anvil.flaky/detect substrate).
            attempt (or (:current-retry-attempt ctx) 1)
            summary (record! jn bn tree {:attempt-number attempt})]
        (log-effect d [:junit
                       (merge (select-keys summary
                                           [:tests :passed :failed :errored
                                            :skipped :duration-ms :parse-errors])
                              {:results results-glob
                               :scanned-files (:scanned-files tree)
                               :attempt attempt})])
        (try
          (publish! (topic-build jn bn)
                    {:type @evt-test-completed
                     :job-name jn
                     :build-number bn
                     :tests (:tests summary)
                     :passed (:passed summary)
                     :failed (:failed summary)
                     :errored (:errored summary)
                     :skipped (:skipped summary)
                     :duration-ms (:duration-ms summary)
                     :attempt attempt})
          (catch Throwable t
            (log/warn t "h-junit: SSE publish failed (non-fatal)")))
        (ok ctx))
      (do
        (log-effect d [:junit {:results results-glob
                               :args    (:args step)
                               :note (cond
                                       (not feat-on?) "recorder-only — :anvil.features/junit disabled"
                                       (not ws) "recorder-only — no :workspace in ctx"
                                       (not jn) "recorder-only — no :job-name in ctx"
                                       (not bn) "recorder-only — no :build-number in ctx"
                                       :else "recorder-only — no results glob")}])
        (ok ctx)))))

(defn- h-archive
  "v0.3 baseline: recorder-only; logs the `archiveArtifacts` declaration
   on the effects stream.

   v0.4.1 T4.3 — when the `:provenance` feature flag is on AND ctx
   supplies :workspace + :job-name + :build-number, additionally:
     1. Glob the `:artifacts` pattern relative to the workspace
     2. For each matched file, build an in-toto v1 statement and
        sign it via cosign (anvil.provenance.cosign/sign-statement!)
     3. Emit per-artifact `[:provenance/attested {:path :sha256 :bundle}]`
        or `[:provenance/degraded {:reason :cosign-missing|:cosign-error
        :error <msg>}]`

   Per AV4-7 (closed-by-default): when the flag is off, behavior is
   identical to v0.3 — recorder only.  Operators who don't opt in see
   no provenance effects and no .intoto.jsonl files."
  [d step ctx]
  (let [artifacts-pattern (or (:artifacts step) (:arg step))]
    (log-effect d [:archive {:artifacts artifacts-pattern
                             :args     (:args step)}])
    ;; v0.4.1 T4.3 — provenance signing.  Lazy-resolve the helpers so
    ;; tests / boot paths that don't enable :provenance never load
    ;; the anvil.provenance.* namespaces.
    (let [flag-on? (try ((requiring-resolve 'anvil.features/enabled?) :provenance)
                        (catch Throwable _ false))
          ws  (:workspace ctx)
          jn  (:job-name ctx)
          bn  (:build-number ctx)]
      (when (and flag-on? artifacts-pattern)
        (cond
          (not (and ws jn bn))
          (log-effect d [:provenance/degraded
                         {:reason :missing-build-context
                          :note (cond
                                  (not ws) "no :workspace in ctx"
                                  (not jn) "no :job-name in ctx"
                                  :else "no :build-number in ctx")}])

          :else
          (let [single-stmt (requiring-resolve 'anvil.provenance.statement/single-artifact-statement)
                sign!       (requiring-resolve 'anvil.provenance.cosign/sign-statement!)
                cosign-on?  (requiring-resolve 'anvil.provenance.cosign/cosign-on-path?)
                sibling-pp  (requiring-resolve 'anvil.provenance.cosign/sibling-attestation-path)
                fs-glob     (requiring-resolve 'babashka.fs/glob)]
            (if-not (cosign-on?)
              (log-effect d [:provenance/degraded
                             {:reason :cosign-missing
                              :note "cosign not on PATH — `anvil.provenance.cosign` shells out per AV4-5/R9"}])
              ;; Glob the workspace for files matching the pattern.
              ;; Pattern is operator-supplied via `archiveArtifacts 'pattern'` and
              ;; is interpreted relative to the workspace just like Jenkins does it.
              (let [matches (try (vec (fs-glob ws artifacts-pattern))
                                 (catch Throwable e
                                   (log-effect d [:provenance/degraded
                                                  {:reason :glob-failed
                                                   :pattern artifacts-pattern
                                                   :error (.getMessage e)}])
                                   []))
                    key-path (:provenance-key-path ctx)   ; optional R4 offline-key flow
                    extra-env (cond-> nil
                                (:provenance-key-password ctx)
                                (assoc "COSIGN_PASSWORD" (:provenance-key-password ctx)))]
                (doseq [match matches]
                  (let [art-path (str match)
                        bundle   (sibling-pp art-path)]
                    (try
                      (let [stmt (single-stmt art-path
                                              {:job-name jn
                                               :build-number bn
                                               :scm (:scm ctx)
                                               :jenkinsfile (:jenkinsfile-source ctx)
                                               :started-at (:started-at ctx)
                                               :finished-at (:finished-at ctx)})
                            sign-result (sign! stmt (cond-> {:out-path bundle
                                                             :artifact art-path}
                                                      key-path (assoc :key-path key-path)
                                                      extra-env (assoc :extra-env extra-env)))]
                        (log-effect d [:provenance/attested
                                       {:path art-path
                                        :sha256 (get-in stmt [:subject 0 :digest :sha256])
                                        :bundle bundle
                                        :cosign-version (:cosign-version sign-result)}]))
                      (catch clojure.lang.ExceptionInfo e
                        (log-effect d [:provenance/degraded
                                       {:reason (or (:reason (ex-data e)) :cosign-error)
                                        :path art-path
                                        :error (ex-message e)}]))
                      (catch Throwable e
                        (log-effect d [:provenance/degraded
                                       {:reason :cosign-error
                                        :path art-path
                                        :error (.getMessage e)}])))))))))))
    (ok ctx)))

(defn- h-delete-dir [d _step ctx]
  (log-effect d [:delete-dir {:cwd (:cwd ctx)}])
  (ok ctx))

(defn- h-stash
  "TX11C: real stash. Routes to anvil.compat.jenkins.steps.stash/stash!
   when ctx supplies :workspace and :stash-root; otherwise records the
   request and returns ok (preserves prior recorder-only behavior for
   tests that don't configure a real workspace)."
  [d step ctx]
  (let [ws (:workspace ctx)
        sr (:stash-root ctx)]
    (if (and ws sr (:name step))
      (let [stash-fn (requiring-resolve 'anvil.compat.jenkins.steps.stash/stash!)
            summary (stash-fn {:name (:name step)
                               :workspace ws
                               :stash-root sr
                               :includes (or (:includes step) "**/*")
                               :excludes (:excludes step)})]
        (log-effect d [:stash (select-keys summary
                                           [:name :files-stashed :bytes-stashed])])
        (ok ctx))
      (do (log-effect d [:stash {:args (:args step)
                                 :raw (:raw-args step)
                                 :note "recorder-only — no :workspace + :stash-root in ctx"}])
          (ok ctx)))))

(defn- h-unstash
  "TX11C: real unstash. Same gating as h-stash."
  [d step ctx]
  (let [ws (:workspace ctx)
        sr (:stash-root ctx)
        nm (or (:name step) (:arg step))]
    (if (and ws sr nm)
      (let [unstash-fn (requiring-resolve 'anvil.compat.jenkins.steps.stash/unstash!)
            r (unstash-fn {:name nm :workspace ws :stash-root sr})]
        (log-effect d [:unstash (select-keys r [:name :files-restored :missing?])])
        (if (:missing? r)
          {:status :failed :error :stash-missing :message (:error r) :ctx ctx}
          (ok ctx)))
      (do (log-effect d [:unstash {:name nm
                                   :note "recorder-only — no :workspace + :stash-root in ctx"}])
          (ok ctx)))))

(defn- h-checkout
  "Handle `:jenkins/checkout` steps.

   For explicit checkouts (user wrote `checkout scm` or
   `checkout([...])` in the Jenkinsfile) — record an effect and
   return ok. The runner's pre-build `scm/provision!` already
   populated the workspace; we don't want to re-clone mid-build.

   For AN8-4 implicit checkouts (synthesized by
   `anvil.compat.jenkins.scm-lifecycle/inject-implicit-checkout`
   when the feature flag is on) — actively call `scm/provision!`
   against the `:scm` config on the step (or in ctx). This closes
   the workspace-empty-at-stage-1 gap that the AN7-5c receipt
   documented for zookeeper (`git clean -fxd` → exit 128).

   Idempotent: `scm/provision!` short-circuits to `:refreshed`
   when `.git` already points at the registered URL/branch."
  [d step ctx]
  (let [implicit? (:implicit? step)
        scm-cfg   (or (:scm step) (:scm ctx))
        ws        (when-let [w (:workspace ctx)] (io/file w))]
    (cond
      ;; Implicit declarative-pipeline checkout with a configured SCM
      ;; and a real workspace — actually provision.
      (and implicit? scm-cfg ws)
      (let [r (scm/provision! ws scm-cfg)]
        (log-effect d [:checkout {:implicit? true
                                  :result (:result r)
                                  :sha (:sha r)
                                  :url (:url scm-cfg)
                                  :branch (:branch scm-cfg)
                                  :error (:error r)
                                  :source :scm-lifecycle}])
        (if (= :failed (:result r))
          ;; A failed implicit checkout means stage 1 will run against
          ;; an empty / stale workspace — propagate the failure so the
          ;; classifier sees the honest cause instead of a downstream
          ;; `git clean -fxd: exit 128` red herring.
          {:status :failed
           :error :scm-checkout-failed
           :message (or (:error r) "implicit checkout failed")
           :ctx ctx}
          (ok ctx)))

      ;; Implicit checkout but no SCM configured — record skip and
      ;; return ok. Preserves pre-AN8-4 behavior for jobs registered
      ;; without `:scm` (the IR still honestly reflects Jenkins's
      ;; declarative-lifecycle contract; the operator just hasn't
      ;; wired the config yet).
      implicit?
      (do (log-effect d [:checkout {:implicit? true
                                    :result :skipped
                                    :reason (cond
                                              (nil? scm-cfg) :no-scm-configured
                                              (nil? ws) :no-workspace
                                              :else :unknown)
                                    :source :scm-lifecycle}])
          (ok ctx))

      ;; Explicit `checkout` step — pre-AN8-4 behavior (record the
      ;; effect; runner's per-job provision already ran upfront).
      :else
      (do (log-effect d [:checkout {:spec (or (:spec step) (:ref step))
                                    :args (:args step)}])
          (ok ctx)))))

(defn- h-mail [d step ctx]
  (log-effect d [:mail (or (:args step) (:raw-args step))])
  (ok ctx))

(defn- h-emailext [d step ctx]
  (log-effect d [:emailext (or (:args step) (:raw-args step))])
  (ok ctx))

(defn- h-write-file [d step ctx]
  (log-effect d [:write-file (or (:args step) (:raw-args step))])
  (ok ctx))

(defn- h-read-file [d step ctx]
  (log-effect d [:read-file (or (:args step) (:raw-args step))])
  ;; Real implementation would read + return contents; v1 returns "" so
  ;; callers don't NPE.
  (assoc (ok ctx) :stdout ""))

(defn- h-build [d step ctx]
  (log-effect d [:build (or (:args step) (:raw-args step))])
  (ok ctx))

(defn- h-error [d step ctx]
  (log-effect d [:error (or (:message step) "<unspecified>")])
  ;; `error 'msg'` is an explicit failure in Jenkins.
  {:status :failed :error :jenkins-error
   :message (or (:message step) "error step invoked")
   :ctx ctx})

(defn- h-sleep [d step ctx]
  (log-effect d [:sleep (:args step)])
  (ok ctx))

(defn- h-shared-lib-result
  "Apply a shared-library shim's return map. The shim either records a
   single effect, or signals `:wrap-body?` (run the wrapped body), or
   `:sh-passthrough?` (re-route through h-sh as a real subprocess)."
  [d step ctx result]
  (cond
    (:sh-passthrough? result)
    (let [cmd (get-in result [:effect 1 :cmd])
          sh-step (assoc step :type :jenkins/sh :script cmd)]
      (h-sh d sh-step ctx))

    (:wrap-body? result)
    (do (log-effect d [:shared-lib/wrap-enter (:method result)])
        (let [after (run-body d (or (:body result) []) ctx)]
          (log-effect d [:shared-lib/wrap-leave (:method result)])
          (propagate-or-ok after identity)))

    (:effect result)
    (do (log-effect d (:effect result))
        (ok ctx))

    :else
    (ok ctx)))

(defn- h-unknown [d step ctx]
  ;; First chance: TX11D shared-libs registry (infra.* and friends).
  ;; Second chance: a plugin adapter registered for this step name.
  ;; Final fallback: log the call as :unknown so the migration UX can
  ;; flag it. If the unknown call carries a `:body` (a translated
  ;; closure tail — added by translator/translate-call when the last
  ;; arg is a `:closure`), dispatch the body so nested KNOWN steps
  ;; still execute. This is what makes a Jenkinsfile like
  ;; `withChecks(...) { realtimeJUnit(...) { infra.runMaven(opts) } }`
  ;; reach the `infra.runMaven` shim through TWO shimmed-as-no-op
  ;; outer wrappers.
  (or
   (when-let [handler (shared-libs/handler-for (:name step))]
     (let [r (handler step ctx)]
       (log/debug (str "anvil shared-lib: " (:name step) " → " (or (:method r) :ok)))
       (h-shared-lib-result d step ctx r)))
   (plugins/dispatch-step step ctx)
   (do (log-effect d [:unknown {:name (:name step) :args (:args step)}])
       (if-let [body (:body step)]
         (run-body d body ctx)
         (ok ctx)))))

(defn- h-dir-pseudo
  "Scripted `dir(...) { ... }` execution from inside a script block emits
   :jenkins/dir-enter / :jenkins/dir-leave pseudo-steps through the
   dispatcher (so observers see them in order). They have no side effect
   beyond the log line."
  [d step ctx]
  (log-effect d (case (:type step)
                  :jenkins/dir-enter [:dir/enter (:path step)]
                  :jenkins/dir-leave [:dir/leave (:path step)]))
  (ok ctx))

;; ---------------------------------------------------------------------------
;; Scope-wrapping handler (declarative `dir { steps { … } }`)
;; ---------------------------------------------------------------------------

(defn- run-body
  "Recursively dispatch a sequence of step IR nodes through this dispatcher,
   threading ctx between them. Returns a result map:

     {:status :ok     :ctx CTX}                    — all steps succeeded
     {:status :failed :ctx CTX :error … :message …} — first failure;
                                                      the rest of body
                                                      is short-circuited

   Wrappers (h-timeout, h-with-credentials, h-retry, h-node, h-parallel,
   h-dir, h-with-env, h-with-checks, h-with-maven, h-script) must
   inspect the :status and propagate failures up. Pre-fix this fn
   returned just the ctx, so a `sh 'exit 1'` inside `timeout { … }` or
   `withCredentials { … }` was reported to the orchestrator as
   :ok with the wrapper's ctx — masking failed builds as successful
   (Codex P1, PR #164)."
  [this body initial-ctx]
  (reduce (fn [acc step]
            (let [r (d/dispatch this step (:ctx acc))]
              (if (= :failed (:status r))
                (reduced (assoc r :ctx (or (:ctx r) (:ctx acc))))
                (assoc acc :ctx (or (:ctx r) (:ctx acc))))))
          {:status :ok :ctx initial-ctx}
          body))

(defn- propagate-or-ok
  "Helper for wrappers: take the result returned by run-body and either
   propagate the failure as-is (with the wrapper's cleanup applied to
   :ctx) or return ok with `success-ctx-fn` applied to the success ctx."
  [run-body-result success-ctx-fn]
  (let [{:keys [status ctx] :as r} run-body-result]
    (if (= :failed status)
      (assoc r :ctx (success-ctx-fn ctx))
      (ok (success-ctx-fn ctx)))))

(defn- h-node
  "Scripted-pipeline `node(label) { body }` — resolve the label against
   the agent registry (TX11C), merge the executor's env into ctx, then
   run the body. On label miss we log [warn] and proceed with :default.

   Records :node/enter / :node/leave effects so observers + the
   admin UI see the agent boundary; also records :node/degraded when
   the registry returns a fallback or a :degraded? entry (e.g. the
   Windows labels on Linux hosts)."
  [this step ctx]
  (let [label (or (:label step) "<dynamic>")
        body (or (:body step) [])
        resolved (agent-registry/resolve-label label)
        old-label (:active-node-label ctx)
        old-env (:env ctx {})
        new-env (merge old-env (:env resolved))]
    (log-effect this [:node/enter label])
    (when (:degraded? resolved)
      (log-effect this [:node/degraded
                        {:label label
                         :fallback-from (:fallback-from resolved)
                         :reason (:degrade-reason resolved)}]))
    (let [after (run-body this body (-> ctx
                                        (assoc :active-node-label label)
                                        (assoc :env new-env)
                                        (assoc :active-executor (:executor resolved))))]
      (log-effect this [:node/leave label])
      (propagate-or-ok after
                       #(cond-> (assoc % :env old-env)
                          old-label (assoc :active-node-label old-label)
                          (not old-label) (dissoc :active-node-label))))))

(defn- h-dir [this step ctx]
  (let [path (or (:path step) "<dynamic>")
        old-cwd (:cwd ctx "/workspace")
        new-cwd (str old-cwd "/" path)
        body (or (:body step) [])]
    (log-effect this [:dir/enter new-cwd])
    (let [after (run-body this body (assoc ctx :cwd new-cwd))]
      (log-effect this [:dir/leave new-cwd])
      (propagate-or-ok after #(assoc % :cwd old-cwd)))))

(defn- parse-env-string
  "Split 'KEY=VALUE' (Jenkins withEnv form) into [k v]. Preserves the
   key verbatim — handling of the `KEY+SUFFIX` / `KEY-SUFFIX` forms
   happens at apply time so the suffix is visible during merge."
  [s]
  (when (string? s)
    (let [idx (.indexOf ^String s "=")]
      (when (pos? idx)
        [(subs s 0 idx) (subs s (inc idx))]))))

(defn- apply-env-pair
  "Apply one [k v] pair from withEnv to an env map. Jenkins recognizes
   two override-with-suffix forms in addition to plain assignment
   (Codex P2, PR #164):

     KEY=val            — overrides
     KEY+SUFFIX=val     — PREPENDS to KEY (colon-separated), creating it
                          if absent. Matches Jenkins's idiom for
                          augmenting PATH: `withEnv([\"PATH+MAVEN=…\"])`.
     KEY-SUFFIX=val     — APPENDS to KEY (colon-separated).

   The SUFFIX after the `+` / `-` is documentary — Jenkins doesn't use
   it for anything beyond letting the operator give the addition a
   name in code review. We keep the same liberal acceptance."
  [env [raw-key v]]
  (cond
    (re-find #"^[A-Za-z_][A-Za-z0-9_]*\+" raw-key)
    (let [base (first (str/split raw-key #"\+" 2))
          existing (get env base "")]
      (assoc env base
             (if (str/blank? existing) (str v) (str v ":" existing))))

    (re-find #"^[A-Za-z_][A-Za-z0-9_]*-" raw-key)
    (let [base (first (str/split raw-key #"-" 2))
          existing (get env base "")]
      (assoc env base
             (if (str/blank? existing) (str v) (str existing ":" v))))

    :else
    (assoc env raw-key (str v))))

(defn- h-with-env [this step ctx]
  (let [pairs (keep parse-env-string (:env-strings step))
        old-env (:env ctx {})
        new-env (reduce apply-env-pair old-env pairs)
        body (or (:body step) [])]
    (log-effect this [:with-env/enter (mapv first pairs)])
    (let [after (run-body this body (assoc ctx :env new-env))]
      (log-effect this [:with-env/leave (mapv first pairs)])
      (propagate-or-ok after #(assoc % :env old-env)))))

(defn- credential-secrets
  "Approximate which strings to mask, given a withCredentials credentials list.
   v1 marks the credential id itself and any envVariable values bound. Real
   resolution from a secret store happens in TX5 when anvil wires actual
   credential lookup."
  [credentials]
  (->> credentials
       (mapcat (fn [c]
                 (let [raw (str (or (:raw-args c) (:raw-text c)))]
                   ;; Pick up 'value-like' substrings from the raw args text.
                   ;; If the user supplied envVariable: 'TOKEN', then later
                   ;; ${TOKEN} resolves at runtime and the actual secret comes
                   ;; from the secret store (TX5). For TX4 masking we treat
                   ;; the credential ID + any quoted strings in the raw args
                   ;; as "values that might leak."
                   (->> (re-seq #"['\"]([^'\"]+)['\"]" raw)
                        (map second)))))
       (remove str/blank?)
       set))

(defn- resolve-credential-from-store
  "Look up a credential by id. v0.6 T2: the lookup goes through the
   `anvil.secrets` SecretBackend protocol — the registered backend
   could be the default local-disk store, Vault, KMS, or an
   operator-pluggable impl per AV6-3.

   Single-arity preserved so the v0.5 test contract (which redefs
   this fn) still holds.  Per-build `:secret-resolved` event
   publication happens in `inject-credentials-into-env` via
   `anvil.secrets/publish-resolved-event!` — that way tests redef'ing
   this resolver still drive the emit path naturally without picking
   up an arity change.

   We require the secrets namespace lazily so the dispatcher doesn't
   hard-depend on it from tests that only exercise the recording
   path."
  [credential-id]
  (try
    (when-let [active (requiring-resolve 'anvil.secrets/active-backend)]
      (when-let [resolve! (requiring-resolve 'anvil.secrets/resolve!)]
        (resolve! (active) credential-id)))
    (catch Throwable _
      ;; Fall back to the v0.5 path if the new ns isn't on the
      ;; classpath for some reason — keeps the dispatcher robust.
      (try
        (when-let [lookup (requiring-resolve 'anvil.storage.credentials/lookup)]
          (lookup credential-id))
        (catch Throwable _ nil)))))

(defn- credential-var-bindings
  "Extract the {role → var-name} map from a credential's raw-args text.
   Recognized roles:
     :string    — single `variable:` binding (legacy + string-type creds)
     :username  — `usernameVariable:` (paired with :password)
     :password  — `passwordVariable:`
   Both username + password variables are captured so usernamePassword
   credentials bind two env vars, not just the first match seen
   (Codex P2, PR #164).

   Important: the alternation lists `usernameVariable` and
   `passwordVariable` BEFORE the substring `variable`, so the regex
   engine commits to the longer alternative when both could match
   at the same position."
  [raw]
  (let [pat #"(usernameVariable|passwordVariable|variable)\s*[:= ]\s*['\"]([^'\"]+)['\"]"
        matches (re-seq pat raw)]
    (reduce
     (fn [acc [_ role-name var-name]]
       (assoc acc
              (case role-name
                "variable"         :string
                "usernameVariable" :username
                "passwordVariable" :password)
              var-name))
     {}
     matches)))

(defn- split-username-password
  "ci.jenkins.io / Jenkins's usernamePassword credentials store the
   pair as a `username:password` string. Split on the FIRST `:` so
   passwords containing `:` round-trip correctly. Returns
   [username password] or nil if no separator."
  [^String s]
  (when s
    (let [idx (.indexOf s ":")]
      (when (pos? idx)
        [(subs s 0 idx) (subs s (inc idx))]))))

(defn- file-credential-container-path
  "Canonical container path for a file credential: /anvil-creds/<id>.
   The host path is stored in the credential's :value field; the
   container side is always under /anvil-creds/ so operators know where
   to look."
  [credential-id]
  (str "/anvil-creds/" credential-id))

(defn- inject-credentials-into-env
  "Walk the parsed credentials list, look each up in the store, and
   build a {env-var → value} map for the wrapped block. Returns
   [env-additions, masked-values, unresolved-ids, file-mounts]:
     - env-additions: {STRING STRING} to merge into ctx :env
     - masked-values: #{STRING} of resolved secret values for masking
     - unresolved-ids: [STRING ...] credential ids declared in the
       Jenkinsfile that the store did not resolve. The caller emits
       [:credential-unresolved …] effects for each — the AN4-1 +
       AN4-3 classifier reads those as :credential-unresolved →
       :failure (AN4-4).
     - file-mounts: [{:host-path STRING :container-path STRING
                      :credential-id STRING :var-name STRING}]
       for :file-type credentials. The caller threads these into ctx
       so shell-execute can pass them to the docker backend.

   String credentials bind one variable. usernamePassword credentials
   bind both usernameVariable + passwordVariable to the respective
   halves of the stored `username:password` string. The masker treats
   both halves (and the original combined string) as secrets so the
   console log doesn't leak either.

   File credentials (AN7-3): the stored value is the HOST filesystem
   path to the file. The container receives the file at
   /anvil-creds/<credential-id> and the bound variable is set to that
   path. The host path is NOT added to the secrets masker (it's a
   filesystem path, not a secret value).

   v0.6 T2: `ctx` (the dispatcher ctx) is forwarded to
   `resolve-credential-from-store/2` so the secrets layer can emit a
   `:secret-resolved` SSE event with :job-name + :build-number on
   successful resolution. Pass nil for tests / recording-only paths."
  ([creds] (inject-credentials-into-env creds nil))
  ([creds ctx]
  (reduce
   (fn [[env masks unresolved file-mounts] cred]
     (let [raw (str (or (:raw-args cred) (:raw-text cred) ""))
           id-match (re-find #"credentialsId\s*[:= ]\s*['\"]([^'\"]+)['\"]" raw)
           credential-id (some-> id-match second)
           vars (credential-var-bindings raw)
           t0 (System/nanoTime)
           looked-up (when credential-id
                       (resolve-credential-from-store credential-id))
           _ (when (and ctx credential-id looked-up
                        (string? (:value looked-up))
                        (not (str/blank? (:value looked-up))))
               ;; v0.6 T2.5 — publish :secret-resolved (no value in payload).
               (try
                 (when-let [pub (requiring-resolve 'anvil.secrets/publish-resolved-event!)]
                   (let [ms (long (/ (- (System/nanoTime) t0) 1e6))]
                     (pub ctx credential-id ms)))
                 (catch Throwable _ nil)))
           value (:value looked-up)
           ;; Treat a blank/empty-string :value as unresolved — anvil's
           ;; credentials store allows empty values (PR-review point on
           ;; AN4-4), and silently binding a blank to the env var is
           ;; exactly the v0.3 regression this PR exists to fix.
           usable-value? (and (string? value)
                              (not (str/blank? value)))
           cred-type (:type looked-up)
           is-up? (= :username-password cred-type)
           is-file? (= :file cred-type)]
       (cond
         ;; A credentialsId was declared but the store didn't resolve it
         ;; to a usable value (missing OR present-but-blank OR store
         ;; uninitialized). AN4-4: surface this so the build fails
         ;; honestly instead of silently binding "".
         (and credential-id (not usable-value?))
         [env masks (conj unresolved credential-id) file-mounts]

         (not (and credential-id value))
         [env masks unresolved file-mounts]

         ;; AN7-3: :file credential — host path in :value. Mount into
         ;; /anvil-creds/<id> for docker agents (see h-with-credentials which
         ;; resolves the correct env-var value based on the active agent).
         ;; The host path is NOT masked (it's a filesystem path, not a secret).
         is-file?
         (let [var-name (:string vars)
               container-path (file-credential-container-path credential-id)
               ;; NOTE: env var is NOT set here — h-with-credentials sets it
               ;; to the correct path after checking docker vs non-docker.
               mount {:host-path value
                      :container-path container-path
                      :credential-id credential-id
                      :var-name var-name}]
           [env masks unresolved (conj file-mounts mount)])

         ;; usernamePassword: split `user:pass`, bind each half + the combined
         is-up?
         (if-let [[u p] (split-username-password value)]
           (let [env' (cond-> env
                        (and (:username vars) u) (assoc (:username vars) u)
                        (and (:password vars) p) (assoc (:password vars) p)
                        ;; Some Jenkinsfiles use `variable:` for the combined
                        ;; form even on usernamePassword creds — honour it.
                        (:string vars)          (assoc (:string vars) value))
                 masks' (cond-> masks
                          u     (conj u)
                          p     (conj p)
                          value (conj value))]
             [env' masks' unresolved file-mounts])
           ;; Stored value without `:` — fall back to binding the whole value
           ;; to whichever var is present, plus masking it.
           (let [var-name (or (:string vars) (:username vars) (:password vars))]
             [(cond-> env var-name (assoc var-name value))
              (conj masks value)
              unresolved
              file-mounts]))

         ;; string credentials: one variable
         :else
         (let [var-name (or (:string vars) (:username vars))]
           [(cond-> env var-name (assoc var-name value))
            (conj masks value)
            unresolved
            file-mounts]))))
   [{} #{} [] []]
   (or creds []))))

(defn- h-with-credentials [this step ctx]
  (let [creds (:credentials step)
        body (or (:body step) [])
        [env-additions resolved-secrets unresolved-ids file-mounts]
        (inject-credentials-into-env creds ctx)
        approx-secrets (credential-secrets creds)
        added-secrets (into approx-secrets resolved-secrets)
        old-secrets @(:secrets this)
        new-secrets (into old-secrets added-secrets)
        old-env (:env ctx {})
        ;; AN7-3: resolve file-mount env var values based on agent type.
        ;; For docker agents: env var → /anvil-creds/<id> (the container
        ;; path where the file is mounted). For non-docker agents: env var
        ;; → the host path directly (file is on the executor's filesystem
        ;; and no mount happens). This matches Jenkins's behavior.
        docker-agent? (boolean (docker-spec ctx))
        file-env-additions (reduce (fn [m {:keys [host-path container-path var-name]}]
                                     (if var-name
                                       (assoc m var-name
                                              (if docker-agent? container-path host-path))
                                       m))
                                   {}
                                   file-mounts)
        new-env (merge old-env env-additions file-env-additions)
        ;; AN7-3: merge file-mounts into ctx so shell-execute (via
        ;; backend-wiring) can add -v flags to the docker invocation.
        old-file-mounts (:file-mounts ctx [])
        new-file-mounts (into old-file-mounts file-mounts)]
    (log-effect this [:with-credentials/enter
                      {:count (count creds)
                       :secret-count (count added-secrets)
                       :resolved-from-store (count env-additions)
                       :unresolved-count (count unresolved-ids)
                       :file-credential-count (count file-mounts)}])
    ;; AN7-3: emit :file-credential/mounted for each file mount so operators
    ;; can see which files were bound into the container (host path is in
    ;; the effect; it's a filesystem path, not a secret value).
    (doseq [{:keys [credential-id host-path container-path var-name]} file-mounts]
      (log-effect this [:file-credential/mounted
                        {:credential-id credential-id
                         :host-path host-path
                         :container-path container-path
                         :var-name var-name}]))
    ;; AN4-4: emit one :credential-unresolved effect per credentialId that
    ;; the store didn't resolve. The AN4-3 classifier extension reads
    ;; this as :credential-unresolved → :failure. No more silent bind-
    ;; to-empty-string.
    (doseq [cid unresolved-ids]
      (log-effect this [:credential-unresolved
                        {:credential-id cid
                         :rule :credential-unresolved
                         ;; The store may be uninitialized (test path /
                         ;; missing config), genuinely missing the id, or
                         ;; have it but with a blank value. We can't tell
                         ;; them apart from inject-credentials-into-env's
                         ;; nil-or-record return today, so the explain
                         ;; speaks to the *outcome*, not the cause.
                         :explain (str "credential '" cid
                                       "' did not resolve to a usable"
                                       " value (store may be missing the"
                                       " id, store may not be configured,"
                                       " or the stored value is blank)")}]))
    (reset! (:secrets this) new-secrets)
    (let [new-ctx (-> ctx
                      (assoc :env new-env)
                      (assoc :file-mounts new-file-mounts))
          after (run-body this body new-ctx)]
      (log-effect this [:with-credentials/leave])
      (reset! (:secrets this) old-secrets)
      (propagate-or-ok after #(-> % (assoc :env old-env)
                                    (assoc :file-mounts old-file-mounts))))))

(defn- timeout-ms
  "Compute the timeout in ms from Jenkins's time + unit."
  [time-val unit]
  (when (number? time-val)
    (long (* time-val
             (case (str unit)
               "SECONDS"     1000
               "MILLISECONDS" 1
               "MINUTES"     60000
               "HOURS"       3600000
               "DAYS"        86400000
               60000)))))

(defn- h-timeout [this step ctx]
  (let [time-val (:time step)
        unit     (:unit step "MINUTES")
        body     (or (:body step) [])
        ms       (timeout-ms time-val unit)
        deadline (when ms (+ (System/currentTimeMillis) ms))
        new-ctx  (cond-> ctx deadline (assoc :timeout-deadline deadline))]
    (log-effect this [:timeout/enter {:time time-val :unit unit :ms ms}])
    ;; When :execute? is on, the subprocess respects :timeout-deadline
    ;; in ctx — shell-execute computes `:timeout (deadline - now)`. The
    ;; first subprocess that overruns is killed; subsequent sh's in the
    ;; same scope-wrapper also see the same deadline and are killed
    ;; immediately if already past it.
    (let [after (run-body this body new-ctx)]
      (log-effect this [:timeout/leave])
      (propagate-or-ok after #(dissoc % :timeout-deadline)))))

(defn- h-container
  "v0.4 T2.2 — `container('image') { … }` (parsed by translate-container).

   Mutates ctx :active-agent to `{:docker {:image …}}` for the body's
   duration; the existing shell-execute path (AN5-3b) recognizes
   docker-shaped active-agents and routes through chengis-core's
   `DockerBackend`. Reuses AN5-3 plumbing per **AV4-2** — no new
   container abstraction lives in anvil.

   Restores the outer :active-agent on exit (nested `container { … }`
   nesting and matrix-cell-per-container-image both rely on this).

   Effects:
     [:container/enter {:image <str>}]
     ... body effects ...
     [:container/leave {:image <str>}]
     [:container/missing-image] (when no image string was parseable —
                                 we still run the body so the build
                                 doesn't silently lose work; the
                                 effect makes the gap observable)

   Gated by `:anvil.features/container-step` (closed-by-default per
   AV4-7): when off, we fall through to the existing host-shell
   path with an `[:container/degraded {:image <str> :reason :flag-off}]`
   effect so operators can see WHY the container wasn't honored."
  [d step ctx]
  (let [image (:image step)
        body (or (:body step) [])
        outer-agent (:active-agent ctx)
        feat-on? (try
                   ((requiring-resolve 'anvil.features/enabled?) :container-step)
                   (catch Throwable _ false))]
    (cond
      (nil? image)
      (do
        (log-effect d [:container/missing-image])
        (let [after (run-body d body ctx)]
          (propagate-or-ok after identity)))

      (not feat-on?)
      (do
        (log-effect d [:container/degraded
                       {:image image :reason :flag-off}])
        (let [after (run-body d body ctx)]
          (propagate-or-ok after identity)))

      :else
      (let [new-agent (-> (or outer-agent {})
                          (assoc :docker {:image image}))]
        (log-effect d [:container/enter {:image image}])
        (let [after (run-body d body (assoc ctx :active-agent new-agent))]
          (log-effect d [:container/leave {:image image}])
          (propagate-or-ok after
                           #(if outer-agent
                              (assoc % :active-agent outer-agent)
                              (dissoc % :active-agent))))))))

(defn- h-retry
  "Jenkins-Pipeline `retry(N) { body }` — run body up to N times,
   stopping at the first :ok.  Each attempt gets its 1-based index
   threaded into ctx via `:current-retry-attempt`, so wrappers below
   that care (h-junit's scanner pass to record-build-results! at
   attempt-number, v0.4 T1.5) can produce per-attempt rows.

   Effects:
     [:retry/enter   {:count N}]                 — once, on entry
     [:retry/attempt {:index K :status …}]       — once per attempt
                                                   (K = 1..N), status
                                                   is :ok or :failed
     [:retry/leave   {:attempts K :outcome …}]   — once, on exit;
                                                   outcome is :ok if
                                                   any attempt
                                                   succeeded, :failed
                                                   if all N failed

   Outer-ctx `:current-retry-attempt` (if any) is restored on exit so
   nested retries don't leak the inner counter."
  [this step ctx]
  (let [n           (max 1 (or (:count step) 1))
        body        (or (:body step) [])
        outer-attempt (:current-retry-attempt ctx)]
    (log-effect this [:retry/enter {:count n}])
    (loop [attempt 1
           last-result nil]
      (let [attempt-ctx (assoc ctx :current-retry-attempt attempt)
            r (run-body this body attempt-ctx)
            status (:status r)]
        (log-effect this [:retry/attempt {:index attempt :status status}])
        (cond
          (= :ok status)
          (do
            (log-effect this [:retry/leave {:attempts attempt :outcome :ok}])
            (propagate-or-ok r
                             #(if outer-attempt
                                (assoc % :current-retry-attempt outer-attempt)
                                (dissoc % :current-retry-attempt))))

          (< attempt n)
          (recur (inc attempt) r)

          :else  ;; final attempt failed
          (do
            (log-effect this [:retry/leave {:attempts attempt :outcome :failed}])
            (propagate-or-ok r
                             #(if outer-attempt
                                (assoc % :current-retry-attempt outer-attempt)
                                (dissoc % :current-retry-attempt)))))))))

(defn- h-properties
  "TX11E: `properties([buildDiscarder(...), disableConcurrentBuilds(...)])`.
   Recorded as a side-effect so observers can see what was configured;
   not enforced beyond what anvil's queue already does
   (disableConcurrentBuilds maps to the per-job concurrency cap)."
  [this step _ctx]
  (log-effect this [:properties {:raw-args (:raw-args step)
                                 :shimmed? true}])
  (ok _ctx))

(defn- h-with-checks
  "TX11E: `withChecks { body }` — pass through. The GitHub Checks API
   isn't called; the body runs unchanged."
  [this step ctx]
  (log-effect this [:with-checks/enter (:raw-args step)])
  (let [after (run-body this (or (:body step) []) ctx)]
    (log-effect this [:with-checks/leave])
    (propagate-or-ok after identity)))

(defn- h-with-maven
  "TX11E: `withMaven { body }` — pass through. PATH/JAVA_HOME come
   from the agent registry's label env (h-node merges them into ctx)."
  [this step ctx]
  (log-effect this [:with-maven/enter (:raw-args step)])
  (let [after (run-body this (or (:body step) []) ctx)]
    (log-effect this [:with-maven/leave])
    (propagate-or-ok after identity)))

(defn- h-parallel [this step ctx]
  (let [branches (or (:branches step) {})]
    (log-effect this [:parallel/start {:branches (vec (keys branches))}])
    ;; v1: sequential execution of each branch. True concurrency lands when
    ;; this dispatcher rides chengis.engine.executor's work scheduler (TX9).
    ;; Failure semantics: any branch's failure propagates up; later
    ;; branches still run (matches Jenkins `failFast: false` default).
    ;; If at least one failed, return :failed with the first failure
    ;; result so the orchestrator marks the build failed.
    (let [results (reduce
                   (fn [acc [name steps]]
                     (log-effect this [:parallel/branch-start name])
                     (let [after (run-body this steps ctx)]
                       (log-effect this [:parallel/branch-end name])
                       (conj acc {:name name :result after})))
                   []
                   branches)
          failures (filter #(= :failed (get-in % [:result :status])) results)]
      (log-effect this [:parallel/end])
      (if (seq failures)
        (let [first-fail (:result (first failures))]
          (assoc first-fail
                 :ctx ctx
                 :message (str "parallel: " (count failures) "/" (count results)
                               " branches failed; first: "
                               (or (:message first-fail) (str (:error first-fail))))))
        (ok ctx :output (str "[parallel: " (count results) " branches]"))))))

;; ---------------------------------------------------------------------------
;; Script-block handler — delegate to runtime
;; ---------------------------------------------------------------------------

(defn- h-script [this step ctx]
  (let [ctx-atom (atom ctx)
        ;; Prepend Jenkinsfile top-level fn defs (extracted by the
        ;; translator) so calls like `isDeployedBranch()` or
        ;; `mavenBuild(...)` defined OUTSIDE `pipeline { ... }` are
        ;; visible to script-block compilation. Without :preamble (old
        ;; IR or scripted-pipeline source), use just the body source —
        ;; backward compat.
        full-source (str (or (:preamble step) "") "\n" (:body-source step))]
    (try
      (let [_result (runtime/run-script-block full-source this ctx-atom)]
        (ok @ctx-atom :output "[script block]"))
      (catch Exception e
        (log-effect this [:script-failed (.getMessage e)])
        {:status :failed :error :script-block-failed
         :message (.getMessage e)
         :ctx @ctx-atom}))))

(defn- h-scripted-eval
  "Tier-3 — run the WHOLE scripted Jenkinsfile through Groovy + anvil's
   expanded Pipeline DSL bindings. The full source is in (:source step).

   This is the path that handles `axes.values().combinations { def
   (platform,jdk) = it; sh \"build-${platform}-jdk${jdk}\" }` correctly:
   Groovy itself does the iteration + GString interpolation, and
   anvil's __sh closure routes the resolved command through h-sh."
  [this step ctx]
  (let [ctx-atom (atom ctx)
        run-scripted (requiring-resolve 'anvil.compat.jenkins.scripted-runtime/run-scripted-file)]
    (try
      (run-scripted (:source step) this ctx-atom)
      (ok @ctx-atom :output "[scripted-eval]")
      (catch Exception e
        (log-effect this [:scripted-eval-failed (.getMessage e)])
        {:status :failed :error :scripted-eval-failed
         :message (.getMessage e)
         :ctx @ctx-atom}))))

;; ---------------------------------------------------------------------------
;; Dispatch table + record
;; ---------------------------------------------------------------------------

(def plugin-step-types
  "Plugin-step :jenkins/* types this dispatcher records as leaf side effects.
   The dispatcher does not try to emulate plugin behavior; it logs the call
   so observers / downstream tooling can react. Anvil's plugin SDK (TX5)
   adds the actual implementations on a per-plugin basis."
  #{:jenkins/record-issues :jenkins/slack-send :jenkins/milestone
    :jenkins/with-sonarqube-env :jenkins/publish-coverage :jenkins/publish-html
    :jenkins/lock :jenkins/ssh-agent :jenkins/ssh-publisher
    :jenkins/nexus-upload :jenkins/wait-quality-gate :jenkins/add-failed-stage
    :jenkins/send-notifications :jenkins/send-error-notification
    :jenkins/send-success-notification :jenkins/notify-slack
    :jenkins/discover-git-ref-build :jenkins/pipeline-helpers :jenkins/report-portal})

(def step-types
  "Every :jenkins/* step type this dispatcher handles."
  (into #{:jenkins/sh :jenkins/bat :jenkins/echo
          :jenkins/junit :jenkins/archive-artifacts :jenkins/delete-dir
          :jenkins/stash :jenkins/unstash :jenkins/checkout
          :jenkins/mail :jenkins/emailext :jenkins/write-file :jenkins/read-file
          :jenkins/build :jenkins/error :jenkins/sleep
          :jenkins/dir :jenkins/dir-enter :jenkins/dir-leave :jenkins/node
          :jenkins/with-env :jenkins/with-credentials :jenkins/timeout
          :jenkins/retry :jenkins/parallel :jenkins/container
          :jenkins/properties :jenkins/with-checks :jenkins/with-maven
          :jenkins/script :jenkins/scripted-eval :jenkins/unknown
          :jenkins/agent-stage-enter :jenkins/agent-stage-leave}
        plugin-step-types))

(defn- h-plugin-leaf
  "Generic handler for plugin-step types: log the call, return :ok."
  [d step ctx]
  (let [t (:type step)
        tag (-> t name keyword)]
    (log-effect d [tag (or (:raw-args step) (:args step))])
    (ok ctx)))

(defn- resolve-agent
  "Pick the agents.edn entry for a declarative agent spec.
   `agent { label '…' }` and `agent { node { label '…' } }` resolve
   via the label. `agent any` and `agent none` resolve through the
   registry's blank-label default (so :default applies). Returns nil
   only when the spec is genuinely unrecognized (e.g. :kubernetes
   that's deliberately deferred)."
  [a]
  (cond
    (string? (:label a))      (agent-registry/resolve-label (:label a))
    (= :node-label (:type a)) (agent-registry/resolve-label (:label a))
    (= :any (:type a))        (agent-registry/resolve-label nil)
    (= :none (:type a))       (agent-registry/resolve-label nil)
    :else                     nil))

(defn- dockerfile-agent-feature-on? []
  (try
    ((requiring-resolve 'anvil.features/enabled?) :dockerfile-agent)
    (catch Throwable _ false)))

(defn- dockerfile-multistage-feature-on?
  "v0.6 T3 — the :anvil.features/dockerfile-multistage flag gates
   `--target` forwarding + the matching cache-key extension. When off,
   a dockerfile spec carrying :target is honored *without* `--target`
   (same as v0.4 AN6-3 behavior) so the existing single-stage path is
   never broken by an unknown flag value."
  []
  (try
    ((requiring-resolve 'anvil.features/enabled?) :dockerfile-multistage)
    (catch Throwable _ false)))

(defn- publish-dockerfile-built-event!
  "Publish a :dockerfile-built event to the per-build bus topic when we
   can identify a build. Best-effort — a missing bus or bad ctx never
   aborts the step. Mirrors `publish-cache-event!` above."
  [ctx df-result filename target]
  (try
    (let [publish!    (requiring-resolve 'anvil.events.bus/publish!)
          topic-build (requiring-resolve 'anvil.events.topics/topic-build)
          jn  (:job-name ctx)
          bn  (:build-number ctx)]
      (when (and jn bn)
        (publish! (topic-build jn bn)
                  (cond-> {:type :dockerfile-built
                           :job-name jn
                           :build-number bn
                           :dockerfile-path filename
                           :image-tag (:tag df-result)
                           :cache-hit? (boolean (:cached? df-result))
                           :duration-ms (or (:duration-ms df-result) 0)}
                    target (assoc :target target)))))
    (catch Throwable t
      (log/debug t "anvil.dispatcher: dockerfile-built publish failed (non-fatal)"))))

(defn- unhonored-container-agent-shape
  "Returns a keyword naming the unhonored agent shape when `:agent step`
   is a container/cluster shape that this runner cannot actually execute
   in the current mode, or nil when the agent is honored.

   Today's honor table:
     mode             | docker        | dockerfile               | kubernetes
     -----------------+---------------+--------------------------+-------------
     :execute? true   | honored       | honored if flag on (AN6-3) | UNHONORED
     :execute? false  | UNHONORED     | UNHONORED                | UNHONORED

   Rationale: in record-only mode every container agent is bypassed by
   construction (the dispatcher records shell calls without forking
   subprocesses). In execute mode, `agent { docker }` runs via
   `build-docker-args` against the host `docker` CLI — so it's honored.
   `dockerfile` is honored when the v0.4 AN6-3 flag is on AND we're in
   execute mode (the build needs a real docker daemon to materialize
   the image). `kubernetes` has no runtime in anvil today; emitting
   `:agent/degraded` for it is the AN4-1-style honesty signal that
   tells the classifier these were silently skipped."
  [d agent-spec]
  (let [execute? (:execute? d)]
    (cond
      (and (map? agent-spec) (:dockerfile agent-spec)
           (not (and execute? (dockerfile-agent-feature-on?))))  :dockerfile
      (and (map? agent-spec) (= :kubernetes (:type agent-spec))) :kubernetes
      (and (map? agent-spec) (:docker agent-spec)
           (not execute?))                                :docker
      :else nil)))

(defn- h-agent-stage-enter [d step ctx]
  (log-effect d (agent/stage-enter-event (:stage step) (:agent step)))
  ;; v0.4 AN6-1 — when the translator resolved a parameter-driven
  ;; nested-label to a static choice (apache-activemq's
  ;; agent { label { label params.nodeLabel } }), surface the
  ;; inference so operators see WHICH parameter and value were
  ;; chosen at translation time.
  (when-let [inf (:inferred-from (:agent step))]
    (log-effect d [:agent/inferred-from-choice
                   {:param (:param-name inf)
                    :chosen (:label (:agent step))
                    :source (:source inf)
                    :stage (:stage step)}]))
  ;; AN6-1 fallback path: nested-label form without parseable
  ;; parameters → degrade reason is set on the agent ir.  Emit a
  ;; richer effect so this build's wild-corpus classification
  ;; carries a clearer 'param-driven-label' instead of the generic
  ;; 'no agents.edn entry'.
  (when (= :param-driven-label (:degrade-reason (:agent step)))
    (log-effect d [:agent/degraded
                   {:label "<dynamic>"
                    :reason :param-driven-label
                    :stage (:stage step)
                    :explain "agent { label { label params.X } } — no parameters block parseable"}]))
  ;; AN7-2 — GString interpolation result effects.
  ;; (a) When the translator resolved a GString label (e.g.
  ;;     `agent { label "${PLATFORM}" }` → "linux" from a choice default),
  ;;     emit :agent/interpolated so operators see the substitution.
  ;; (b) When the translator could NOT resolve (no matching parameter found
  ;;     or no default), emit :translator/unresolved-interpolation per AV5-6.
  ;;     The classifier reads this as honest :unresolved-interpolation.
  (when-let [tpl (:interpolated-from (:agent step))]
    (log-effect d [:agent/interpolated
                   {:gstring-template tpl
                    :resolved-to (:label (:agent step))
                    :stage (:stage step)}]))
  (when (= :unresolved-interpolation (:degrade-reason (:agent step)))
    (log-effect d [:translator/unresolved-interpolation
                   {:gstring-template (:gstring-template (:agent step))
                    :unresolved-vars  (:unresolved-vars (:agent step))
                    :stage (:stage step)
                    :explain (str "agent { label \"" (:gstring-template (:agent step))
                                  "\" } — "
                                  (pr-str (:unresolved-vars (:agent step)))
                                  " not in parameters block or no default value")}]))
  ;; Resolve label-based declarative agents through TX11C's registry
  ;; (the same path scripted `node('…')` uses). Without this,
  ;; declarative jobs like `agent { label 'maven-21' }` or even
  ;; `agent any` got the env from the runner's defaults only — tools
  ;; registered in agents.edn were invisible to sh
  ;; (Codex P2, PR #164: declarative-label + agent-any).
  ;;
  ;; Save the pre-enter env under :pre-agent-env so the matching
  ;; leave handler can restore it. Pre-fix the leave just dropped
  ;; markers, and a later stage with no resolved env inherited the
  ;; previous label's PATH/JAVA_HOME — tools leaked across stages.
  ;;
  ;; AN4-2: emit :agent/degraded for container shapes this runner cannot
  ;; honor — dockerfile and kubernetes (always), docker (in record-only
  ;; mode). The AN4-1 classifier consumes these as :unsupported so a
  ;; build with `agent { dockerfile … }` no longer silently SUCCEEDs.
  (when-let [unhonored-shape (unhonored-container-agent-shape d (:agent step))]
    (log-effect d [:agent/degraded
                   {:requested-agent {:type (name unhonored-shape)
                                      :stage (:stage step)}
                    :reason :runtime-unsupported
                    :explain (str "agent { " (name unhonored-shape) " }: "
                                  (case unhonored-shape
                                    :docker
                                    "no container runtime in record-only mode"
                                    :dockerfile
                                    "dockerfile agents are not yet implemented"
                                    :kubernetes
                                    "kubernetes agents are not yet implemented")
                                  " — the stage body still dispatches but"
                                  " its container/cluster requirement is"
                                  " not honored")}]))
  (let [resolved (resolve-agent (:agent step))
        old-env (:env ctx {})
        merged-env (if resolved (merge old-env (:env resolved)) old-env)
        label (or (:label (:agent step))
                  (some-> (:agent step) :type name))
        ;; v0.4 AN6-3 — `agent { dockerfile { filename '…' } }`. When
        ;; the feature is on AND we're in execute mode, materialize
        ;; the image via anvil.tools.dockerfile and upgrade
        ;; ctx :active-agent to {:docker {:image <tag>}}. The
        ;; existing AN5-3 DockerBackend bridge then routes h-sh.
        dockerfile-spec (:dockerfile (:agent step))
        df-result (when (and dockerfile-spec
                             (:execute? d)
                             (dockerfile-agent-feature-on?))
                    (let [filename (or (:filename dockerfile-spec) "Dockerfile")
                          ws (:workspace ctx)
                          ;; v0.6 T3 — forward :target + :dir only when
                          ;; the multistage flag is on. With the flag
                          ;; off, both fall back to nil and the v0.4
                          ;; AN6-3 single-stage path is preserved.
                          multistage? (dockerfile-multistage-feature-on?)
                          target (when multistage? (:target dockerfile-spec))
                          dir    (when multistage? (:dir dockerfile-spec))
                          ensure! (requiring-resolve 'anvil.tools.dockerfile/ensure-image!)
                          r (ensure! ws filename {:execute? true
                                                  :target target
                                                  :dir dir})]
                      (log-effect d [(if (:cached? r)
                                       :dockerfile/image-cached
                                       :dockerfile/image-built)
                                     (cond-> {:tag (:tag r)
                                              :exit (:exit r)
                                              :filename filename
                                              :workspace ws
                                              :missing-dockerfile? (boolean (:missing-dockerfile? r))}
                                       target (assoc :target target)
                                       dir    (assoc :dir dir))])
                      ;; v0.6 T3 — emit :dockerfile-built SSE event on
                      ;; every honored build (cache hit OR miss). Topic
                      ;; lookup requires job-name + build-number on ctx;
                      ;; absent those (record-only / unit-test paths),
                      ;; the helper silently no-ops.
                      (when (and (not (:missing-dockerfile? r))
                                 (:tag r))
                        (publish-dockerfile-built-event! ctx r filename target))
                      r))
        dockerfile-upgrade (when (and df-result (zero? (:exit df-result)))
                             {:docker {:image (:tag df-result)}
                              :resolved-from-dockerfile
                              (or (:filename dockerfile-spec) "Dockerfile")})
        ;; AN5-3c: when the registry resolves the label to a docker
        ;; executor (e.g. agents.edn maps "ubuntu" → {:executor :docker
        ;; :image "eclipse-temurin:21"}), upgrade ctx :active-agent
        ;; from the original {:label "ubuntu"} shape to a
        ;; {:docker {:image "..."}} shape so AN5-3b's backend-wiring
        ;; routes h-sh through the chengis-core DockerBackend.
        ;; This is the bridge between Jenkins's "named agents" model
        ;; and anvil's pluggable executors — the unlock for the
        ;; wild-corpus dirty-dozen, all of which use agent { label
        ;; '...' } rather than agent { docker { ... } }.
        ;;
        ;; AN8-1: when the stage's effective `tools { maven 'X' jdk 'Y' }`
        ;; spec resolves through :anvil.tools/images in anvil.edn to a
        ;; pre-baked image, AND the current agent isn't already declaring
        ;; its own docker image (Jenkinsfile-declared `agent { docker
        ;; { image '...' } }` wins — operator never overrides an
        ;; explicit author choice), upgrade :active-agent to that image.
        ;; No mapping → :tools/unmapped effect + the existing agent
        ;; resolution stands.
        tools-spec (:tools step)
        tools-feature-on? (try (features/enabled? :tools-directive)
                               (catch Throwable _ false))
        explicit-docker? (or (:docker (:agent step))
                             (:dockerfile (:agent step))
                             (and resolved (= :docker (:executor resolved))))
        tools-resolution (when (and tools-feature-on?
                                    (seq tools-spec)
                                    (not explicit-docker?))
                           (tools-images/resolve-image tools-spec))
        _ (when (and tools-feature-on? (seq tools-spec))
            (if (and tools-resolution (:image tools-resolution))
              (log-effect d [:tools/resolved
                             {:stage (:stage step)
                              :tools tools-spec
                              :matched-key (:matched-key tools-resolution)
                              :image (:image tools-resolution)}])
              (log-effect d [:tools/unmapped
                             {:stage (:stage step)
                              :tools tools-spec
                              :candidate-keys (:candidate-keys tools-resolution)
                              :explain (str "no :anvil.tools/images mapping for "
                                            (pr-str tools-spec)
                                            " — falling back to current agent. "
                                            "Add a mapping under one of the candidate keys "
                                            "in anvil.edn.")}])))
        tools-upgrade (when (and tools-resolution (:image tools-resolution))
                        {:docker {:image (:image tools-resolution)}
                         :resolved-from-tools
                         {:tools tools-spec
                          :matched-key (:matched-key tools-resolution)}})
        active-agent (or dockerfile-upgrade
                         (and resolved
                              (= :docker (:executor resolved))
                              (:docker resolved)
                              {:docker (:docker resolved)
                               :resolved-from-label label})
                         tools-upgrade
                         (:agent step))]
    (when (and resolved (:degraded? resolved))
      (log-effect d [:agent/degraded
                     {:label label
                      :fallback-from (:fallback-from resolved)
                      :reason (:degrade-reason resolved)}]))
    (ok (cond-> (assoc ctx
                       :active-agent active-agent
                       :active-stage (:stage step)
                       :pre-agent-env old-env
                       :env merged-env)
          resolved (assoc :active-executor (:executor resolved))))))

(defn- h-agent-stage-leave [d step ctx]
  (log-effect d (agent/stage-leave-event (:stage step) (:agent step)))
  ;; Restore the pre-enter env saved by h-agent-stage-enter so tools
  ;; configured for one stage's label can't leak into the next
  ;; stage's env (Codex P2, PR #164).
  (let [restored (if (contains? ctx :pre-agent-env)
                   (assoc ctx :env (:pre-agent-env ctx))
                   ctx)]
    (ok (dissoc restored
                :active-agent :active-stage :active-executor :pre-agent-env))))

(defn- dispatch-step [this step ctx]
  (let [t (:type step)]
    (cond
      (contains? plugin-step-types t) (h-plugin-leaf this step ctx)
      :else
      (case t
        :jenkins/sh                (h-sh this step ctx)
        :jenkins/bat               (h-bat this step ctx)
        :jenkins/echo              (h-echo this step ctx)
        :jenkins/junit             (h-junit this step ctx)
        :jenkins/archive-artifacts (h-archive this step ctx)
        :jenkins/delete-dir        (h-delete-dir this step ctx)
        :jenkins/stash             (h-stash this step ctx)
        :jenkins/unstash           (h-unstash this step ctx)
        :jenkins/checkout          (h-checkout this step ctx)
        :jenkins/mail              (h-mail this step ctx)
        :jenkins/emailext          (h-emailext this step ctx)
        :jenkins/write-file        (h-write-file this step ctx)
        :jenkins/read-file         (h-read-file this step ctx)
        :jenkins/build             (h-build this step ctx)
        :jenkins/error             (h-error this step ctx)
        :jenkins/sleep             (h-sleep this step ctx)
        :jenkins/dir               (h-dir this step ctx)
        (:jenkins/dir-enter
         :jenkins/dir-leave)       (h-dir-pseudo this step ctx)
        :jenkins/node              (h-node this step ctx)
        :jenkins/with-env          (h-with-env this step ctx)
        :jenkins/with-credentials  (h-with-credentials this step ctx)
        :jenkins/timeout           (h-timeout this step ctx)
        :jenkins/retry             (h-retry this step ctx)
        :jenkins/parallel          (h-parallel this step ctx)
        :jenkins/container         (h-container this step ctx)
        :jenkins/properties        (h-properties this step ctx)
        :jenkins/with-checks       (h-with-checks this step ctx)
        :jenkins/with-maven        (h-with-maven this step ctx)
        :jenkins/script            (h-script this step ctx)
        :jenkins/scripted-eval     (h-scripted-eval this step ctx)
        :jenkins/unknown           (h-unknown this step ctx)
        :jenkins/agent-stage-enter (h-agent-stage-enter this step ctx)
        :jenkins/agent-stage-leave (h-agent-stage-leave this step ctx)))))

(defrecord AnvilJenkinsDispatcher [effects sh-canned secrets execute? sh-fail-attempts]
  d/StepDispatcher
  (supports? [_ step] (contains? step-types (:type step)))
  (dispatch  [this step ctx] (dispatch-step this step ctx))
  (describe  [_] (str "anvil-jenkins" (when execute? ":execute"))))

(defn make
  "Construct a fresh AnvilJenkinsDispatcher.

   `:effects`           chronological side-effects vector (atom)
   `:sh-canned`         cmd-string → canned-stdout map (atom) for tests
   `:secrets`           set of strings to redact from logged effects
                        (atom); mutated by withCredentials handlers
   `:execute?`          if true, sh/bat actually subprocess-execute (TX9).
                        Default false — record-only, matching the
                        spike + TX4-TX8 test surface.
   `:sh-fail-attempts`  v0.4 T1.5 test hook (atom of cmd → remaining
                        failures). When h-sh sees a cmd whose count is
                        > 0 it returns :failed and decrements; tests
                        use this to exercise retry-loop semantics
                        without spinning a real subprocess. nil
                        (default) → never injects failure."
  ([] (make {}))
  ([{:keys [effects sh-canned secrets execute? sh-fail-attempts]
     :or {effects (atom [])
          sh-canned (atom {})
          secrets (atom #{})
          execute? false}}]
   (->AnvilJenkinsDispatcher effects sh-canned secrets execute? sh-fail-attempts)))
