(ns anvil.integration.gitlab-test
  "Tests for T3.1 — GitLab Commit Status API client.
   HTTP is fully mocked via :http-fn so no network is touched."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [anvil.integration.gitlab :as gl]))

;; ---------------------------------------------------------------------------
;; build-result->state mapping
;; ---------------------------------------------------------------------------

(deftest result->state-mapping
  (testing "success variants"
    (is (= "success"  (gl/build-result->state :success)))
    (is (= "success"  (gl/build-result->state :neutral))))
  (testing "failure variants"
    (is (= "failed"   (gl/build-result->state :failure)))
    (is (= "failed"   (gl/build-result->state :unstable)))
    (is (= "failed"   (gl/build-result->state :unknown-keyword))))
  (testing "aborted"
    (is (= "canceled" (gl/build-result->state :aborted)))))

;; ---------------------------------------------------------------------------
;; post-commit-status! with mock http-fn
;; ---------------------------------------------------------------------------

(defn- recording-http-fn [calls-atom resp]
  (fn [opts]
    (swap! calls-atom conj opts)
    resp))

(deftest post-commit-status-uses-correct-url-and-method
  (let [calls (atom [])
        http-fn (recording-http-fn calls
                                   {:status 201
                                    :body   (json/write-str {:id 42 :status "running"})})]
    (with-redefs [gl/token    (constantly "gl_tok_abc")
                  gl/base-url (constantly "https://gitlab.example.com")]
      (let [resp (gl/post-commit-status!
                  {:project-id  "99"
                   :sha         "deadbeef1234"
                   :state       "running"
                   :description "build running"
                   :http-fn     http-fn})]
        (testing "HTTP status forwarded"
          (is (= 201 (:status resp))))
        (testing "parsed body"
          (is (= 42 (-> resp :parsed :id))))
        (let [call (first @calls)
              body (json/read-str (:body call) :key-fn keyword)]
          (testing "URL construction"
            ;; Project-id '99' must be URL-encoded in the path
            (is (= "https://gitlab.example.com/api/v4/projects/99/statuses/deadbeef1234"
                   (:url call))))
          (testing "method"
            (is (= :post (:method call))))
          (testing "PRIVATE-TOKEN header"
            (is (= "gl_tok_abc" (get-in call [:headers "PRIVATE-TOKEN"]))))
          (testing "body contains state and description"
            (is (= "running"       (:state body)))
            (is (= "build running" (:description body)))))))))

(deftest post-commit-status-encodes-path-based-project-id
  (let [calls (atom [])
        http-fn (recording-http-fn calls {:status 200 :body "{}"})]
    (with-redefs [gl/token    (constantly "t")
                  gl/base-url (constantly "https://gitlab.com")]
      (gl/post-commit-status!
       {:project-id "my-group/my-repo"
        :sha        "abc"
        :state      "success"
        :http-fn    http-fn})
      (let [url (:url (first @calls))]
        (is (re-find #"my-group%2Fmy-repo" url)
            "slash in project path must be percent-encoded")))))

(deftest post-commit-status-optional-fields-omitted-when-nil
  (let [calls (atom [])
        http-fn (recording-http-fn calls {:status 201 :body "{}"})]
    (with-redefs [gl/token    (constantly "t")
                  gl/base-url (constantly "https://gitlab.com")]
      (gl/post-commit-status!
       {:project-id "1"
        :sha        "abc"
        :state      "pending"
        :http-fn    http-fn})
      (let [body (json/read-str (:body (first @calls)) :key-fn keyword)]
        (is (not (contains? body :description)) "description absent when not supplied")
        (is (not (contains? body :target_url))  "target_url absent when not supplied")))))

;; ---------------------------------------------------------------------------
;; SHA cache
;; ---------------------------------------------------------------------------

(deftest sha-record-and-lookup
  (gl/clear-shas!)
  (gl/record-sha! "demo" 5 "abc123")
  (is (= "abc123" (gl/sha-for "demo" 5)))
  (is (nil? (gl/sha-for "demo" 99))))

(deftest clear-shas-empties-the-map
  (gl/record-sha! "demo" 1 "abc")
  (gl/clear-shas!)
  (is (nil? (gl/sha-for "demo" 1))))
