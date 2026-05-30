(ns anvil.web.jenkins-api.handlers
  "Ring handlers for `/jenkins/*` — the Jenkins REST API shim.

   Mounted by anvil.web.routes via a reitit subtree. Each handler
   delegates JSON shape to anvil.web.jenkins-api.views and persistence
   to anvil.web.jenkins-api.jobs.

   Coverage (TX6 v1):
     READ
       GET  /api/json
       GET  /crumbIssuer/api/json
       GET  /job/<name>/api/json
       GET  /job/<name>/<n>/api/json
       GET  /job/<name>/<n>/consoleText
       GET  /job/<name>/<n>/logText/progressiveText?start=N
       GET  /queue/api/json
     WRITE
       POST /job/<name>/build
       POST /job/<name>/buildWithParameters
     501 STUBS
       any  /createItem
       any  /config.xml
       any  /script

   For real Jenkins-CLI compatibility, this whole table mounts at
   `/jenkins/` so URLs match `http://anvil:8080/jenkins/...` exactly."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]
            [anvil.web.jenkins-api.views :as views]))

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn- json-response
  ([body] (json-response body 200))
  ([body status]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn- text-response
  ([body] (text-response body 200 {}))
  ([body status] (text-response body status {}))
  ([body status extra-headers]
   {:status status
    :headers (merge {"Content-Type" "text/plain; charset=utf-8"} extra-headers)
    :body body}))

(defn- not-found-json [what]
  (json-response (views/not-found what) 404))

;; ---------------------------------------------------------------------------
;; Read endpoints
;; ---------------------------------------------------------------------------

(defn root [req]
  (json-response (views/root req)))

(defn crumb [req]
  (json-response (views/crumb req)))

(defn job-api [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (if job
      (json-response (views/job-summary req job))
      (not-found-json (str "job " job-name)))))

(defn build-api [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))]
    (if b
      (json-response (views/build-summary req job-name b))
      (not-found-json (str "build " job-name "#" n)))))

(defn console-text [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))]
    (if b
      (text-response (jobs/console-log-for b))
      (text-response (str "no such build " job-name "#" n) 404))))

(defn log-progressive [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        start (try (Integer/parseInt (or (get-in req [:query-params "start"]) "0"))
                   (catch Exception _ 0))
        b (when n (jobs/find-build job-name n))]
    (if-not b
      (text-response (str "no such build " job-name "#" n) 404)
      (let [log (jobs/console-log-for b)
            total (count log)
            slice (if (< start total) (subs log start) "")
            new-size (+ start (count slice))
            more? (boolean (:building? b))]
        (text-response slice 200
                       {"X-Text-Size" (str new-size)
                        "X-More-Data" (if more? "true" "false")})))))

(defn queue-api [req]
  (json-response (views/queue req)))

;; ---------------------------------------------------------------------------
;; Write endpoints (build triggering)
;; ---------------------------------------------------------------------------

(defn- form-or-query-params
  "Pluck parameters from form body or query string for buildWithParameters."
  [req]
  (or (:form-params req)
      (:query-params req)
      {}))

(defn trigger-build [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (cond
      (not job)
      (not-found-json (str "job " job-name))

      (not (:buildable? job))
      (json-response {"_class" "hudson.model.BuildFailure"
                      "message" (str "job " job-name " is not buildable")}
                     409)

      :else
      ;; TX9 phase 5: enqueue into the async build queue instead of
      ;; spawning a future inline. The worker pool picks up and runs.
      ;; When the queue isn't started (tests that don't init it), the
      ;; item sits queued forever — tests opt in via queue/start-workers!.
      (let [item (queue/enqueue! job-name {})]
        {:status 201
         :headers {"Location" (str "/jenkins/queue/item/" (:queue-id item) "/")
                   "Content-Type" "application/json"}
         :body (json/write-str
                {"_class" "hudson.model.Queue$Item"
                 "id" (:queue-id item)
                 "url" (str "/jenkins/queue/item/" (:queue-id item) "/")
                 "task" {"name" job-name}})}))))

(defn trigger-build-with-parameters [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)
        params (form-or-query-params req)]
    (cond
      (not job)
      (not-found-json (str "job " job-name))

      (not (:buildable? job))
      ;; Mirror the buildable check from `trigger-build` so a disabled
      ;; job can't be silently revived by sending parameters
      ;; (Codex P2, PR #164).
      (json-response {"_class" "hudson.model.BuildFailure"
                      "message" (str "job " job-name " is not buildable")}
                     409)

      :else
      (let [item (queue/enqueue! job-name {:parameters params})]
        {:status 201
         :headers {"Location" (str "/jenkins/queue/item/" (:queue-id item) "/")
                   "Content-Type" "application/json"}
         :body (json/write-str
                {"_class" "hudson.model.Queue$Item"
                 "id" (:queue-id item)
                 "url" (str "/jenkins/queue/item/" (:queue-id item) "/")
                 "task" {"name" job-name}
                 "parameters" params})}))))

(defn queue-item-by-id
  "GET /jenkins/queue/item/:id/ — return the queued item across its
   full lifecycle (waiting / dispatched / completed / cancelled).
   Closes the loop with the Location header returned by trigger-build
   / trigger-build-with-parameters; items are retained in the queue
   state through completion so Jenkins-compat clients can follow the
   Location to discover the executable build (Codex P2, PR #164)."
  [req]
  (let [id-str (get-in req [:path-params :id])
        id (try (Long/parseLong (str id-str)) (catch Exception _ nil))
        item (when id (queue/find-by-id id))]
    (cond
      (nil? id)
      (not-found-json (str "invalid queue id " (pr-str id-str)))

      (nil? item)
      (not-found-json (str "no queue item with id " id))

      :else
      (let [base {"id" (:queue-id item)
                  "url" (str "/jenkins/queue/item/" (:queue-id item) "/")
                  "task" {"name" (:job-name item)}
                  "params" (or (:parameters item) {})}]
        (case (:phase item)
          :cancelled
          (json-response
           (merge base
                  {"_class" "hudson.model.Queue$LeftItem"
                   "cancelled" true
                   "why" "Cancelled before pickup"}))

          :dispatched
          (json-response
           (merge base
                  {"_class" "hudson.model.Queue$LeftItem"
                   "cancelled" false
                   "executable"
                   (when-let [bn (:build-number item)]
                     {"_class" "hudson.model.FreeStyleBuild"
                      "number" bn
                      "url" (str "/jenkins/job/" (:job-name item) "/" bn "/")})}))

          :completed
          (json-response
           (merge base
                  {"_class" "hudson.model.Queue$LeftItem"
                   "cancelled" false
                   "executable"
                   {"_class" "hudson.model.FreeStyleBuild"
                    "number" (:build-number item)
                    "url" (str "/jenkins/job/" (:job-name item) "/"
                               (:build-number item) "/")
                    "result" (some-> (:build-result item) name str/upper-case)}}))

          :failed
          (json-response
           (merge base
                  {"_class" "hudson.model.Queue$LeftItem"
                   "cancelled" false
                   "why" (or (:error item) "Worker threw")}))

          ;; default — still in queue
          (json-response
           (merge base
                  {"_class" "hudson.model.Queue$WaitingItem"
                   "why" "Waiting for worker availability"})))))))

;; ---------------------------------------------------------------------------
;; 501 stubs for the administrative API surface anvil doesn't implement
;; ---------------------------------------------------------------------------

(defn create-item-stub [_req]
  (json-response
   (views/not-implemented-stub
    "POST /createItem"
    "deferred"
    "Use anvil's CLI: `anvil import jenkinsfile <path>` to import an
     existing Jenkinsfile, or write a Chengisfile.edn directly. Anvil
     does not adopt Jenkins's XML-as-source-of-truth model.")
   501))

(defn config-xml-stub [_req]
  (json-response
   (views/not-implemented-stub
    "GET/POST /config.xml"
    "deferred"
    "Anvil's source of truth is the Jenkinsfile / Chengisfile in your
     repo + the registered-job metadata in anvil's admin UI. There is
     no config.xml.")
   501))

(defn script-console-stub [_req]
  (json-response
   (views/not-implemented-stub
    "POST /script"
    "never"
    "Anvil deliberately does not ship a Groovy script console — its
     security and operability problems are well-documented in the
     Jenkins community. Use Chengisfile + anvil plugin SDK to
     extend behavior.")
   501))
