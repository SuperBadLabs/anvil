(ns anvil.compat.jenkins.file-credentials-test
  "AN7-3 — :file-type credential support.

   Tests verify:
   1. build-docker-args appends -v :ro flags for file-mounts
   2. :file cred resolves → file-mounts + env-var → container-path
   3. :file credentials do NOT get values added to the secrets masker
   4. :credential-unresolved is still emitted when :file cred is missing
   5. :file-credential/mounted effect is emitted on resolution"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as dispatcher]
            [anvil.storage.db :as db]
            [anvil.storage.credentials :as creds])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(def ^:dynamic *db-dir* nil)

(use-fixtures :each
  (fn [f]
    (let [d (temp-dir "anvil-file-creds-test-")
          path (str d "/test.db")]
      (binding [*db-dir* d]
        (try
          (db/init! path)
          (f)
          (finally
            (db/close!)
            (rm-rf d)))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- effects [disp]
  @(:effects disp))

(defn- find-effects [disp kw]
  (filterv #(= kw (first %)) (effects disp)))

(defn- find-effect [disp kw]
  (first (find-effects disp kw)))

;; ---------------------------------------------------------------------------
;; build-docker-args — pure function tests (no DB / dispatcher needed)
;; ---------------------------------------------------------------------------

(deftest build-docker-args-no-file-mounts
  (testing "without file-mounts, only workspace -v flag is present"
    (let [argv (dispatcher/build-docker-args
                {:image "eclipse-temurin:21" :extra-args nil}
                "gpg --import $KEY"
                "/workspace"
                {})]
      (is (vector? argv))
      (is (some #{"eclipse-temurin:21"} argv))
      (let [v-pairs (->> (partition 2 1 argv)
                         (filter #(= "-v" (first %)))
                         (mapv second))]
        (is (= 1 (count v-pairs)) "exactly one -v flag (workspace)")
        (is (re-find #"/workspace:/workspace" (first v-pairs)))))))

(deftest build-docker-args-with-file-mounts
  (testing "file-mounts add -v :ro flags to docker args"
    (let [mounts [{:host-path "/run/secrets/keyring.asc"
                   :container-path "/anvil-creds/jkube-gpg-key"}
                  {:host-path "/run/secrets/tls.pem"
                   :container-path "/anvil-creds/tls-cert"}]
          argv (dispatcher/build-docker-args
                {:image "eclipse-temurin:21" :extra-args nil}
                "gpg --import $KEY"
                "/workspace"
                {}
                mounts)]
      (is (vector? argv))
      (let [v-pairs (->> (partition 2 1 argv)
                         (filter #(= "-v" (first %)))
                         (mapv second))]
        (is (= 3 (count v-pairs)) "workspace + 2 file mounts = 3 -v flags")
        (is (some #(= "/run/secrets/keyring.asc:/anvil-creds/jkube-gpg-key:ro" %) v-pairs)
            "file mount has :ro suffix")
        (is (some #(= "/run/secrets/tls.pem:/anvil-creds/tls-cert:ro" %) v-pairs))))))

(deftest build-docker-args-nil-file-mounts-same-as-no-arg
  (testing "nil file-mounts is equivalent to omitting the argument"
    (let [a (dispatcher/build-docker-args
             {:image "img:latest" :extra-args nil}
             "echo hi" "/ws" {} nil)
          b (dispatcher/build-docker-args
             {:image "img:latest" :extra-args nil}
             "echo hi" "/ws" {})]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; :file credential resolved → :file-credential/mounted effect
;; ---------------------------------------------------------------------------

(deftest file-credential-resolved-emits-mount-effect
  (testing ":file cred resolves from store → :file-credential/mounted effect emitted"
    ;; Write a real temp file to use as the credential path.
    (let [tmp (java.io.File/createTempFile "anvil-gpg-test" ".asc")]
      (try
        (spit tmp "GPG KEY DATA")
        (creds/add! {:id "jkube-gpg-key"
                     :type :file
                     :value (str tmp)
                     :description "test gpg key"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'jkube-gpg-key', variable: 'GPG_KEY_FILE'"}]
                    :body [{:type :jenkins/sh :script "gpg --import \"$GPG_KEY_FILE\""}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})]
          ;; :credential-unresolved must NOT be emitted
          (is (empty? (find-effects disp :credential-unresolved))
              "resolved :file cred must NOT emit :credential-unresolved")
          ;; :file-credential/mounted MUST be emitted
          (let [mount-eff (find-effect disp :file-credential/mounted)]
            (is (some? mount-eff) ":file-credential/mounted effect must be emitted")
            (is (= "jkube-gpg-key" (-> mount-eff second :credential-id)))
            (is (= (str tmp) (-> mount-eff second :host-path))
                "host-path in effect matches what was registered")
            (is (= "/anvil-creds/jkube-gpg-key" (-> mount-eff second :container-path))
                "container path is /anvil-creds/<credential-id>")
            (is (= "GPG_KEY_FILE" (-> mount-eff second :var-name))
                "var-name matches the `variable:` binding")))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; :file credential unresolved → :credential-unresolved (AN4-4)
;; ---------------------------------------------------------------------------

(deftest file-credential-unresolved-emits-credential-unresolved
  (testing ":file cred missing from store → :credential-unresolved per AN4-4"
    ;; Do NOT add the credential; store is empty after fixture setup.
    (let [disp (dispatcher/make)
          step {:type :jenkins/with-credentials
                :credentials [{:kind "file"
                               :raw-args "credentialsId: 'missing-key', variable: 'KEY'"}]
                :body []}
          _r (d/dispatch disp step {})]
      (let [unresolved (find-effects disp :credential-unresolved)]
        (is (= 1 (count unresolved)))
        (is (= "missing-key" (-> unresolved first second :credential-id)))))))

;; ---------------------------------------------------------------------------
;; :file credential host-path NOT in secrets masker
;; ---------------------------------------------------------------------------

(deftest file-credential-host-path-not-in-secrets-masker
  (testing ":file cred host-path is NOT added to the secrets masker (it's a path)"
    (let [tmp (java.io.File/createTempFile "anvil-mask-test" ".key")]
      (try
        (spit tmp "KEY DATA")
        (creds/add! {:id "test-key"
                     :type :file
                     :value (str tmp)
                     :description "test"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'test-key', variable: 'KEY_FILE'"}]
                    :body [{:type :jenkins/sh :script "echo done"}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})]
          (is (not (contains? @(:secrets disp) (str tmp)))
              "host file path must NOT be in the secrets masker"))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; :file credential enter-effect reports file-credential-count
;; ---------------------------------------------------------------------------

(deftest file-credential-enter-effect-counts-file-creds
  (testing ":with-credentials/enter effect includes :file-credential-count"
    (let [tmp (java.io.File/createTempFile "anvil-enter-test" ".pem")]
      (try
        (spit tmp "TLS CERT DATA")
        (creds/add! {:id "my-cert"
                     :type :file
                     :value (str tmp)
                     :description "test cert"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'my-cert', variable: 'TLS_CERT'"}]
                    :body [{:type :jenkins/sh :script "cat $TLS_CERT"}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})
              enter-eff (find-effect disp :with-credentials/enter)]
          (is (some? enter-eff) ":with-credentials/enter must be emitted")
          (is (= 1 (-> enter-eff second :file-credential-count))
              "enter effect reports 1 file credential mounted"))
        (finally (.delete tmp))))))
