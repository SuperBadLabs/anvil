(ns anvil.provenance.cosign-test
  "v0.4.1 T4.2 + T4.6 — tests for the cosign shell-out wrapper.

   Two tiers:
     (a) Hermetic tests — argv-validation, missing-binary error path,
         file I/O checks.  Always run.
     (b) Integration test — real cosign sign + verify round-trip using
         a generated local keypair.  Gated by ANVIL_COSIGN_INTEGRATION=1
         env var since we can't assume cosign is on PATH in every CI
         runner.  (Locally cosign IS available — see the integration
         deftest below.)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [babashka.fs :as fs]
            [anvil.provenance.cosign :as cosign]
            [anvil.provenance.statement :as stmt]))

(defn- integration-enabled? []
  (or (= "1" (System/getenv "ANVIL_COSIGN_INTEGRATION"))
      (= "true" (System/getenv "ANVIL_COSIGN_INTEGRATION"))))

;; ---------------------------------------------------------------------------
;; Hermetic — cosign-on-path? + missing-binary path
;; ---------------------------------------------------------------------------

(deftest cosign-on-path-returns-boolean
  (testing "cosign-on-path? returns boolean either way"
    (is (boolean? (cosign/cosign-on-path?))
        "no exception, no nil — just true/false")))

(deftest sign-without-cosign-throws-actionable-error
  (testing "when cosign is missing, sign-statement! raises ex-info with :fix hint"
    (with-redefs [cosign/cosign-on-path? (constantly false)]
      (let [thrown (try
                     (cosign/sign-statement! {} {:out-path "/tmp/x.intoto.jsonl"
                                                 :artifact "/tmp/x.jar"})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is thrown "missing cosign must throw, never silently no-op")
        (is (= :cosign-missing (:reason (ex-data thrown))))
        (is (re-find #"cosign" (ex-message thrown)))
        (is (some? (:fix (ex-data thrown)))
            "operator gets an actionable fix string, not just an error")))))

(deftest verify-without-cosign-throws-actionable-error
  (with-redefs [cosign/cosign-on-path? (constantly false)]
    (let [thrown (try
                   (cosign/verify-blob {:artifact "/tmp/x.jar"
                                        :attestation-path "/tmp/x.jar.intoto.jsonl"})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is thrown)
      (is (= :cosign-missing (:reason (ex-data thrown)))))))

;; ---------------------------------------------------------------------------
;; Hermetic — argv + file validation
;; ---------------------------------------------------------------------------

(deftest sign-requires-out-path-and-artifact
  (with-redefs [cosign/cosign-on-path? (constantly true)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":out-path and :artifact are required"
                          (cosign/sign-statement! {} {:out-path "/tmp/x"})))))

(deftest sign-throws-when-artifact-missing
  (with-redefs [cosign/cosign-on-path? (constantly true)]
    (let [thrown (try (cosign/sign-statement! {} {:out-path "/tmp/x"
                                                  :artifact "/tmp/does-not-exist"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is thrown)
      (is (= :artifact-missing (:reason (ex-data thrown)))))))

(deftest verify-throws-when-attestation-missing
  (let [tmp (fs/create-temp-file {:suffix ".jar"})]
    (try
      (spit (fs/file tmp) "fake artifact")
      (with-redefs [cosign/cosign-on-path? (constantly true)]
        (let [thrown (try (cosign/verify-blob
                           {:artifact (str tmp)
                            :attestation-path "/tmp/does-not-exist.intoto.jsonl"})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is thrown)
          (is (= :attestation-missing (:reason (ex-data thrown))))))
      (finally (fs/delete-if-exists tmp)))))

(deftest sibling-attestation-path-appends-suffix
  (is (= "/tmp/foo.jar.intoto.jsonl"
         (cosign/sibling-attestation-path "/tmp/foo.jar")))
  (is (= "build/dist/anvil-0.4.1.tar.gz.intoto.jsonl"
         (cosign/sibling-attestation-path "build/dist/anvil-0.4.1.tar.gz"))))

;; ---------------------------------------------------------------------------
;; Integration — opt-in via ANVIL_COSIGN_INTEGRATION=1
;; Generates a local cosign keypair, signs an artifact, verifies it,
;; then tampers with the artifact and confirms verify rejects.
;; ---------------------------------------------------------------------------

(deftest integration-real-cosign-round-trip
  (when (integration-enabled?)
    (when (cosign/cosign-on-path?)
      (let [tmp-dir (fs/create-temp-dir {:prefix "anvil-cosign-test-"})
            artifact-path (str (fs/file tmp-dir "my-app.jar"))
            attestation-path (cosign/sibling-attestation-path artifact-path)
            key-path (str (fs/file tmp-dir "cosign.key"))
            pub-path (str (fs/file tmp-dir "cosign.pub"))
            statement (stmt/build-statement
                       {:job-name "demo"
                        :build-number 1
                        :artifacts [{:name "my-app.jar"
                                     :sha256 "placeholder-replaced-after-write"}]
                        :scm {:url "https://github.com/example/repo.git"
                              :commit "deadbeef"}
                        :started-at "2026-06-07T22:00:00Z"
                        :finished-at "2026-06-07T22:00:30Z"})]
        (try
          ;; 1. Write a fake artifact
          (spit artifact-path "fake artifact bytes\n")
          (testing "generate a local cosign keypair (no password)"
            (let [{:keys [exit err]}
                  (sh/sh "cosign" "generate-key-pair"
                         :env (assoc (into {} (System/getenv))
                                     "COSIGN_PASSWORD" "")
                         :dir (str tmp-dir))]
              (is (zero? exit) (str "cosign generate-key-pair failed: " err))))
          (testing "real artifact sha is bound by cosign — re-build statement with the actual hash"
            (let [actual-sha (stmt/sha256-of-file artifact-path)
                  statement (stmt/build-statement
                             {:job-name "demo"
                              :build-number 1
                              :artifacts [{:name "my-app.jar" :sha256 actual-sha}]
                              :started-at "2026-06-07T22:00:00Z"
                              :finished-at "2026-06-07T22:00:30Z"})
                  sign-result (cosign/sign-statement!
                               statement
                               {:out-path attestation-path
                                :artifact artifact-path
                                :key-path key-path
                                :extra-env {"COSIGN_PASSWORD" ""}})]
              (is (zero? (:exit-code sign-result)))
              (is (.exists (io/file attestation-path))
                  "attestation written beside the artifact")))
          (testing "verify against the generated public key returns verified?=true"
            (let [verify-result (cosign/verify-blob
                                 {:artifact artifact-path
                                  :attestation-path attestation-path
                                  :key-path pub-path})]
              (is (:verified? verify-result)
                  (str "expected verified true; cosign stderr: "
                       (:stderr verify-result)))))
          (testing "tamper detection: modify artifact, verify fails"
            (spit artifact-path "tampered artifact bytes\n")
            (let [verify-result (cosign/verify-blob
                                 {:artifact artifact-path
                                  :attestation-path attestation-path
                                  :key-path pub-path})]
              (is (false? (:verified? verify-result))
                  "tampered artifact MUST be rejected — this is the
                   whole point of provenance")))
          (finally
            (fs/delete-tree tmp-dir)))))))
