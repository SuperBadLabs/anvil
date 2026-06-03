(ns anvil.web.anvil-admin-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]))

(defn- mk-req
  ([method uri] (mk-req method uri nil))
  ([method uri body]
   {:request-method method
    :uri uri
    :scheme :http
    :headers {"host" "anvil.test" "content-type" "application/json"}
    :body (when body (json/write-str body))
    :query-params {} :form-params {}}))

(use-fixtures :each (fn [f] (jobs/clear!) (queue/clear!) (f) (jobs/clear!)))

(deftest register-job-via-admin-test
  (testing "POST /anvil/admin/jobs registers a job"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/anvil/admin/jobs"
                                {"name" "demo"
                                 "jenkinsfile_source" "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }"}))
          body (json/read-str (:body resp))]
      (is (= 201 (:status resp)))
      (is (= "ok" (get body "status")))
      (is (= "demo" (get body "name")))
      (is (str/includes? (get body "jenkins_url") "demo"))
      (is (some? (jobs/find-job "demo"))))))

(deftest register-job-with-scm-test
  (testing "POST /anvil/admin/jobs accepts scm block + threads to the in-memory job"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/anvil/admin/jobs"
                                {"name" "with-scm"
                                 "jenkinsfile_source" "pipeline { agent any; stages { stage('S') { steps { sh 'true' } } } }"
                                 "scm" {"type" "git"
                                        "url" "https://github.com/example/repo.git"
                                        "branch" "main"}}))]
      (is (= 201 (:status resp)))
      (let [j (jobs/find-job "with-scm")]
        (is (= :git (-> j :scm :type)))
        (is (= "https://github.com/example/repo.git" (-> j :scm :url)))
        (is (= "main" (-> j :scm :branch)))))))

(deftest register-job-with-malformed-scm-test
  (testing "scm without url → 400"
    (let [resp ((routes/make-handler)
                (mk-req :post "/anvil/admin/jobs"
                        {"name" "bad"
                         "jenkinsfile_source" "pipeline { }"
                         "scm" {"type" "git" "branch" "main"}}))]
      (is (= 400 (:status resp))))))

(deftest register-job-validation-test
  (testing "missing name → 400"
    (let [resp ((routes/make-handler)
                (mk-req :post "/anvil/admin/jobs"
                        {"jenkinsfile_source" "pipeline { }"}))]
      (is (= 400 (:status resp)))))
  (testing "missing jenkinsfile_source → 400"
    (let [resp ((routes/make-handler)
                (mk-req :post "/anvil/admin/jobs" {"name" "x"}))]
      (is (= 400 (:status resp))))))

(deftest list-jobs-via-admin-test
  (testing "GET /anvil/admin/jobs returns anvil-native shape (NOT Jenkins shape)"
    (jobs/register-job! {:name "alpha" :jenkinsfile-source "pipeline { }"})
    (jobs/register-job! {:name "beta"  :jenkinsfile-source "pipeline { }"})
    (let [resp ((routes/make-handler) (mk-req :get "/anvil/admin/jobs"))
          body (json/read-str (:body resp))]
      (is (= 200 (:status resp)))
      (is (= 2 (get body "count")))
      (is (= ["alpha" "beta"] (mapv #(get % "name") (get body "jobs"))))
      ;; Confirm it's anvil-native, not Jenkins-shape (no "_class" key).
      (is (not (contains? (first (get body "jobs")) "_class"))))))

(deftest delete-job-via-admin-test
  (testing "DELETE /anvil/admin/jobs/:name removes the job"
    (jobs/register-job! {:name "ephemeral" :jenkinsfile-source "pipeline { }"})
    (is (some? (jobs/find-job "ephemeral")))
    (let [resp ((routes/make-handler) (mk-req :delete "/anvil/admin/jobs/ephemeral"))]
      (is (= 200 (:status resp))))
    (is (nil? (jobs/find-job "ephemeral")))))

(deftest delete-nonexistent-job-test
  (testing "DELETE on a missing job returns 404"
    (let [resp ((routes/make-handler) (mk-req :delete "/anvil/admin/jobs/never-existed"))]
      (is (= 404 (:status resp))))))

(deftest register-then-build-via-jenkins-shim-end-to-end-test
  (testing "register via /anvil/admin/jobs, then trigger via /jenkins/job/<n>/build,
            confirming the two surfaces compose"
    (let [handler (routes/make-handler)]
      ;; Register
      (handler (mk-req :post "/anvil/admin/jobs"
                       {"name" "compose-demo"
                        "jenkinsfile_source" "pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }"}))
      ;; Trigger via Jenkins-shape
      (let [trig-resp (handler (mk-req :post "/jenkins/job/compose-demo/build"))]
        (is (= 201 (:status trig-resp)))
        (is (str/starts-with? (get-in trig-resp [:headers "Location"])
                              "/jenkins/queue/item/")))
      ;; Confirm via Jenkins shape
      (let [job-resp (handler (mk-req :get "/jenkins/job/compose-demo/api/json"))]
        (is (= 200 (:status job-resp)))))))
