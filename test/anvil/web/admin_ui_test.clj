(ns anvil.web.admin-ui-test
  "Admin UI: dashboard, jobs list, per-job page, per-build page, queue,
   coverage. Tests assert the response is 200 and that load-bearing
   content (job names, build numbers, badges, coverage percentages)
   appears in the rendered HTML."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]))

(defn- get-req [uri]
  {:request-method :get :uri uri :scheme :http
   :headers {"host" "anvil.test"} :query-params {} :form-params {}})

(use-fixtures :each
  (fn [f] (jobs/clear!) (queue/clear!) (f) (queue/clear!) (jobs/clear!)))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(deftest dashboard-empty-state-test
  (testing "GET / shows the dashboard with zero jobs"
    (let [handler (routes/make-handler)
          resp (handler (get-req "/"))]
      (is (= 200 (:status resp)))
      (let [body (:body resp)]
        (is (str/includes? body "anvil"))
        (is (str/includes? body "Dashboard"))
        (is (str/includes? body "Jobs"))
        (is (str/includes? body "No jobs registered yet"))))))

(deftest dashboard-with-jobs-test
  (testing "GET / surfaces registered jobs in the recent-builds table"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [handler (routes/make-handler)
          resp (handler (get-req "/"))
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "demo"))
      (is (str/includes? body "/jobs/demo")))))

;; ---------------------------------------------------------------------------
;; Jobs list
;; ---------------------------------------------------------------------------

(deftest jobs-list-empty-state-test
  (testing "GET /jobs with no jobs shows the empty-state hint"
    (let [resp ((routes/make-handler) (get-req "/jobs"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "No jobs registered yet")))))

(deftest jobs-list-populated-test
  (testing "GET /jobs renders one row per registered job"
    (jobs/register-job! {:name "alpha" :jenkinsfile-source "pipeline { }"})
    (jobs/register-job! {:name "beta"  :jenkinsfile-source "pipeline { }"})
    (let [resp ((routes/make-handler) (get-req "/jobs"))
          body (:body resp)]
      (is (str/includes? body "alpha"))
      (is (str/includes? body "beta"))
      (is (str/includes? body "Jobs (2)")))))

;; ---------------------------------------------------------------------------
;; Per-job page
;; ---------------------------------------------------------------------------

(deftest job-detail-not-found-test
  (testing "GET /jobs/<n> for an unknown job returns the 'not found' page (still 200)"
    (let [resp ((routes/make-handler) (get-req "/jobs/no-such-job"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "not found")))))

(deftest job-detail-shows-jenkinsfile-test
  (testing "the per-job page renders the Jenkinsfile source + builds heading"
    (jobs/register-job!
     {:name "demo"
      :jenkinsfile-source "pipeline { agent any; stages { stage('Quick') { steps { echo 'hi' } } } }"})
    (let [resp ((routes/make-handler) (get-req "/jobs/demo"))
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "pipeline"))
      (is (str/includes? body "Quick"))
      (is (str/includes? body "Build history")))))

(deftest job-detail-shows-build-rows-test
  (testing "after recording a build, the per-job page lists it"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [n (jobs/record-build-start! "demo" {})]
      (jobs/record-build-end! "demo" n {:result :success
                                        :effects [[:sh {:cmd "echo done"}]]}))
    (let [resp ((routes/make-handler) (get-req "/jobs/demo"))
          body (:body resp)]
      (is (str/includes? body "#1"))
      (is (str/includes? body "success")))))

;; ---------------------------------------------------------------------------
;; Build detail
;; ---------------------------------------------------------------------------

(deftest build-detail-shows-console-test
  (testing "the per-build page shows the console log content"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (let [n (jobs/record-build-start! "demo" {})]
      (jobs/record-build-end!
       "demo" n
       {:result :success
        :effects [[:sh {:cmd "echo hello"}] [:stdout "hello"]]}))
    (let [resp ((routes/make-handler) (get-req "/jobs/demo/1"))
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "Console"))
      (is (str/includes? body "echo hello")))))

(deftest build-detail-not-found-test
  (testing "a non-existent build renders a 'not found' page (200 + nav)"
    (let [resp ((routes/make-handler) (get-req "/jobs/no-job/42"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "not found")))))

;; ---------------------------------------------------------------------------
;; Queue page
;; ---------------------------------------------------------------------------

(deftest queue-page-empty-test
  (testing "GET /queue with empty queue shows the placeholder"
    (let [resp ((routes/make-handler) (get-req "/queue"))]
      (is (= 200 (:status resp)))
      ;; TU5.1 rewrote the queue placeholder copy.
      (is (str/includes? (:body resp) "Queue empty.")))))

(deftest queue-page-with-items-test
  (testing "GET /queue shows queued items"
    (jobs/register-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (queue/enqueue! "demo" {})
    (queue/enqueue! "demo" {})
    (let [resp ((routes/make-handler) (get-req "/queue"))
          body (:body resp)]
      (is (str/includes? body "demo"))
      ;; Two queue items rendered
      (is (>= (count (re-seq #"/jobs/demo" body)) 2)))))

;; ---------------------------------------------------------------------------
;; Coverage page
;; ---------------------------------------------------------------------------

(deftest coverage-page-empty-state-test
  (testing "GET /coverage with no jobs"
    (let [resp ((routes/make-handler) (get-req "/coverage"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "No jobs registered yet")))))

(deftest coverage-page-reports-coverage-test
  (testing "GET /coverage runs the parser over each job's Jenkinsfile
            and surfaces a coverage % per job + aggregate"
    (jobs/register-job!
     {:name "all-known"
      :jenkinsfile-source "pipeline { agent any; stages { stage('S') {
                            steps { sh 'echo one'; sh 'echo two' } } } }"})
    (let [resp ((routes/make-handler) (get-req "/coverage"))
          body (:body resp)]
      (is (= 200 (:status resp)))
      ;; All-known should report 100%
      (is (str/includes? body "100.0%"))
      (is (str/includes? body "all-known"))
      (is (str/includes? body "Per-job")))))