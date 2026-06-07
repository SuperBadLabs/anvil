(ns anvil.provenance.statement-test
  "v0.4.1 T4.1 — tests for the in-toto v1 statement builder.

   Pure data — no cosign, no files (the file-hash helper is tested
   in cosign_test).  Asserts shape conformance to the SLSA
   provenance/v1 spec; cosign will reject any drift."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [anvil.provenance.statement :as stmt]))

(def ^:private fixture-artifact
  {:name "my-app-1.2.3.jar"
   :sha256 "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"})

(def ^:private fixture-opts
  {:job-name "demo"
   :build-number 42
   :artifacts [fixture-artifact]
   :scm {:url "https://github.com/example/repo.git"
         :commit "deadbeefcafebabe1234567890abcdef12345678"}
   :jenkinsfile "pipeline { agent any; stages { stage('B') { steps { sh 'make' } } } }"
   :started-at "2026-06-07T22:00:00Z"
   :finished-at "2026-06-07T22:00:42Z"})

;; ---------------------------------------------------------------------------
;; SHA-256 helpers
;; ---------------------------------------------------------------------------

(deftest sha256-hex-known-vector
  (testing "RFC 6234 test vector — 'abc' SHA-256"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (stmt/sha256-of-string "abc")))))

(deftest sha256-of-string-handles-utf8
  (testing "non-ASCII string hashed via UTF-8 bytes"
    (let [h1 (stmt/sha256-of-string "café")
          h2 (stmt/sha256-of-string "café")]
      (is (= 64 (count h1)))
      (is (= h1 h2) "deterministic"))))

;; ---------------------------------------------------------------------------
;; build-statement — shape conformance
;; ---------------------------------------------------------------------------

(deftest statement-has-required-top-level-fields
  (let [s (stmt/build-statement fixture-opts)]
    (is (= "https://in-toto.io/Statement/v1" (:_type s)))
    (is (= "https://slsa.dev/provenance/v1" (:predicateType s)))
    (is (sequential? (:subject s)))
    (is (map? (:predicate s)))))

(deftest subject-pulls-name-and-digest-from-artifacts
  (let [s (stmt/build-statement fixture-opts)
        subj (first (:subject s))]
    (is (= "my-app-1.2.3.jar" (:name subj)))
    (is (= "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"
           (-> subj :digest :sha256)))))

(deftest multi-artifact-statement-has-one-subject-per-artifact
  (let [s (stmt/build-statement
           (assoc fixture-opts
                  :artifacts [{:name "a.jar" :sha256 (apply str (repeat 64 \a))}
                              {:name "b.jar" :sha256 (apply str (repeat 64 \b))}
                              {:name "c.tar.gz" :sha256 (apply str (repeat 64 \c))}]))]
    (is (= 3 (count (:subject s))))
    (is (= ["a.jar" "b.jar" "c.tar.gz"]
           (mapv :name (:subject s))))))

(deftest builder-id-includes-anvil-version
  (let [s (stmt/build-statement fixture-opts)
        bid (get-in s [:predicate :runDetails :builder :id])]
    (is (str/starts-with? bid "https://anvil.superbadlabs.dev/builder/"))
    (is (re-find #"\d+\.\d+" bid)
        "builder id encodes the anvil version — verifiers use this
         to pin/exclude attestations by anvil version")))

(deftest external-parameters-include-job-and-jenkinsfile-hash
  (let [s (stmt/build-statement fixture-opts)
        ext (get-in s [:predicate :buildDefinition :externalParameters])]
    (is (= "demo" (:jobName ext)))
    (is (= 42 (:buildNumber ext)))
    (is (= 64 (count (:jenkinsfileSha256 ext)))
        "Jenkinsfile hash present and is a hex SHA-256")
    (testing "Jenkinsfile content is NOT in the statement (only its hash)"
      (let [serialized (json/write-str s)]
        (is (not (str/includes? serialized "pipeline { agent any"))
            "the literal Jenkinsfile text never appears in the attestation —
             verifiers compare hashes, the source itself stays out of the chain")))))

(deftest resolved-dependencies-includes-scm-when-provided
  (let [s (stmt/build-statement fixture-opts)
        deps (get-in s [:predicate :buildDefinition :resolvedDependencies])]
    (is (= 1 (count deps)))
    (is (= "git+https://github.com/example/repo.git@deadbeefcafebabe1234567890abcdef12345678"
           (:uri (first deps))))
    (is (= "deadbeefcafebabe1234567890abcdef12345678"
           (-> deps first :digest :sha1)))))

(deftest scm-omitted-when-not-provided
  (let [s (stmt/build-statement (dissoc fixture-opts :scm))]
    (is (not (contains? (get-in s [:predicate :buildDefinition])
                        :resolvedDependencies))
        "no SCM → no resolvedDependencies key (rather than empty array;
         keeps the attestation honest about what we know)")))

(deftest metadata-carries-timestamps-and-invocation-id
  (let [s (stmt/build-statement fixture-opts)
        meta (get-in s [:predicate :runDetails :metadata])]
    (is (= "demo#42" (:invocationId meta)))
    (is (= "2026-06-07T22:00:00Z" (:startedOn meta)))
    (is (= "2026-06-07T22:00:42Z" (:finishedOn meta)))))

(deftest invocation-id-overridable
  (let [s (stmt/build-statement (assoc fixture-opts :invocation-id "custom-id-123"))]
    (is (= "custom-id-123"
           (get-in s [:predicate :runDetails :metadata :invocationId])))))

(deftest extras-merge-into-predicate
  (let [s (stmt/build-statement (assoc fixture-opts :extras
                                       {:customField "value"}))]
    (is (= "value" (get-in s [:predicate :customField])))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest build-statement-rejects-missing-required
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":job-name and :build-number are required"
                        (stmt/build-statement {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":artifacts must be a non-empty"
                        (stmt/build-statement {:job-name "x" :build-number 1}))))

(deftest build-statement-rejects-malformed-artifact-entry
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":name \+ :sha256"
                        (stmt/build-statement
                         (assoc fixture-opts
                                :artifacts [{:name "x.jar"}])))))

;; ---------------------------------------------------------------------------
;; JSON serialization — the actual wire shape cosign consumes
;; ---------------------------------------------------------------------------

(deftest statement-round-trips-through-json
  (let [s (stmt/build-statement fixture-opts)
        s-back (json/read-str (json/write-str s) :key-fn keyword)]
    (is (= (:_type s) (:_type s-back)))
    (is (= (:predicateType s) (:predicateType s-back)))
    (is (= (count (:subject s)) (count (:subject s-back))))
    (is (= (-> s :subject first :digest :sha256)
           (-> s-back :subject first :digest :sha256)))))
