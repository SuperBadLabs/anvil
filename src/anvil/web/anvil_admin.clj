(ns anvil.web.anvil-admin
  "Anvil-native admin REST endpoints.

   Mounted at /anvil/admin/* — DISTINCT from /jenkins/* (which is the
   Jenkins-compatibility shim). The Jenkins shim intentionally returns
   501 for administrative endpoints like /createItem because Jenkins's
   XML-config-as-source-of-truth model conflicts with anvil's
   Jenkinsfile-and-Chengisfile-in-your-repo posture.

   This namespace is anvil's OWN admin surface. It's where the CI
   integration fixture registers jobs, and where future anvil-native
   tooling will manage the registered-job catalog.

   v1 endpoints:
     POST   /anvil/admin/jobs              register or replace a job
     DELETE /anvil/admin/jobs/:name        unregister a job
     GET    /anvil/admin/jobs              list jobs (anvil-native shape)

   No auth at v1 — anvil ships single-user, behind a reverse proxy by
   default. Auth comes when anvil grows team features (Chengis territory)."
  (:require [clojure.data.json :as json]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn- read-json-body
  "Parse a JSON request body into a Clojure map with string keys.
   Returns nil on failure."
  [req]
  (try
    (let [body (:body req)
          s (cond
              (string? body) body
              (instance? java.io.InputStream body) (slurp body)
              :else (str body))]
      (when (seq s) (json/read-str s)))
    (catch Exception _ nil)))

(defn- json-response
  ([body] (json-response body 200))
  ([body status]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn- coerce-positive-int
  "Coerce a JSON-supplied value to a positive Long. Accepts numbers and
   numeric strings. Returns nil if absent, :invalid if present but
   malformed. The caller defaults nil → 1 so a missing field is fine;
   :invalid bubbles to a 400. (Codex P2, PR #164: 0 cap stuck builds
   in the queue forever; string cap threw in the worker after polling.)"
  [v]
  (cond
    (nil? v) nil
    (and (integer? v) (pos? v)) (long v)
    (and (string? v) (re-matches #"\d+" v))
    (let [n (try (Long/parseLong v) (catch Exception _ nil))]
      (if (and n (pos? n)) n :invalid))
    :else :invalid))

(defn- coerce-scm
  "Pull `\"scm\": {\"type\":\"git\",\"url\":...,\"branch\":...}` out of the
   request body. Returns nil if absent (job has no SCM — current behavior),
   the parsed map if valid, or `:invalid` with a reason string for the
   caller to surface as a 400."
  [scm-body]
  (cond
    (nil? scm-body) nil
    (not (map? scm-body)) [:invalid "scm must be an object"]
    (not (string? (get scm-body "url")))
    [:invalid "scm.url (string) is required"]
    :else
    {:type   (keyword (or (get scm-body "type") "git"))
     :url    (get scm-body "url")
     :branch (or (get scm-body "branch") "main")}))

(defn register-job [req]
  (let [body (read-json-body req)
        nm (get body "name")
        src (get body "jenkinsfile_source")
        buildable? (if (contains? body "buildable") (get body "buildable") true)
        raw-cap (get body "max_concurrent_builds")
        coerced (coerce-positive-int raw-cap)
        max-conc (cond
                   (nil? coerced)        1            ; field absent
                   (= :invalid coerced)  :invalid     ; bubbles to cond
                   :else                 coerced)
        scm (coerce-scm (get body "scm"))]
    (cond
      (not (string? nm))
      (json-response {"error" "name (string) is required"} 400)

      (not (string? src))
      (json-response {"error" "jenkinsfile_source (string) is required"} 400)

      (= :invalid max-conc)
      (json-response {"error" (str "max_concurrent_builds must be a positive integer "
                                   "(got " (pr-str raw-cap) ")")} 400)

      (and (vector? scm) (= :invalid (first scm)))
      (json-response {"error" (second scm)} 400)

      :else
      (do (jobs/register-job!
           (cond-> {:name nm
                    :jenkinsfile-source src
                    :buildable? buildable?
                    :max-concurrent-builds max-conc}
             scm (assoc :scm scm)))
          (json-response {"status" "ok"
                          "name" nm
                          "url" (str "/jobs/" nm)
                          "jenkins_url" (str "/jenkins/job/" nm "/")}
                         201)))))

(defn delete-job [req]
  (let [nm (get-in req [:path-params :name])]
    (if (jobs/find-job nm)
      (do
        ;; Use the unified delete path so persistence is wiped too — otherwise
        ;; merge-disk-into-atom! rehydrates the deleted job on the next
        ;; find-job/list-jobs call (Codex P2, PR #164).
        (jobs/delete-job! nm)
        (json-response {"status" "deleted" "name" nm} 200))
      (json-response {"error" (str "no such job: " nm)} 404))))

(defn list-jobs [_req]
  (let [js (jobs/list-jobs)]
    (json-response
     {"count" (count js)
      "jobs" (mapv (fn [j]
                     {"name" (:name j)
                      "color" (some-> j :color name)
                      "buildable" (boolean (:buildable? j))
                      "last_build" (:last-build j)
                      "last_successful_build" (:last-successful-build j)
                      "last_failed_build" (:last-failed-build j)
                      "url" (str "/jobs/" (:name j))})
                   js)})))
