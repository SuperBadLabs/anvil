(ns anvil.integration.bitbucket-test
  "Tests for T3.2 — Bitbucket Build Status API client.
   HTTP is fully mocked via :http-fn so no network is touched."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [anvil.integration.bitbucket :as bb]))

;; ---------------------------------------------------------------------------
;; build-result->state mapping
;; ---------------------------------------------------------------------------

(deftest result->state-mapping
  (testing "success variants"
    (is (= "SUCCESSFUL" (bb/build-result->state :success)))
    (is (= "SUCCESSFUL" (bb/build-result->state :neutral))))
  (testing "failure variants"
    (is (= "FAILED"  (bb/build-result->state :failure)))
    (is (= "FAILED"  (bb/build-result->state :unstable)))
    (is (= "FAILED"  (bb/build-result->state :unknown-keyword))))
  (testing "stopped"
    (is (= "STOPPED" (bb/build-result->state :aborted)))))

;; ---------------------------------------------------------------------------
;; post-build-status! with mock http-fn
;; ---------------------------------------------------------------------------

(defn- recording-http-fn [calls-atom resp]
  (fn [opts]
    (swap! calls-atom conj opts)
    resp))

(deftest post-build-status-uses-correct-url
  (let [calls (atom [])
        http-fn (recording-http-fn calls
                                   {:status 201
                                    :body   (json/write-str {:state "INPROGRESS" :key "anvil"})})]
    (with-redefs [bb/base-url (constantly "https://api.bitbucket.org")]
      (let [resp (bb/post-build-status!
                  {:workspace   "acme"
                   :repo-slug   "my-repo"
                   :sha         "deadbeef"
                   :state       "INPROGRESS"
                   :description "build running"
                   :http-fn     http-fn})]
        (testing "HTTP status forwarded"
          (is (= 201 (:status resp))))
        (testing "parsed body"
          (is (= "INPROGRESS" (-> resp :parsed :state))))
        (let [call (first @calls)
              body (json/read-str (:body call) :key-fn keyword)]
          (testing "URL"
            (is (= "https://api.bitbucket.org/2.0/repositories/acme/my-repo/commit/deadbeef/statuses/build"
                   (:url call))))
          (testing "method is POST"
            (is (= :post (:method call))))
          (testing "body contains state"
            (is (= "INPROGRESS" (:state body))))
          (testing "key defaults to 'anvil'"
            (is (= "anvil" (:key body))))
          (testing "description in body"
            (is (= "build running" (:description body)))))))))

(deftest post-build-status-uses-bearer-auth
  (let [calls (atom [])
        http-fn (recording-http-fn calls {:status 200 :body "{}"})]
    (with-redefs [bb/base-url (constantly "https://api.bitbucket.org")
                  ;; Simulate ANVIL_BITBUCKET_TOKEN env var being set
                  anvil.integration.bitbucket/bearer-token (constantly "my_oauth_token")]
      (bb/post-build-status!
       {:workspace "ws" :repo-slug "repo" :sha "abc" :state "SUCCESSFUL"
        :http-fn http-fn})
      (let [auth (get-in (first @calls) [:headers "Authorization"])]
        (is (= "Bearer my_oauth_token" auth))))))

(deftest post-build-status-uses-basic-auth-fallback
  (let [calls (atom [])
        http-fn (recording-http-fn calls {:status 200 :body "{}"})]
    (with-redefs [bb/base-url (constantly "https://api.bitbucket.org")
                  anvil.integration.bitbucket/bearer-token (constantly nil)
                  anvil.integration.bitbucket/basic-user   (constantly "alice")
                  anvil.integration.bitbucket/basic-password (constantly "s3cr3t")]
      (bb/post-build-status!
       {:workspace "ws" :repo-slug "repo" :sha "abc" :state "FAILED"
        :http-fn http-fn})
      (let [auth (get-in (first @calls) [:headers "Authorization"])]
        (is (some? auth) "Authorization header must be present")
        (is (.startsWith auth "Basic "))))))

(deftest post-build-status-no-auth-omits-header
  (let [calls (atom [])
        http-fn (recording-http-fn calls {:status 200 :body "{}"})]
    (with-redefs [bb/base-url (constantly "https://api.bitbucket.org")
                  anvil.integration.bitbucket/bearer-token  (constantly nil)
                  anvil.integration.bitbucket/basic-user    (constantly nil)
                  anvil.integration.bitbucket/basic-password (constantly nil)]
      (bb/post-build-status!
       {:workspace "ws" :repo-slug "repo" :sha "abc" :state "SUCCESSFUL"
        :http-fn http-fn})
      (let [auth (get-in (first @calls) [:headers "Authorization"])]
        (is (nil? auth) "no auth configured → no Authorization header")))))

;; ---------------------------------------------------------------------------
;; SHA cache
;; ---------------------------------------------------------------------------

(deftest sha-record-and-lookup
  (bb/clear-shas!)
  (bb/record-sha! "demo" 3 "sha-bb-1")
  (is (= "sha-bb-1" (bb/sha-for "demo" 3)))
  (is (nil? (bb/sha-for "demo" 99))))

(deftest clear-shas-empties-the-map
  (bb/record-sha! "demo" 1 "old")
  (bb/clear-shas!)
  (is (nil? (bb/sha-for "demo" 1))))
