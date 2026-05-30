(ns anvil.web.jenkins-api-test
  "Tests for the Jenkins REST API shim — round-trips the read + write
   endpoints jenkins-cli.jar and the GitHub Jenkins plugin commonly hit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]))

(use-fixtures :each
  (fn [f]
    (jobs/clear!)
    (queue/clear!)
    (queue/start-workers! 2 runner/run-build!)
    (try (f)
         (finally
           (queue/stop-workers! 2000)
           (queue/clear!)
           (jobs/clear!)))))

(defn- mk-req
  ([method uri] (mk-req method uri {}))
  ([method uri {:keys [headers query-params form-params]}]
   {:request-method method
    :uri uri
    :scheme :http
    :headers (merge {"host" "anvil.test"} headers)
    :query-params (or query-params {})
    :form-params (or form-params {})
    :query-string (when (seq query-params)
                    (str/join "&" (for [[k v] query-params] (str k "=" v))))}))

(defn- json-body [resp]
  (json/read-str (:body resp)))

(defn- register-simple-job! []
  (jobs/register-job!
   {:name "demo"
    :jenkinsfile-source "pipeline {
                            agent any
                            stages {
                              stage('Build') {
                                steps {
                                  sh 'echo building'
                                  sh 'echo testing'
                                }
                              }
                            }
                          }"}))

;; ---------------------------------------------------------------------------
;; Read endpoints
;; ---------------------------------------------------------------------------

(deftest root-api-json-test
  (testing "GET /jenkins/api/json returns Jenkins-shape root + jobs list"
    (register-simple-job!)
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/api/json"))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (= "hudson.model.Hudson" (get body "_class")))
      (is (= "NORMAL" (get body "mode")))
      (is (= 1 (count (get body "jobs"))))
      (let [job (first (get body "jobs"))]
        (is (= "demo" (get job "name")))
        (is (= "hudson.model.FreeStyleProject" (get job "_class")))
        (is (= "notbuilt" (get job "color")))
        (is (str/includes? (get job "url") "/jenkins/job/demo/"))))))

(deftest crumb-issuer-test
  (testing "GET /jenkins/crumbIssuer/api/json returns a synthetic crumb"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/crumbIssuer/api/json"))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (= "Jenkins-Crumb" (get body "crumbRequestField")))
      (is (some? (get body "crumb"))))))

(deftest queue-api-test
  (testing "GET /jenkins/queue/api/json returns the empty queue"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/queue/api/json"))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (= "hudson.model.Queue" (get body "_class")))
      (is (= [] (get body "items"))))))

(deftest job-api-test
  (testing "GET /jenkins/job/<name>/api/json returns the job's metadata"
    (register-simple-job!)
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/job/demo/api/json"))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (= "demo" (get body "name")))
      (is (= "notbuilt" (get body "color")))
      (is (true? (get body "buildable")))
      (is (= 1 (get body "nextBuildNumber"))
          "before any build, nextBuildNumber is 1"))))

(deftest job-not-found-test
  (testing "missing job → 404 with a Jenkins-shape error body"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :get "/jenkins/job/no-such-job/api/json"))]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Build trigger
;; ---------------------------------------------------------------------------

(deftest trigger-build-and-poll-test
  (testing "POST /jenkins/job/<name>/build triggers a build; consoleText
            and build api/json reflect it after completion"
    (register-simple-job!)
    (let [handler (routes/make-handler)
          trigger-resp (handler (mk-req :post "/jenkins/job/demo/build"))]
      (is (= 201 (:status trigger-resp)))
      (is (str/starts-with? (get-in trigger-resp [:headers "Location"])
                            "/jenkins/queue/item/"))

      ;; Wait for the synchronous-future-completion. v1 just polls
      ;; for the build's :building? false.
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [b (jobs/find-build "demo" 1)]
            (cond
              (and b (not (:building? b))) :done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "build did not complete in time" {:b b}))
              :else (do (Thread/sleep 50) (recur))))))

      ;; build api/json
      (let [build-resp (handler (mk-req :get "/jenkins/job/demo/1/api/json"))
            body (json-body build-resp)]
        (is (= 200 (:status build-resp)))
        (is (= 1 (get body "number")))
        (is (= "SUCCESS" (get body "result")))
        (is (false? (get body "building"))))

      ;; consoleText
      (let [console-resp (handler (mk-req :get "/jenkins/job/demo/1/consoleText"))]
        (is (= 200 (:status console-resp)))
        (is (= "text/plain; charset=utf-8" (get-in console-resp [:headers "Content-Type"])))
        (is (str/includes? (:body console-resp) "+ echo building"))
        (is (str/includes? (:body console-resp) "+ echo testing")))

      ;; Job color now reflects SUCCESS.
      (let [job-resp (handler (mk-req :get "/jenkins/job/demo/api/json"))
            body (json-body job-resp)]
        (is (= "blue" (get body "color")))
        (is (= 1 (get-in body ["lastSuccessfulBuild" "number"])))))))

(deftest progressive-log-test
  (testing "GET /jenkins/job/<name>/<n>/logText/progressiveText supports
            incremental polling with X-Text-Size + X-More-Data"
    (register-simple-job!)
    (let [handler (routes/make-handler)]
      (handler (mk-req :post "/jenkins/job/demo/build"))
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [b (jobs/find-build "demo" 1)]
            (when-not (and b (not (:building? b)))
              (when (< (System/currentTimeMillis) deadline)
                (Thread/sleep 50)
                (recur))))))

      (let [resp1 (handler (mk-req :get "/jenkins/job/demo/1/logText/progressiveText"
                                   {:query-params {"start" "0"}}))]
        (is (= 200 (:status resp1)))
        (is (= "false" (get-in resp1 [:headers "X-More-Data"])))
        (is (some? (get-in resp1 [:headers "X-Text-Size"])))
        (is (str/includes? (:body resp1) "echo")))

      ;; Mid-poll: start at 5 should produce a smaller slice (no error
      ;; about negative slice etc.).
      (let [resp2 (handler (mk-req :get "/jenkins/job/demo/1/logText/progressiveText"
                                   {:query-params {"start" "5"}}))]
        (is (= 200 (:status resp2)))))))

(deftest build-with-parameters-test
  (testing "POST /jenkins/job/<name>/buildWithParameters accepts form params
            and threads them into the build context"
    (jobs/register-job!
     {:name "param-demo"
      :jenkinsfile-source "pipeline { agent any; stages { stage('S') {
                            steps { echo 'building' } } } }"})

    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/jenkins/job/param-demo/buildWithParameters"
                                {:form-params {"VERSION" "1.0.0"
                                               "STAGE" "prod"}}))]
      (is (= 201 (:status resp)))
      (let [body (json/read-str (:body resp))]
        (is (= {"VERSION" "1.0.0" "STAGE" "prod"} (get body "parameters")))))))

;; ---------------------------------------------------------------------------
;; 501 stubs
;; ---------------------------------------------------------------------------

(deftest create-item-501-test
  (testing "POST /jenkins/createItem returns 501 with anvil-replacement-path"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/jenkins/createItem"))]
      (is (= 501 (:status resp)))
      (let [body (json-body resp)]
        (is (str/includes? (get body "anvil-replacement-path")
                           "anvil import jenkinsfile"))))))

(deftest script-console-501-test
  (testing "POST /jenkins/script returns 501 — deliberately not implemented"
    (let [handler (routes/make-handler)
          resp (handler (mk-req :post "/jenkins/script"))
          body (json-body resp)]
      (is (= 501 (:status resp)))
      (is (str/includes? (get body "anvil-replacement-path") "deliberately")
          "the replacement-path text is clear that anvil never planning to ship this")
      ;; "tranche" is "never" for this endpoint — the policy decision,
      ;; not a 'we'll get to it later'.
      (is (= "never" (get body "tranche"))))))
