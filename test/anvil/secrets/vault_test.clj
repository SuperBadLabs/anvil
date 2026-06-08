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
;; v0.6.2 security — path-traversal + URL-encoding invariants
;;
;; Without these guards, a credentialsId of "../../../sys/wrapping/unwrap"
;; interpolates verbatim into the URL and reaches Vault's sys/* endpoints.
;; The status-code response (403 vs 404 vs 200) leaks path existence even
;; when the request is denied.
;; ---------------------------------------------------------------------------

(deftest resolve-rejects-traversal-id-without-http
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})
        called? (atom false)]
    (with-redefs [v/send-get (fn [_ _ _]
                               (reset! called? true)
                               (throw (ex-info "MUST NOT be called for invalid id" {})))]
      (is (nil? (s/resolve! b "../../../sys/wrapping/unwrap"))
          "traversal id → resolve! returns nil (treated as unresolved)")
      (is (false? @called?)
          "send-get MUST NOT be invoked for a traversal-shaped id"))))

(deftest resolve-rejects-leading-slash-id
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})
        called? (atom false)]
    (with-redefs [v/send-get (fn [_ _ _]
                               (reset! called? true)
                               (throw (ex-info "MUST NOT be called" {})))]
      (is (nil? (s/resolve! b "/etc/passwd")))
      (is (false? @called?)))))

(deftest resolve-rejects-nul-byte-id
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})
        called? (atom false)]
    (with-redefs [v/send-get (fn [_ _ _]
                               (reset! called? true)
                               (throw (ex-info "MUST NOT be called" {})))]
      (is (nil? (s/resolve! b (str "gh" (char 0) "token"))))
      (is (false? @called?)))))

(deftest resolve-rejects-blank-and-nil-id
  (let [token-path (write-token! "tok")
        b (v/make-backend {:url "https://vault" :token-path token-path})]
    (with-redefs [v/send-get (fn [_ _ _]
                               (throw (ex-info "MUST NOT be called" {})))]
      (is (nil? (s/resolve! b "")))
      (is (nil? (s/resolve! b nil))))))

(deftest read-url-encodes-url-unsafe-chars
  (let [cfg {:url "https://vault" :kv-mount "secret"
             :kv-path-prefix "" :kv-version 2}]
    (is (= "https://vault/v1/secret/data/gh%20token"
           (#'v/read-url cfg "gh token"))
        "spaces become %20 (not the '+' URLEncoder emits by default)")
    (is (= "https://vault/v1/secret/data/a%2Bb"
           (#'v/read-url cfg "a+b"))
        "'+' is percent-encoded so it can't be re-read as a space")
    (is (= "https://vault/v1/secret/data/a%25b"
           (#'v/read-url cfg "a%b"))
        "'%' is encoded to %25 so the id can't smuggle in pre-encoded sequences")
    (is (= "https://vault/v1/secret/data/a%23b"
           (#'v/read-url cfg "a#b"))
        "'#' (URL fragment delimiter) is percent-encoded")
    (is (= "https://vault/v1/secret/data/a%3Fb"
           (#'v/read-url cfg "a?b"))
        "'?' (query-string delimiter) is percent-encoded")))

(deftest read-url-preserves-multi-segment-ids
  (let [cfg {:url "https://vault" :kv-mount "secret"
             :kv-path-prefix "" :kv-version 2}]
    (is (= "https://vault/v1/secret/data/team-a/gh-token"
           (#'v/read-url cfg "team-a/gh-token"))
        "single-`/` between segments is preserved (idiomatic prefix shape)")
    (is (= "https://vault/v1/secret/data/team%20a/gh%20token"
           (#'v/read-url cfg "team a/gh token"))
        "each segment is encoded independently — `/` separator survives")))

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
