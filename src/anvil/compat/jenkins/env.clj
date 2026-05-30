(ns anvil.compat.jenkins.env
  "Jenkins environment-variable population.

   When a pipeline runs in anvil with `:source :jenkins`, the following
   env vars are populated before any step executes. The values come from
   the build context (build number, workspace path, SCM info) supplied
   by anvil's executor.

   This namespace is intentionally pure — `build-env` is a function of
   the build-context map and returns a string → string map. The runtime
   pours these into the Groovy `env` Expando.

   Divergences from real Jenkins env-var semantics are listed in
   `docs/jenkins-compat/env.md` (to be written in TX9)."
  (:require [clojure.string :as str]))

(def jenkins-env-keys
  "The Jenkins env-var contract we populate. See
   https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables
   for the canonical list."
  #{:BUILD_NUMBER :BUILD_ID :BUILD_URL :BUILD_TAG :BUILD_DISPLAY_NAME
    :JOB_NAME :JOB_BASE_NAME :JOB_URL
    :WORKSPACE :JENKINS_HOME :NODE_NAME :EXECUTOR_NUMBER
    :BRANCH_NAME :CHANGE_ID :CHANGE_TARGET :CHANGE_URL
    :CHANGE_BRANCH :CHANGE_AUTHOR :CHANGE_AUTHOR_DISPLAY_NAME})

(defn- safe-str [x]
  (if (nil? x) "" (str x)))

(defn build-env
  "Return a String → String map of Jenkins env vars from a build context.

   The context map is the same shape anvil's executor produces. Missing
   fields render as empty strings (matching Jenkins's behavior for
   undefined-but-expected vars in non-SCM-driven pipelines)."
  [{:keys [build-number build-id build-url build-tag display-name
           job-name job-base-name job-url
           workspace jenkins-home node-name executor-number
           branch-name change-id change-target change-url
           change-branch change-author change-author-display-name
           extra-env]
    :or {workspace      "/workspace"
         jenkins-home   "/var/jenkins_home"
         node-name      "anvil-local"
         executor-number 0}}]
  (merge
   {"BUILD_NUMBER"                (safe-str build-number)
    "BUILD_ID"                    (safe-str (or build-id build-number))
    "BUILD_URL"                   (safe-str build-url)
    "BUILD_TAG"                   (safe-str build-tag)
    "BUILD_DISPLAY_NAME"          (safe-str (or display-name
                                                (when build-number
                                                  (str "#" build-number))))
    "JOB_NAME"                    (safe-str job-name)
    "JOB_BASE_NAME"               (safe-str (or job-base-name job-name))
    "JOB_URL"                     (safe-str job-url)
    "WORKSPACE"                   (safe-str workspace)
    "JENKINS_HOME"                (safe-str jenkins-home)
    "NODE_NAME"                   (safe-str node-name)
    "EXECUTOR_NUMBER"             (safe-str executor-number)
    "BRANCH_NAME"                 (safe-str branch-name)
    "CHANGE_ID"                   (safe-str change-id)
    "CHANGE_TARGET"               (safe-str change-target)
    "CHANGE_URL"                  (safe-str change-url)
    "CHANGE_BRANCH"               (safe-str change-branch)
    "CHANGE_AUTHOR"               (safe-str change-author)
    "CHANGE_AUTHOR_DISPLAY_NAME"  (safe-str change-author-display-name)}
   ;; Pipeline-declared environment {} block contents override / extend.
   (or extra-env {})))

(defn divergences
  "Return a list of documented divergences from Jenkins env-var semantics.
   The migration UX (TX7) surfaces these to users."
  []
  [{:jenkins-var "HUDSON_HOME"
    :status      :not-exposed
    :reason      "Hudson-era alias; not in scope."}
   {:jenkins-var "BUILD_USER"
    :status      :not-exposed
    :reason      "Plugin-provided (Build User Vars Plugin); not in core anvil."}
   {:jenkins-var "GIT_COMMIT" "GIT_BRANCH" "GIT_URL"
    :status      :emitted-when-scm-present
    :reason      "Populated only when the pipeline checkout actually resolves a Git SCM."}])
