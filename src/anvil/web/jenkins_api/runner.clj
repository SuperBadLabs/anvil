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
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.env :as jenkins-env]
            [anvil.web.jenkins-api.jobs :as jobs]))

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
          ;; Jenkins env vars populated for the build.
          env-vars (jenkins-env/build-env
                    {:build-number number
                     :build-id (str number)
                     :job-name job-name
                     :workspace workspace-path
                     :extra-env (or parameters {})})]
      (try
        (let [source (:jenkinsfile-source job)
              base-ir (t/parse source (str job-name "/Jenkinsfile"))
              ;; TX11B: expand matrix combinations (scripted-Pipeline files
              ;; with .combinations { … } blocks need their templated
              ;; stages materialized before dispatch).
              pipeline-ir (mx/expand-matrices base-ir source)
              flat-stages (flatten-pipeline pipeline-ir)
              flat {:stages (vec flat-stages)}
              dispatcher (ad/make {:execute? execute?})
              stash-root (.getAbsolutePath
                          (io/file (.getParentFile workspace) "stashes"))
              ctx {:dispatcher dispatcher
                   :env env-vars
                   :cwd workspace-path
                   :workspace workspace-path
                   :stash-root stash-root   ;; TX11C
                   :log-file log-file
                   :build-number number
                   :job-name job-name}
              result (d/run-pipeline flat dispatcher ctx)
              effects @(:effects dispatcher)
              build-result (case (:status result)
                             :ok :success
                             :failed :failure
                             :success)]
          (jobs/record-build-end! job-name number
                                  {:result build-result
                                   :effects effects
                                   :log-path log-path})
          {:build-number number
           :result build-result
           :effect-count (count effects)
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
           :log-path log-path})))))
