(ns anvil.secrets.kms-test
  "Tests for the Cloud-KMS SecretBackend adapter (T2.3).

   We don't depend on the AWS SDK being on the classpath — the
   real `aws-decrypt` is replaced via with-redefs. The protocol
   surface + provider-stub behaviour is what we cover."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.secrets :as s]
            [anvil.secrets.kms :as kms]))

(use-fixtures :each
  (fn [f] (try (f) (finally (s/reset-for-tests!)))))

;; ---------------------------------------------------------------------------
;; Constructor invariants
;; ---------------------------------------------------------------------------

(deftest make-backend-requires-provider
  (is (thrown? clojure.lang.ExceptionInfo
               (kms/make-backend {:region "us-east-1" :blobs {}}))))

(deftest make-backend-aws-requires-region
  ;; Stub out aws-kms-client so this test doesn't try to call the SDK
  (with-redefs [kms/aws-kms-client (constantly :stub-client)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":region required"
                          (kms/make-backend {:provider :aws :blobs {}})))))

(deftest make-backend-aws-builds-with-stubbed-client
  (with-redefs [kms/aws-kms-client (constantly :stub-client)]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1" :blobs {}})]
      (is (= :kms (s/backend-kind b)))
      (is (= :stub-client (:client b))))))

(deftest make-backend-rejects-unknown-provider
  (is (thrown? clojure.lang.ExceptionInfo
               (kms/make-backend {:provider :wat :blobs {}}))))

;; ---------------------------------------------------------------------------
;; AWS resolve!
;; ---------------------------------------------------------------------------

(deftest aws-resolve-decrypts-and-returns-value-type
  (with-redefs [kms/aws-kms-client (constantly :stub-client)
                kms/aws-decrypt (fn [_client ct]
                                  (str "PLAINTEXT(" ct ")"))]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1"
                               :blobs {"k1" {:ciphertext-b64 "abc=="
                                             :type :string}
                                       "k2" {:ciphertext-b64 "def=="
                                             :type :username-password}}})]
      (is (= {:value "PLAINTEXT(abc==)" :type :string}
             (s/resolve! b "k1")))
      (is (= {:value "PLAINTEXT(def==)" :type :username-password}
             (s/resolve! b "k2"))))))

(deftest aws-resolve-unknown-id-returns-nil
  (with-redefs [kms/aws-kms-client (constantly :stub-client)
                kms/aws-decrypt (fn [_ _] (throw (Error. "should not be called")))]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1"
                               :blobs {}})]
      (is (nil? (s/resolve! b "missing"))))))

(deftest aws-resolve-blank-ciphertext-returns-nil
  (with-redefs [kms/aws-kms-client (constantly :stub-client)
                kms/aws-decrypt (fn [_ _] (throw (Error. "should not be called")))]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1"
                               :blobs {"k" {:ciphertext-b64 ""}}})]
      (is (nil? (s/resolve! b "k"))))))

(deftest aws-resolve-decrypt-failure-returns-nil
  (with-redefs [kms/aws-kms-client (constantly :stub-client)
                kms/aws-decrypt (fn [_ _]
                                  (throw (ex-info "KMS Decrypt anomaly"
                                                  {:category :fault})))]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1"
                               :blobs {"k" {:ciphertext-b64 "x=="}}})]
      (is (nil? (s/resolve! b "k"))
          "decrypt errors degrade to nil — caller sees unresolved"))))

(deftest aws-resolve-defaults-type-to-string
  (with-redefs [kms/aws-kms-client (constantly :stub-client)
                kms/aws-decrypt (fn [_ _] "pt")]
    (let [b (kms/make-backend {:provider :aws :region "us-east-1"
                               :blobs {"k" {:ciphertext-b64 "x=="}}})]
      (is (= :string (:type (s/resolve! b "k")))))))

;; ---------------------------------------------------------------------------
;; Stubbed providers (GCP / Azure) — :resolve! returns nil cleanly
;; ---------------------------------------------------------------------------

(deftest gcp-stub-returns-nil-for-every-id
  (let [b (kms/make-backend {:provider :gcp
                             :blobs {"k" {:ciphertext-b64 "x=="}}})]
    (is (nil? (s/resolve! b "k"))
        "GCP stub: resolve! returns nil — v0.6.x implements")))

(deftest azure-stub-returns-nil-for-every-id
  (let [b (kms/make-backend {:provider :azure
                             :blobs {"k" {:ciphertext-b64 "x=="}}})]
    (is (nil? (s/resolve! b "k")))))

;; ---------------------------------------------------------------------------
;; list-ids
;; ---------------------------------------------------------------------------

(deftest list-ids-returns-blob-keys-no-values
  (with-redefs [kms/aws-kms-client (constantly :stub-client)]
    (let [b (kms/make-backend
             {:provider :aws :region "us-east-1"
              :blobs {"a" {:ciphertext-b64 "x" :type :string}
                      "b" {:ciphertext-b64 "y" :type :string}}})]
      (is (= #{"a" "b"} (set (s/list-ids b))))
      ;; And the listing never contains ciphertext or any non-id keys
      (is (every? string? (s/list-ids b))))))

;; ---------------------------------------------------------------------------
;; Lazy AWS SDK loading — clear error if SDK is absent
;; ---------------------------------------------------------------------------

(deftest missing-sdk-throws-clear-error
  ;; Simulate the SDK not being on the classpath by redef'ing
  ;; requiring-resolve to return nil for the AWS symbol.
  (let [orig requiring-resolve]
    (with-redefs [requiring-resolve
                  (fn [sym]
                    (if (= sym 'cognitect.aws.client.api/client)
                      nil
                      (orig sym)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"AWS SDK not on classpath"
                            (#'kms/aws-kms-client "us-east-1"))))))
