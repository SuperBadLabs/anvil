(ns anvil.web.build-form-test
  "End-to-end-shaped tests for the trigger UX (TU4.1–TU4.5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]))

(defn- register-param-job!
  "Register a Jenkinsfile-source job with a parameters{} block."
  [job-name source]
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source source
                       :buildable? true
                       :max-concurrent-builds 1}))

(defn- drain-queue! []
  (doseq [item (queue/queue-snapshot)]
    (queue/cancel! (:queue-id item))))

(use-fixtures :each (fn [t] (t) (drain-queue!)))

;; ===========================================================================
;; TU4.1 — form rendering
;; ===========================================================================

(deftest form-renders-all-five-param-kinds
  (register-param-job!
   "form-all-kinds"
   "pipeline { agent any
     parameters {
       string(name: 'BRANCH', defaultValue: 'main', description: 'git branch')
       choice(name: 'ENV', choices: ['dev', 'stg', 'prod'], description: 'target')
       booleanParam(name: 'CLEAN', defaultValue: false, description: 'wipe ws')
       password(name: 'TOKEN', defaultValue: '', description: 'auth')
       file(name: 'UPLOAD', description: 'tarball')
     }}")
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/form-all-kinds/build-form"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (testing "every parameter rendered with the right input type"
      ;; Hiccup serializes attrs alphabetically, so id= comes BEFORE
      ;; type=. Use order-independent assertions.
      (is (re-find #"id=\"BRANCH\"" body))
      (is (re-find #"type=\"text\"" body))
      (is (re-find #"value=\"main\"" body))
      (is (re-find #"id=\"ENV\"" body))
      (is (re-find #"<select[^>]*name=\"ENV\"" body))
      (is (re-find #"value=\"dev\"" body))
      (is (re-find #"value=\"prod\"" body))
      (is (re-find #"id=\"CLEAN\"" body))
      (is (re-find #"type=\"checkbox\"" body))
      (is (re-find #"id=\"TOKEN\"" body))
      (is (re-find #"type=\"password\"" body))
      (is (re-find #"id=\"UPLOAD\"" body))
      (is (re-find #"type=\"file\"" body)))
    (testing "trigger-N-times widget shown"
      (is (str/includes? body "trigger-n-enabled"))
      (is (str/includes? body "for flake-hunting")))))

(deftest form-handles-job-with-no-params-block
  (register-param-job! "no-params" "pipeline { agent any; stages { stage('s') { steps { echo 'x' } } } }")
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/no-params/build-form"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "no parameters block"))))

;; ===========================================================================
;; TU4.1 — POST enqueues
;; ===========================================================================

(deftest valid-submission-enqueues-one-build
  (register-param-job! "tu41-sub"
                       "parameters {
                          string(name: 'BRANCH', defaultValue: 'main')
                        }")
  (let [before (count (queue/queue-snapshot))
        h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu41-sub/build-form"
                 :form-params {"BRANCH" "release/1.2"}})]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/jobs/tu41-sub"))
    (let [after (queue/queue-snapshot)
          mine (last (filter #(= "tu41-sub" (:job-name %)) after))]
      (is (= (inc before) (count after)))
      (is (= "release/1.2" (-> mine :parameters (get "BRANCH")))))))

(deftest boolean-checkbox-coerces-to-true-on-on
  (register-param-job! "tu41-bool"
                       "parameters { booleanParam(name: 'CLEAN', defaultValue: false) }")
  (let [h (routes/make-handler)
        ;; Browsers send checked checkboxes as `name=on`; unchecked,
        ;; nothing at all.
        resp (h {:request-method :post
                 :uri "/jobs/tu41-bool/build-form"
                 :form-params {"CLEAN" "on"}})]
    (is (= 303 (:status resp)))
    (let [snap (queue/queue-snapshot)
          mine (last (filter #(= "tu41-bool" (:job-name %)) snap))]
      (is (true? (-> mine :parameters (get "CLEAN")))))))

;; ===========================================================================
;; TU4.2 — validation
;; ===========================================================================

(deftest choice-out-of-range-fails-validation
  (register-param-job! "tu42-choice"
                       "parameters {
                          choice(name: 'ENV', choices: ['dev', 'prod'])
                        }")
  (let [h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu42-choice/build-form"
                 :form-params {"ENV" "stage"}})]
    (is (= 200 (:status resp))
        "validation failure re-renders the form, no redirect")
    (let [body (str (:body resp))]
      (is (str/includes? body "must be one of: dev, prod")))
    (is (empty? (filter #(= "tu42-choice" (:job-name %))
                        (queue/queue-snapshot)))
        "build NOT enqueued")))

(deftest inline-validation-returns-just-the-error-fragment
  (register-param-job! "tu42-inline"
                       "parameters {
                          choice(name: 'ENV', choices: ['dev', 'prod'])
                        }")
  (let [h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu42-inline/build-form"
                 :query-string "validate=ENV"
                 :form-params {"ENV" "bogus"}})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "must be one of"))
    (is (not (str/includes? body "<html"))
        "inline validation returns ONLY the error region, not the whole page")))

;; ===========================================================================
;; TU4.4 — copy-URL shortlink
;; ===========================================================================

(deftest copy-url-builds-buildwithparameters-curl
  (register-param-job! "tu44-url"
                       "parameters {
                          string(name: 'BRANCH', defaultValue: 'main')
                          choice(name: 'ENV', choices: ['dev'])
                        }")
  (let [h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu44-url/build-form"
                 :query-string "action=copy-url"
                 :headers {"host" "anvil.example.com"}
                 :form-params {"BRANCH" "feature/x" "ENV" "dev"}})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "/jenkins/job/tu44-url/buildWithParameters"))
    (is (str/includes? body "BRANCH=feature%2Fx"))
    (is (str/includes? body "ENV=dev"))
    (is (str/includes? body "curl -X POST"))))

;; ===========================================================================
;; TU4.5 — Trigger N times
;; ===========================================================================

(deftest trigger-n-enqueues-n-copies
  (register-param-job! "tu45-flake"
                       "parameters {
                          string(name: 'SEED', defaultValue: '0')
                        }")
  (let [before (count (queue/queue-snapshot))
        h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu45-flake/build-form"
                 :form-params {"SEED" "42"
                               "trigger-n-enabled" "true"
                               "trigger-n-count"   "5"}})]
    (is (= 303 (:status resp)))
    (let [mine (filter #(= "tu45-flake" (:job-name %)) (queue/queue-snapshot))]
      (is (= 5 (count mine)) "5 queue items, one per trigger")
      (is (every? #(= "42" (-> % :parameters (get "SEED"))) mine)
          "identical params on every copy")
      (is (= 5 (count (str/split (get-in resp [:headers "X-Anvil-Queue-Ids"]) #",")))))))

;; ===========================================================================
;; TU4.3 — recent-values cookie
;; ===========================================================================

(deftest successful-submission-sets-recent-cookie
  (register-param-job! "tu43-cookie"
                       "parameters { string(name: 'BRANCH', defaultValue: 'main') }")
  (let [h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu43-cookie/build-form"
                 :form-params {"BRANCH" "feature/x"}})]
    (is (= 303 (:status resp)))
    (let [cookies (get-in resp [:headers "Set-Cookie"])
          ;; Set-Cookie is a vec of strings; pr-str shows the whole thing.
          serialized (apply str (if (sequential? cookies) cookies [cookies]))]
      (is (some? cookies))
      (is (str/includes? serialized "anvil_recent_tu43-cookie")))))