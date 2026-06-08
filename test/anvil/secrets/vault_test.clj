(ns anvil.secrets.vault-test
  "Tests for the Vault SecretBackend adapter (T2.2). HTTP is mocked
   via with-redefs on the private `send-get` fn — no live Vault
   needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [anvil.secrets :as s]
            [anvil.secrets.vault :as v])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [f] (try (f) (finally (s/reset-for-tests!)))))

;; ---------------------------------------------------------------------------
;; Fixtures + helpers
;; ---------------------------------------------------------------------------

(defn- write-token! [content]
  (let [d (.toFile (Files/createTempDirectory "anvil-vault-test-"
                                              (make-array FileAttribute 0)))
        f (io/file d "token")]
    (spit f content)
    (.deleteOnExit f)
    (str f)))

(defn- fake-response
  "Build a stub object that responds to .statusCode + .body — what
   send-get returns. We don't import HttpResponse; the proxy class is
   easier to mock than a real instance."
  [status body]
  (reify
    java.net.http.HttpResponse
    (statusCode [_] status)
    (body [_] body)
    (headers [_] nil)
    (request [_] nil)
    (uri [_] nil)
    (version [_] nil)
    (sslSession [_] (java.util.Optional/empty))
    (previousResponse [_] (java.util.Optional/empty))))

;; ---------------------------------------------------------------------------
;; URL composition
;; ---------------------------------------------------------------------------

(deftest read-url-kv2-default
  (let [b (v/make-backend {:url "https://vault.example.com"
                           :token-path "/tmp/x"})
        cfg (:config b)]
    (is (= "https://vault.example.com/v1/secret/data/gh-token"
           (#'v/read-url cfg "gh-token")))))

(deftest read-url-kv2-with-prefix
  (let [cfg {:url "https://vault.example.com/"
             :token-path "/x"
             :kv-mount "secret"
             :kv-path-prefix "anvil/"
             :kv-version 2}]
    (is (= "https://vault.example.com/v1/secret/data/anvil/gh-token"
           (#'v/read-url cfg "gh-token"))
        "trailing slash on url is collapsed; prefix joins cleanly")))

(deftest read-url-kv1-omits-data-segment
  (let [cfg {:url "https://vault" :token-path "/x"
             :kv-mount "secret" :kv-path-prefix "" :kv-version 1}]
    (is (= "https://vault/v1/secret/gh-token"
           (#'v/read-url cfg "gh-token")))))

;; ---------------------------------------------------------------------------
;; KV-v2 response parsing
;; ---------------------------------------------------------------------------

(deftest parse-kv2-extracts-value-and-type
  (let [body (json/write-str {:data {:data {:value "ghp_abc"
                                            :type "string"}
                                     :metadata {:version 1}}})]
    (is (= {:value "ghp_abc" :type :string}
           (#'v/parse-kv2-response body 2)))))

(deftest parse-kv2-defaults-type-to-string
  (let [body (json/write-str {:data {:data {:value "v"}}})]
    (is (= {:value "v" :type :string}
           (#'v/parse-kv2-response body 2)))))

(deftest parse-kv1-flat-shape
  (let [body (json/write-str {:data {:value "v"}})]
    (is (= {:value "v" :type :string}
           (#'v/parse-kv2-response body 1)))))

(deftest parse-no-value-returns-nil
  (let [body (json/write-str {:data {:data {:other "field"}}})]
    (is (nil? (#'v/parse-kv2-response body 2)))))

;; ---------------------------------------------------------------------------
;; resolve! end-to-end with mocked HTTP
;; ---------------------------------------------------------------------------

(deftest resolve-200-returns-value
  (let [token-path (write-token! "test-token\n")
        b (v/make-backend {:url "https://vault" :token-path token-path})
        body (json/write-str {:data {:data {:value "secretval"
                                            :type "string"}}})]
    (with-redefs [v/send-get (fn [_cfg _tok _id] (fake-response 200 body))]
      (is (= {:value "secretval" :type :string}
             (s/resolve! b "gh-token"))))))

(deftest resolve-404-returns-nil
  (let [token-path (write-token! "test-token")
        b (v/make-backend {:url "https://vault" :token-path token-path})]
    (with-redefs [v/send-get (fn [_cfg _tok _id] (fake-response 404 "{}"))]
      (is (nil? (s/resolve! b "missing"))))))

(deftest resolve-500-returns-nil
  (let [token-path (write-token! "test-token")
        b (v/make-backend {:url "https://vault" :token-path token-path})]
    (with-redefs [v/send-get (fn [_cfg _tok _id] (fake-response 500 "boom"))]
      (is (nil? (s/resolve! b "x"))
          "non-2xx non-404 must NOT throw; backend fallback semantics"))))

(deftest resolve-missing-token-returns-nil
  (let [b (v/make-backend {:url "https://vault"
                           :token-path "/tmp/anvil-this-does-not-exist"})]
    (with-redefs [v/send-get (fn [_ _ _]
                               (throw (ex-info "should not be called" {})))]
      (is (nil? (s/resolve! b "any"))
          "missing token-file → resolve! returns nil before any HTTP call"))))

(deftest resolve-transport-error-returns-nil
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})]
    (with-redefs [v/send-get (fn [_ _ _] nil)]
      (is (nil? (s/resolve! b "x"))
          "send-get returning nil (transport fail) → resolve! returns nil"))))

;; ---------------------------------------------------------------------------
;; Constructor invariants
;; ---------------------------------------------------------------------------

(deftest make-backend-requires-url-and-token-path
  (is (thrown? clojure.lang.ExceptionInfo
               (v/make-backend {:url "https://x"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (v/make-backend {:token-path "/t"}))))

(deftest make-backend-tags-kind-vault
  (let [b (v/make-backend {:url "https://x" :token-path "/t"})]
    (is (= :vault (s/backend-kind b)))))

(deftest make-backend-defaults
  (let [b (v/make-backend {:url "https://x" :token-path "/t"})]
    (is (= "secret" (get-in b [:config :kv-mount])))
    (is (= "" (get-in b [:config :kv-path-prefix])))
    (is (= 2 (get-in b [:config :kv-version])))
    (is (= 5 (get-in b [:config :timeout-s])))))

;; ---------------------------------------------------------------------------
;; Secret-leak invariants
;; ---------------------------------------------------------------------------

(deftest resolve-result-isolates-value
  ;; A resolved secret's value lives ONLY in the returned map's :value
  ;; field. The protocol contract — exercised here so any future
  ;; refactor that smuggles the value somewhere else trips.
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})
        body (json/write-str {:data {:data {:value "leakcanary"
                                            :type "string"}}})]
    (with-redefs [v/send-get (fn [_ _ _] (fake-response 200 body))]
      (let [r (s/resolve! b "x")]
        (is (= "leakcanary" (:value r)))
        ;; Pruning :value leaves no trace of the secret anywhere.
        (is (not (some #(= "leakcanary" %)
                       (vals (dissoc r :value))))
            "secret literal must not appear in any other field of the return")))))
