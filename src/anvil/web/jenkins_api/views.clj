(ns anvil.web.jenkins-api.views
  "JSON shape converters — anvil data → Jenkins API JSON.

   The shapes here mirror what real Jenkins returns for the same
   endpoints. jenkins-cli.jar and the GitHub-Jenkins plugin both consume
   them; the `_class` keys are particularly load-bearing because
   Jenkins's polymorphic JSON dispatch keys off them."
  (:require [clojure.string :as str]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.version :as v]))

(defn- abs-url
  "Build an absolute URL prefix for inclusion in Jenkins-shape responses.
   Real Jenkins returns full http://… URLs even for hyperlinks within
   the same instance, so jenkins-cli's URL-following works."
  [req path]
  (str (name (or (:scheme req) :http))
       "://"
       (or (get-in req [:headers "host"]) "localhost:8080")
       "/jenkins" path))

;; ---------------------------------------------------------------------------
;; Root: GET /jenkins/api/json
;; ---------------------------------------------------------------------------

(defn root [req]
  {"_class" "hudson.model.Hudson"
   "assignedLabels" [{"name" "anvil-local"}]
   "mode" "NORMAL"
   "nodeDescription" "anvil (drop-in Jenkins replacement)"
   "nodeName" ""
   "numExecutors" 2
   "description" (str (v/version-string))
   "jobs" (mapv (fn [job]
                  {"_class" "hudson.model.FreeStyleProject"
                   "name"   (:name job)
                   "url"    (abs-url req (str "/job/" (:name job) "/"))
                   "color"  (some-> job :color name)})
                (jobs/list-jobs))
   "url" (abs-url req "/")
   "useCrumbs" false
   "useSecurity" false
   "views" [{"_class" "hudson.model.AllView"
             "name" "all"
             "url" (abs-url req "/")}]})

;; ---------------------------------------------------------------------------
;; Crumb issuer
;; ---------------------------------------------------------------------------

(defn crumb [_req]
  ;; Synthetic crumb so jenkins-cli.jar's automatic CSRF flow finds a
  ;; field to populate. Anvil doesn't actually enforce CSRF here.
  {"_class" "hudson.security.csrf.DefaultCrumbIssuer"
   "crumb" "anvil-no-csrf-enforcement"
   "crumbRequestField" "Jenkins-Crumb"})

;; ---------------------------------------------------------------------------
;; Job
;; ---------------------------------------------------------------------------

(defn job-summary
  "GET /jenkins/job/<name>/api/json"
  [req job]
  (let [name (:name job)
        builds (jobs/list-builds-for-job name)]
    {"_class" "hudson.model.FreeStyleProject"
     "name"   name
     "url"    (abs-url req (str "/job/" name "/"))
     "color"  (some-> job :color clojure.core/name)
     "buildable" (boolean (:buildable? job))
     "concurrentBuild" false
     "description" ""
     "displayName" name
     "fullName" name
     "fullDisplayName" name
     "nextBuildNumber" (inc (or (:last-build job) 0))
     "builds"
     (mapv (fn [b]
             {"_class" "hudson.model.FreeStyleBuild"
              "number" (:number b)
              "url" (abs-url req (str "/job/" name "/" (:number b) "/"))})
           builds)
     "lastBuild"           (when (:last-build job)
                             {"_class" "hudson.model.FreeStyleBuild"
                              "number" (:last-build job)
                              "url" (abs-url req (str "/job/" name "/" (:last-build job) "/"))})
     "lastSuccessfulBuild" (when (:last-successful-build job)
                             {"_class" "hudson.model.FreeStyleBuild"
                              "number" (:last-successful-build job)
                              "url" (abs-url req (str "/job/" name "/" (:last-successful-build job) "/"))})
     "lastFailedBuild"     (when (:last-failed-build job)
                             {"_class" "hudson.model.FreeStyleBuild"
                              "number" (:last-failed-build job)
                              "url" (abs-url req (str "/job/" name "/" (:last-failed-build job) "/"))})}))

;; ---------------------------------------------------------------------------
;; Build
;; ---------------------------------------------------------------------------

(defn- result->jenkins
  "anvil result keyword → Jenkins build result string. AN4-1 added two
   new honest result classes; Jenkins doesn't natively have :neutral or
   :unsupported, so we map them to NOT_BUILT — the Jenkins-canonical
   way to say 'this build did not produce its expected output'. The
   classifier rule + explain still live on the build record for the
   anvil-native UI to render verbatim."
  [r]
  (case r
    :success     "SUCCESS"
    :failure     "FAILURE"
    :aborted     "ABORTED"
    :unstable    "UNSTABLE"
    :neutral     "NOT_BUILT"
    :unsupported "NOT_BUILT"
    :running     nil
    nil          nil))

(defn build-summary
  "GET /jenkins/job/<name>/<n>/api/json"
  [req job-name b]
  {"_class" "hudson.model.FreeStyleBuild"
   "id"        (str (:number b))
   "number"    (:number b)
   "url"       (abs-url req (str "/job/" job-name "/" (:number b) "/"))
   "result"    (result->jenkins (:result b))
   "building"  (boolean (:building? b))
   "duration"  (or (:duration-ms b) 0)
   "estimatedDuration" (max 0 (or (:duration-ms b) 0))
   "timestamp" (if-let [t (:started-at b)] (.toEpochMilli t) 0)
   "displayName" (str "#" (:number b))
   "fullDisplayName" (str job-name " #" (:number b))
   "keepLog"   false})

;; ---------------------------------------------------------------------------
;; Queue
;; ---------------------------------------------------------------------------

(defn queue [_req]
  (let [items (queue/queue-snapshot)]
    {"_class" "hudson.model.Queue"
     "discoverableItems" []
     "items"
     (mapv (fn [it]
             {"_class" "hudson.model.Queue$WaitingItem"
              "id" (:queue-id it)
              "task" {"name" (:job-name it)}
              "inQueueSince" (some-> (:enqueued-at it) .toEpochMilli)
              "cancelled" (boolean (:cancelled? it))
              "params" (pr-str (:parameters it))
              "stuck" false})
           items)}))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn not-found [resource]
  {"_class" "hudson.model.FailureMessage"
   "message" (str resource " not found")})

(defn not-implemented-stub
  "Used by the 501 endpoints. Returns Chengis-native equivalents the
   user can point at instead."
  [endpoint tx-tranche native-path]
  {"_class" "hudson.model.NotImplemented"
   "endpoint" endpoint
   "anvil-message"
   (str "Anvil does not implement " endpoint " — Jenkins's "
        "configuration-as-XML model conflicts with anvil's "
        "Jenkinsfile + Chengisfile-as-source-of-truth posture.")
   "anvil-replacement-path" native-path
   "tranche" tx-tranche})
