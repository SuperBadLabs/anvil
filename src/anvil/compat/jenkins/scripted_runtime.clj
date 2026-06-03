(ns anvil.compat.jenkins.scripted-runtime
  "Tier-3 scripted-Pipeline runtime — runs an entire jenkinsci/jenkins-
   style scripted Jenkinsfile through `eval-with-dsl-base` with a wide
   enough DSL binding set that GStrings, `combinations`, destructured
   `def (a,b) = it`, control flow, and most plugin steps work natively
   (because Groovy itself handles them).

   The existing `runtime.clj` ships the minimal set (sh, dir, echo, a
   handful of leafs) sufficient for `script {}` blocks inside declarative
   Pipelines. This namespace EXTENDS that with block-step closures (node,
   stage, parallel, retry, timeout, withCredentials, withChecks, etc.),
   DSL globals (currentBuild, pullRequest, infra), and helpers (pwd,
   isUnix, readFile, properties) so the full scripted Jenkinsfile body
   can run through Groovy as one Script — which is what makes
   `\"foo-${platform}\"` interpolate against the live `it`-destructured
   bindings inside `axes.values().combinations { … }`."
  (:require [anvil.compat.jenkins.runtime :as rt]
            [anvil.compat.jenkins.groovy :as g]
            [chengis.engine.dispatcher :as d]
            [taoensso.timbre :as log])
  (:import [groovy.lang Closure]
           [groovy.util Expando]))

;; ---------------------------------------------------------------------------
;; Internals (mostly re-exposes of runtime.clj privates via `requiring-resolve`
;; pattern is awkward; here we just re-implement the few thin helpers we need).
;; ---------------------------------------------------------------------------

(defn- record-effect
  "Record a synthetic block-scope event directly on the dispatcher's
   effects ledger. We bypass d/dispatch for these because they're pure
   scaffolding (enter/leave markers) — anvil's StepDispatcher protocol
   doesn't recognize them and would throw."
  [dispatcher kind payload]
  (let [effects-atom (:effects dispatcher)]
    (when effects-atom
      (swap! effects-atom conj [kind payload]))))

(defn- emit
  "Dispatch a real step through the dispatcher (e.g. :jenkins/sh).
   Returns the result's :stdout. For scaffolding pseudo-steps with a
   :scaffold? truthy marker, record on the effects ledger directly."
  [dispatcher ctx-atom step]
  (if (:scaffold? step)
    (do (record-effect dispatcher (:type step) (dissoc step :type :scaffold?))
        nil)
    (let [r (d/dispatch dispatcher step @ctx-atom)
          new-ctx (or (:ctx r) @ctx-atom)]
      (reset! ctx-atom new-ctx)
      (:stdout r))))

(defn- coerce-string [x] (if (nil? x) "" (str x)))

(defn- as-map
  "Coerce a Groovy LinkedHashMap or Clojure map to Clojure map keyed by
   the original keys as strings."
  [m]
  (cond
    (map? m)         (into {} (for [[k v] m] [(name k) v]))
    (instance? java.util.Map m) (into {} (for [[k v] m] [(str k) v]))
    :else            {}))

;; ---------------------------------------------------------------------------
;; Block-step closure factories
;; ---------------------------------------------------------------------------

(defn- block-closure
  "Generic block step: emit an :enter effect with the step's args, run
   the closure body, emit :leave. `kind` is the IR :type keyword."
  [dispatcher ctx-atom kind]
  (g/clojure-fn->groovy-closure
   (fn
     ([^Closure body]
      (emit dispatcher ctx-atom {:type kind :args []})
      (let [r (.call body)]
        (emit dispatcher ctx-atom {:type (keyword (str (name kind) "-end")) :args []})
        r))
     ([arg ^Closure body]
      (emit dispatcher ctx-atom {:type kind :arg arg})
      (let [r (.call body)]
        (emit dispatcher ctx-atom {:type (keyword (str (name kind) "-end")) :arg arg})
        r))
     ([a b ^Closure body]
      (emit dispatcher ctx-atom {:type kind :args [a b]})
      (let [r (.call body)]
        (emit dispatcher ctx-atom {:type (keyword (str (name kind) "-end"))})
        r)))))

(defn- make-stage-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [name ^Closure body]
     (emit dispatcher ctx-atom
           {:scaffold? true :type :jenkins/scripted-stage-enter :name (coerce-string name)})
     (try (.call body)
          (finally
            (emit dispatcher ctx-atom
                  {:scaffold? true :type :jenkins/scripted-stage-leave
                   :name (coerce-string name)}))))))

(defn- make-node-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn
     ([^Closure body]
      (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/node-enter :label "any"})
      (try (.call body)
           (finally
             (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/node-leave}))))
     ([label ^Closure body]
      (emit dispatcher ctx-atom
            {:scaffold? true :type :jenkins/node-enter :label (coerce-string label)})
      (try (.call body)
           (finally
             (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/node-leave})))))))

(defn- make-retry-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [arg ^Closure body]
     ;; arg is either an Integer count or a Map with :count/:conditions.
     (let [m (if (instance? java.util.Map arg) (as-map arg) {"count" arg})]
       (emit dispatcher ctx-atom
             {:scaffold? true :type :jenkins/retry-enter
              :count (or (get m "count") 1)
              :conditions (get m "conditions")})
       (try (.call body)
            (finally
              (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/retry-leave})))))))

(defn- make-timeout-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [opts ^Closure body]
     (let [m (as-map opts)
           t (or (get m "time") 0)
           u (or (get m "unit") "MINUTES")]
       (emit dispatcher ctx-atom
             {:scaffold? true :type :jenkins/timeout-enter :time t :unit (coerce-string u)})
       (try (.call body)
            (finally
              (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/timeout-leave})))))))

(defn- make-withCredentials-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [creds-list ^Closure body]
     (emit dispatcher ctx-atom
           {:scaffold? true :type :jenkins/with-credentials-enter
            :secret-count (if (sequential? creds-list) (count creds-list) 1)})
     (try (.call body)
          (finally
            (emit dispatcher ctx-atom
                  {:scaffold? true :type :jenkins/with-credentials-leave}))))))

(defn- make-withChecks-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [opts ^Closure body]
     (emit dispatcher ctx-atom
           {:scaffold? true :type :jenkins/with-checks-enter :opts (as-map opts)})
     (try (.call body)
          (finally
            (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/with-checks-leave}))))))

(defn- make-dir-closure
  "Override runtime.clj's __dir to accept BOTH absolute and relative
   paths. Jenkins's dir(path) does this; the runtime.clj version
   always concatenates onto cwd which doubles up when path is absolute
   (the unmodified ci.jenkins.io Jenkinsfile passes `dir(pwd(tmp:true))`
   which is absolute)."
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [path ^Closure body]
     (let [old-cwd (or (:cwd @ctx-atom) "/workspace")
           p (coerce-string path)
           new-cwd (if (.startsWith p "/") p (str old-cwd "/" p))]
       (emit dispatcher ctx-atom
             {:scaffold? true :type :jenkins/dir-enter :path new-cwd})
       (swap! ctx-atom assoc :cwd new-cwd)
       (try (.call body)
            (finally
              (emit dispatcher ctx-atom
                    {:scaffold? true :type :jenkins/dir-leave :path new-cwd})
              (swap! ctx-atom assoc :cwd old-cwd)))))))

(defn- make-parallel-closure
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [^java.util.Map branches]
     (emit dispatcher ctx-atom
           {:scaffold? true :type :jenkins/parallel-enter :branches (count branches)})
     (try
       (doseq [[branch-name branch] branches
               :when (instance? Closure branch)]
         (emit dispatcher ctx-atom
               {:scaffold? true :type :jenkins/parallel-branch :name (coerce-string branch-name)})
         (try (.call ^Closure branch)
              (catch Throwable t
                (log/warn t (str "parallel branch '" branch-name "' threw: "
                                 (.getMessage t)))
                (emit dispatcher ctx-atom
                      {:scaffold? true :type :jenkins/parallel-branch-failed
                       :name (coerce-string branch-name)
                       :message (.getMessage t)
                       :class (.getName (class t))}))))
       (finally
         (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/parallel-leave}))))))

;; A tolerant catch-all block-step closure: any unknown block step
;; (realtimeJUnit, infra.withArtifactCachingProxy, etc.) gets its body
;; executed and the call recorded. Used as the methodMissing fallback
;; for closure-tailed calls.
(defn- make-tolerant-block-closure
  [dispatcher ctx-atom step-name]
  (g/clojure-fn->groovy-closure
   (fn [& args]
     (let [last-arg (last args)]
       (if (instance? Closure last-arg)
         (do (emit dispatcher ctx-atom
                   {:scaffold? true :type :jenkins/unknown-enter
                    :name step-name
                    :args (vec (butlast args))})
             (try (.call ^Closure last-arg)
                  (finally
                    (emit dispatcher ctx-atom
                          {:scaffold? true :type :jenkins/unknown-leave :name step-name}))))
         (emit dispatcher ctx-atom
               {:scaffold? true :type :jenkins/unknown
                :name step-name
                :args (vec args)}))))))

;; ---------------------------------------------------------------------------
;; Helpers / DSL functions
;; ---------------------------------------------------------------------------

(defn- make-pwd
  "pwd() / pwd(tmp: true). Returns a workspace path string.

   Jenkins's pwd() returns the cwd or, with tmp:true, a writeable temp
   dir under the workspace. Anvil hands back a concrete tmp dir under
   the current cwd so downstream `dir(tmpDir)` calls produce a real
   filesystem path the inner `stash`/`readFile` can find."
  [ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn
     ([] (or (:cwd @ctx-atom) "/workspace"))
     ([opts]
      (let [m (as-map opts)
            base (or (:cwd @ctx-atom) "/workspace")]
        (if (= true (get m "tmp"))
          (let [tmp (str base "/.tmp")]
            (.mkdirs (java.io.File. tmp))
            tmp)
          base))))))

(defn- make-isUnix []
  ;; Single-host single-OS — anvil's dogfood is Linux. Always true.
  (g/clojure-fn->groovy-closure (fn [] true)))

(defn- make-readFile [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [path]
     (let [p (coerce-string path)
           f (if (.startsWith p "/")
               (java.io.File. p)
               (java.io.File. (or (:cwd @ctx-atom) ".") p))]
       (if (.exists f)
         (slurp f)
         "")))))

(defn- make-writeFile [_dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [opts]
     (let [m (as-map opts)
           p (get m "file")
           text (get m "text" "")
           f (if (.startsWith p "/")
               (java.io.File. p)
               (java.io.File. (or (:cwd @ctx-atom) ".") p))]
       (.mkdirs (.getParentFile f))
       (spit f text)
       nil))))

(defn- make-properties
  "properties([...]) — top-level Pipeline configurator. We just record
   the call; anvil doesn't enforce buildDiscarder etc."
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [_props]
     (emit dispatcher ctx-atom {:scaffold? true :type :jenkins/properties})
     nil)))

(defn- make-noop-fn
  "Returns a Groovy closure that ignores its args and returns nil.
   Used for kubernetesAgent(), nonresumable(), agent() retry conditions
   and other plugin helpers whose return values seed lists."
  []
  (g/clojure-fn->groovy-closure
   (fn [& _args] nil)))

;; ---------------------------------------------------------------------------
;; Jenkins-env globals (bare identifiers AND env.X both work)
;;
;; Real Jenkins exposes a documented set of env vars both as bare global
;; identifiers (`JENKINS_URL`, `BUILD_NUMBER`, …) and as `env.X` properties.
;; Out-of-the-wild Jenkinsfiles use the bare form constantly — e.g.
;; `if (JENKINS_URL == 'https://ci.jenkins.io/')` — which used to throw
;; `MissingPropertyException: JENKINS_URL` in scripted-eval. We synthesize
;; sensible defaults from anvil's build ctx so the property lookup succeeds
;; AND the values match what a job-page would surface.
;; ---------------------------------------------------------------------------

(defn jenkins-env-globals
  "Map of Jenkins-documented env-var globals derived from anvil ctx.
   Returned as String → String so both `JENKINS_URL` (bare-identifier
   binding lookup) and `env.JENKINS_URL` (Expando property) resolve.
   See https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables"
  [ctx]
  (let [job-name      (or (:job-name ctx) "anvil-job")
        build-number  (str (or (:build-number ctx) 1))
        workspace     (or (:workspace ctx) (or (:cwd ctx) "/workspace"))
        anvil-url     (or (:anvil-url ctx) "http://localhost:8765/")
        branch-name   (or (:branch-name ctx) "master")]
    {"JENKINS_URL"      anvil-url
     "JENKINS_HOME"     (or (System/getenv "ANVIL_DATA") "/var/lib/anvil")
     "HUDSON_URL"       anvil-url                     ; legacy alias
     "BUILD_NUMBER"     build-number
     "BUILD_ID"         build-number                  ; modern Jenkins: alias
     "BUILD_DISPLAY_NAME" (str "#" build-number)
     "BUILD_TAG"        (str "jenkins-" job-name "-" build-number)
     "BUILD_URL"        (str anvil-url "jenkins/job/" job-name "/" build-number "/")
     "JOB_NAME"         job-name
     "JOB_BASE_NAME"    job-name
     "JOB_URL"          (str anvil-url "jenkins/job/" job-name "/")
     "WORKSPACE"        workspace
     "WORKSPACE_TMP"    (str workspace "/.tmp")
     "BRANCH_NAME"      branch-name
     "CHANGE_ID"        ""
     "CHANGE_TARGET"    ""
     "CHANGE_BRANCH"    ""
     "EXECUTOR_NUMBER"  "0"
     "NODE_NAME"        "built-in"
     "NODE_LABELS"      ""
     "GIT_BRANCH"       branch-name
     "GIT_COMMIT"       ""
     "GIT_URL"          ""}))

;; ---------------------------------------------------------------------------
;; DSL globals — currentBuild, pullRequest, infra
;; ---------------------------------------------------------------------------

(defn- make-currentBuild []
  (doto (Expando.)
    (.setProperty "result" "SUCCESS")
    (.setProperty "displayName" "anvil-build")
    (.setProperty "number" 1)))

(defn- make-pullRequest []
  ;; pullRequest.labels.contains(x) — we model labels as an empty list.
  ;; That way the Jenkinsfile's `pullRequest.labels.contains('full-test')`
  ;; returns false and the conditional path skips Launchable subsetting.
  (doto (Expando.)
    (.setProperty "labels" [])))

(defn- make-infra
  "infra.* shared-library shim. The methods route to anvil's existing
   infra shim implementations from anvil.compat.jenkins.shared-libs.infra."
  [dispatcher ctx-atom]
  (let [obj (Expando.)
        sh (fn [cmd]
             (emit dispatcher ctx-atom
                   {:type :jenkins/sh :script cmd
                    :shim :infra/runMaven}))]
    (.setProperty obj "checkoutSCM"
                  (g/clojure-fn->groovy-closure
                   (fn []
                     ;; Tier-3 receipt: actually clone jenkinsci/jenkins
                     ;; into cwd if not already populated. Idempotent —
                     ;; later cells skip when .git already exists.
                     (let [cwd (or (:cwd @ctx-atom) "/workspace")
                           git-dir (java.io.File. cwd ".git")]
                       (when-not (.exists git-dir)
                         (emit dispatcher ctx-atom
                               {:type :jenkins/sh
                                :script (str "git clone --depth 1 --branch master "
                                             "https://github.com/jenkinsci/jenkins.git "
                                             cwd "-src && "
                                             "cp -a " cwd "-src/. " cwd "/")})))
                     (emit dispatcher ctx-atom
                           {:scaffold? true :type :jenkins/scm-assume-checked-out})
                     nil)))
    (.setProperty obj "runMaven"
                  (g/clojure-fn->groovy-closure
                   (fn
                     ([opts] (sh (str "mvn " (clojure.string/join " " opts))))
                     ([opts _jdk] (sh (str "mvn " (clojure.string/join " " opts))))
                     ([opts _jdk _extra]
                      (sh (str "mvn " (clojure.string/join " " opts)))))))
    (.setProperty obj "runWithMaven"
                  (g/clojure-fn->groovy-closure
                   (fn [& args]
                     (sh (str "mvn " (clojure.string/join " " (flatten args)))))))
    (.setProperty obj "withArtifactCachingProxy"
                  (g/clojure-fn->groovy-closure
                   (fn [^Closure body]
                     ;; Just pass through — no proxy on a single host.
                     (.call body))))
    (.setProperty obj "maybePublishIncrementals"
                  (g/clojure-fn->groovy-closure
                   (fn []
                     (emit dispatcher ctx-atom
                           {:scaffold? true :type :jenkins/infra-maybePublishIncrementals})
                     nil)))
    obj))

;; ---------------------------------------------------------------------------
;; The full binding set
;; ---------------------------------------------------------------------------

(defn- make-shared-lib-stub
  "Stub for an unresolved shared-library call (buildPlugin, mavenBuild,
   etc). Records the call + args as a scaffolding event and returns nil
   so the surrounding script keeps running. Marks the build with a
   :shared-lib-unresolved warning so SUCCESS doesn't pretend we built
   something."
  [dispatcher ctx-atom step-name]
  (g/clojure-fn->groovy-closure
   (fn [& args]
     (emit dispatcher ctx-atom
           {:scaffold? true :type :jenkins/shared-lib-unresolved
            :name step-name
            :argc (count args)
            :note (str "shared library not resolved on anvil — "
                       step-name "(...) recorded but NOT executed")})
     nil)))

(defn make-scripted-bindings
  "Full DSL binding map for whole-Jenkinsfile execution. Includes the
   base set from runtime.clj plus block-step closures + DSL globals
   needed for the unmodified ci.jenkins.io scripted Pipeline."
  [dispatcher ctx-atom env-vars]
  (let [env-globals (jenkins-env-globals @ctx-atom)
        ;; Merge env globals INTO env-vars so the `env` Expando carries
        ;; them too — `env.JENKINS_URL` and bare `JENKINS_URL` both work.
        ;; Caller-provided env-vars win on conflict (test overrides etc.).
        env-vars+ (merge env-globals env-vars)
        base (rt/make-dsl-bindings dispatcher ctx-atom env-vars+)]
    (merge
     base
     ;; Jenkins env globals — bare identifiers (JENKINS_URL, BUILD_NUMBER, …)
     env-globals
     {;; Block-step closures
      "__node"           (make-node-closure dispatcher ctx-atom)
      "__stage"          (make-stage-closure dispatcher ctx-atom)
      "__dir"            (make-dir-closure dispatcher ctx-atom)
      ;; Tolerant overrides for stash/unstash — the unmodified
      ;; jenkinsci/jenkins Jenkinsfile stashes per-cell launchable
      ;; session files in one stage then unstashes in parallel
      ;; branches. anvil's stash store needs :workspace + :stash-root
      ;; in ctx, which the scripted-eval path doesn't currently
      ;; populate. A missing unstash should NOT abort the branch.
      "__stash"
      (g/clojure-fn->groovy-closure
       (fn [opts]
         (emit dispatcher ctx-atom
               {:scaffold? true :type :jenkins/stash :args (as-map opts)})
         nil))
      "__unstash"
      (g/clojure-fn->groovy-closure
       (fn [name-or-opts]
         (let [n (if (instance? java.util.Map name-or-opts)
                   (get (as-map name-or-opts) "name")
                   (coerce-string name-or-opts))]
           (emit dispatcher ctx-atom
                 {:scaffold? true :type :jenkins/unstash
                  :name n :missing? true})
           nil)))
      ;; checkout — accept any arg + no-op (the real workspace already
      ;; has the source from anvil's job clone).
      "__checkout"
      (g/clojure-fn->groovy-closure
       (fn [_arg]
         (emit dispatcher ctx-atom
               {:scaffold? true :type :jenkins/scm-assume-checked-out})
         nil))
      "__retry"          (make-retry-closure dispatcher ctx-atom)
      "__timeout"        (make-timeout-closure dispatcher ctx-atom)
      "__withCredentials" (make-withCredentials-closure dispatcher ctx-atom)
      "__withChecks"     (make-withChecks-closure dispatcher ctx-atom)
      "__parallel"       (make-parallel-closure dispatcher ctx-atom)
      ;; Tolerant fallbacks — used for unknown plugin block steps
      "__realtimeJUnit"  (make-tolerant-block-closure dispatcher ctx-atom "realtimeJUnit")
      "__lock"           (make-tolerant-block-closure dispatcher ctx-atom "lock")
      ;; Plain functions
      "__properties"     (make-properties dispatcher ctx-atom)
      "__pwd"            (make-pwd ctx-atom)
      "__isUnix"         (make-isUnix)
      "__readFile"       (make-readFile dispatcher ctx-atom)
      "__writeFile"      (make-writeFile dispatcher ctx-atom)
      ;; Plugin helper-functions (return nil — used to seed lists/conditions)
      "__kubernetesAgent" (make-noop-fn)
      "__nonresumable"    (make-noop-fn)
      "__agent"           (make-noop-fn)
      "__buildDiscarder"  (make-noop-fn)
      "__logRotator"      (make-noop-fn)
      "__disableConcurrentBuilds" (make-noop-fn)
      "__string"          (make-noop-fn)
      "__usernamePassword" (make-noop-fn)
      "__file"            (make-noop-fn)
      "__discoverGitReferenceBuild" (make-noop-fn)
      "__recordCoverage"  (make-noop-fn)
      "__recordIssues"    (make-noop-fn)
      "__java"            (make-noop-fn)
      "__javaDoc"         (make-noop-fn)
      "__spotBugs"        (make-noop-fn)
      "__checkStyle"      (make-noop-fn)
      "__esLint"          (make-noop-fn)
      "__styleLint"       (make-noop-fn)
      "__launchable"      (make-noop-fn)
      ;; Shared-library entry-points (jenkins-infra/pipeline-library and
      ;; common org templates). These are the most-frequent first call in
      ;; real-world Jenkinsfiles — without a stub the methodMissing path
      ;; throws and the build looks busted for a reason the user can't
      ;; act on. Stubs RECORD the call so reports show "the Jenkinsfile
      ;; invoked buildPlugin(...) but the library is not resolved".
      "__buildPlugin"     (make-shared-lib-stub dispatcher ctx-atom "buildPlugin")
      "__buildPluginWithGradle" (make-shared-lib-stub dispatcher ctx-atom "buildPluginWithGradle")
      "__mavenBuild"      (make-shared-lib-stub dispatcher ctx-atom "mavenBuild")
      "__gradleBuild"     (make-shared-lib-stub dispatcher ctx-atom "gradleBuild")
      "__nodejs"          (make-shared-lib-stub dispatcher ctx-atom "nodejs")
      "__buildPython"     (make-shared-lib-stub dispatcher ctx-atom "buildPython")
      "__buildDockerImage" (make-shared-lib-stub dispatcher ctx-atom "buildDockerImage")
      ;; DSL globals (accessed without leading __ — properties on the
      ;; binding, not methodMissing routes)
      "currentBuild"     (make-currentBuild)
      "pullRequest"      (make-pullRequest)
      "infra"            (make-infra dispatcher ctx-atom)
      ;; checkout scm — `scm` is a placeholder global; checkout is a
      ;; method recorded as a leaf via base bindings.
      "scm"              "anvil-scm-stub"})))

;; ---------------------------------------------------------------------------
;; Public entry: run a whole scripted Jenkinsfile
;; ---------------------------------------------------------------------------

(defn run-scripted-file
  "Execute the WHOLE scripted Jenkinsfile source through Groovy with
   anvil's expanded Pipeline DSL bindings. Returns the final dispatch
   ctx (mutated through the run).

   Differs from runtime.clj/run-script-block which runs only the body
   of a `script { … }` block — here the script body is the entire file."
  [^String full-source dispatcher ctx-atom]
  (let [env-vars (get @ctx-atom :env {})
        bindings (make-scripted-bindings dispatcher ctx-atom env-vars)]
    (try
      (rt/eval-with-dsl-base full-source bindings)
      (catch Throwable t
        (log/warn t "anvil scripted-runtime: top-level exception")
        (emit dispatcher ctx-atom
              {:scaffold? true :type :jenkins/scripted-exception
               :message (.getMessage t)
               :class (.getName (class t))})))
    @ctx-atom))
