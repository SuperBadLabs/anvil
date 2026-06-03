(ns anvil.integration.github-test
  "Tests for T3.1 + T3.2 + T3.3 — auth + checks-api + signature
   verification. HTTP is fully mocked via the :http-fn parameter so
   no network is touched."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [anvil.integration.github :as gh]))

;; ---------------------------------------------------------------------------
;; Signature verification (T3.3)
;; ---------------------------------------------------------------------------

(deftest hmac-sha256-is-stable-and-changes-with-input
  ;; We don't pin a known RFC test vector (different implementations
  ;; pad differently). Instead assert determinism + sensitivity.
  (let [m1 (gh/hmac-sha256 "secret" "hello")
        m2 (gh/hmac-sha256 "secret" "hello")
        m3 (gh/hmac-sha256 "secret" "world")
        m4 (gh/hmac-sha256 "other" "hello")]
    (is (= m1 m2) "deterministic")
    (is (not= m1 m3) "msg-sensitive")
    (is (not= m1 m4) "key-sensitive")
    (is (= 64 (count m1)) "sha256 hex digest is 64 chars")))

(deftest verify-signature-without-secret-accepts-unverified
  (with-redefs [gh/webhook-secret (constantly nil)]
    (is (true? (gh/verify-webhook-signature "body" nil)))
    (is (true? (gh/verify-webhook-signature "body" "sha256=anything")))))

(deftest verify-signature-with-secret
  (with-redefs [gh/webhook-secret (constantly "shhh")]
    (let [body "{\"pull_request\":{}}"
          good (str "sha256=" (gh/hmac-sha256 "shhh" body))]
      (is (true? (gh/verify-webhook-signature body good)))
      (is (false? (gh/verify-webhook-signature body
                                               "sha256=00deadbeef00000000000000000000000000000000000000000000000000000000")))
      (is (false? (gh/verify-webhook-signature body nil)))
      (is (false? (gh/verify-webhook-signature body "md5=…"))))))

;; ---------------------------------------------------------------------------
;; Checks API (T3.2)
;; ---------------------------------------------------------------------------

(defn- recording-http-fn [calls-atom]
  (fn [opts]
    (swap! calls-atom conj opts)
    {:status 201
     :body (json/write-str {:id 99999 :status "in_progress"})}))

(deftest create-check-run-posts-correct-payload
  (let [calls (atom [])
        http-fn (recording-http-fn calls)]
    (with-redefs [gh/token (constantly "tok_xxx")]
      (let [resp (gh/create-check-run!
                  {:repo "foo/bar"
                   :name "anvil"
                   :head-sha "abc123"
                   :status :in-progress
                   :details-url "http://anvil/builds/1"
                   :http-fn http-fn})]
        (is (= 201 (:status resp)))
        (is (= 99999 (-> resp :parsed :id))))
      (let [call (first @calls)
            body (json/read-str (:body call) :key-fn keyword)]
        (testing "URL + method"
          (is (= "https://api.github.com/repos/foo/bar/check-runs" (:url call)))
          (is (= :post (:method call))))
        (testing "Authorization header carries the PAT"
          (is (= "Bearer tok_xxx"
                 (get-in call [:headers "Authorization"]))))
        (testing "Status :in-progress normalized to GitHub's snake_case"
          (is (= "in_progress" (:status body)))
          (is (= "abc123" (:head_sha body)))
          (is (= "http://anvil/builds/1" (:details_url body))))
        (testing "nil keys stripped from body"
          (is (not (contains? body :conclusion))))))))

(deftest update-check-run-uses-patch-and-id-in-url
  (let [calls (atom [])
        http-fn (recording-http-fn calls)]
    (with-redefs [gh/token (constantly "tok")]
      (gh/update-check-run!
       {:repo "foo/bar"
        :check-run-id 99999
        :status :completed
        :conclusion :success
        :http-fn http-fn})
      (let [call (first @calls)
            body (json/read-str (:body call) :key-fn keyword)]
        (is (= :patch (:method call)))
        (is (= "https://api.github.com/repos/foo/bar/check-runs/99999"
               (:url call)))
        (is (= "completed" (:status body)))
        (is (= "success" (:conclusion body)))))))

(deftest result-to-conclusion-mapping
  (is (= :success   (gh/build-result->conclusion :success)))
  (is (= :failure   (gh/build-result->conclusion :failure)))
  (is (= :neutral   (gh/build-result->conclusion :unstable)))
  (is (= :cancelled (gh/build-result->conclusion :aborted)))
  (is (= :timed-out (gh/build-result->conclusion :timed-out)))
  (is (= :neutral   (gh/build-result->conclusion :weird-thing))))

(deftest record-and-lookup-check-run-mapping
  (gh/clear-check-runs!)
  (gh/record-check-run! "demo" 7 12345 "foo/bar")
  (let [info (gh/check-run-for "demo" 7)]
    (is (= 12345 (:check-run-id info)))
    (is (= "foo/bar" (:repo info))))
  (is (nil? (gh/check-run-for "demo" 999))))
