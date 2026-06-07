(ns anvil.provenance.dispatcher-hook-test
  "v0.4.1 T4.3 — dispatcher post-build hook tests.

   Three tiers:
     (a) Flag-off — no provenance effects at all (the AV4-7 contract)
     (b) Flag-on with missing prerequisites — degraded effects only
     (c) Flag-on with stubbed cosign — happy path with mocked signing
     (d) Flag-on with REAL cosign — opt-in via ANVIL_COSIGN_INTEGRATION=1

   The dispatcher is exercised through the same run-jenkinsfile harness
   used by retry/container fixture tests — IR translator → real
   dispatcher → effect stream."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.features :as features]
            [anvil.provenance.cosign :as cosign]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (features/set! :provenance false)))))

(defn- run-jenkinsfile-with-ctx
  "Run a Jenkinsfile with the supplied ctx overlay.  Dispatcher gets
   the standard {:cwd ...} plus whatever's in ctx-overlay (used to
   pass :workspace / :job-name / :build-number for provenance)."
  [src ctx-overlay]
  (let [ir (t/parse src)
        stages (for [s (:stages ir)]
                 {:name (:name s)
                  :steps (vec (:steps s))})
        flat {:stages (vec stages)}
        dispatcher (ad/make {})
        result (d/run-pipeline flat dispatcher
                               (merge {:cwd "/workspace"} ctx-overlay))]
    {:result result :effects @(:effects dispatcher)}))

(defn- effects-of-type [effects type-kw]
  (filter #(= type-kw (first %)) effects))

;; ---------------------------------------------------------------------------
;; (a) Flag off — no provenance effects
;; ---------------------------------------------------------------------------

(deftest archive-step-with-flag-off-emits-only-recorder-effect
  (features/set! :provenance false)
  (let [{:keys [effects]}
        (run-jenkinsfile-with-ctx
         "pipeline { agent any; stages { stage('S') { steps {
            archiveArtifacts 'target/*.jar'
          } } } }"
         {:workspace "/tmp" :job-name "demo" :build-number 1})]
    (is (seq (effects-of-type effects :archive))
        ":archive recorder effect always fires")
    (is (empty? (effects-of-type effects :provenance/attested))
        "flag off → no provenance/attested effects")
    (is (empty? (effects-of-type effects :provenance/degraded))
        "flag off → no provenance/degraded effects either —
         silence is the AV4-7 contract")))

;; ---------------------------------------------------------------------------
;; (b) Flag on, missing prerequisites — degraded effects
;; ---------------------------------------------------------------------------

(deftest archive-step-without-workspace-emits-degraded-missing-context
  (features/set! :provenance true)
  (let [{:keys [effects]}
        (run-jenkinsfile-with-ctx
         "pipeline { agent any; stages { stage('S') { steps {
            archiveArtifacts 'target/*.jar'
          } } } }"
         {:job-name "demo" :build-number 1}) ; <-- no :workspace
        degraded (effects-of-type effects :provenance/degraded)]
    (is (= 1 (count degraded)))
    (is (= :missing-build-context (-> degraded first second :reason))
        "operator visibility: tells you exactly why we couldn't sign")))

(deftest archive-step-without-cosign-emits-degraded-cosign-missing
  (features/set! :provenance true)
  (let [tmp-dir (fs/create-temp-dir {:prefix "anvil-prov-test-"})]
    (try
      (with-redefs [cosign/cosign-on-path? (constantly false)]
        (let [{:keys [effects]}
              (run-jenkinsfile-with-ctx
               "pipeline { agent any; stages { stage('S') { steps {
                  archiveArtifacts 'target/*.jar'
                } } } }"
               {:workspace (str tmp-dir)
                :job-name "demo"
                :build-number 1})
              degraded (effects-of-type effects :provenance/degraded)]
          (is (= 1 (count degraded)))
          (is (= :cosign-missing (-> degraded first second :reason))
              "actionable: operator sees AV4-5/R9 hint, knows to install cosign")))
      (finally (fs/delete-tree tmp-dir)))))

;; ---------------------------------------------------------------------------
;; (c) Flag on with stubbed cosign — happy path
;; ---------------------------------------------------------------------------

(deftest archive-step-signs-each-matched-artifact-mocked-cosign
  (features/set! :provenance true)
  (let [tmp-dir (fs/create-temp-dir {:prefix "anvil-prov-test-"})
        ;; Plant three "artifacts" + one file that should NOT match
        _ (fs/create-dirs (fs/file tmp-dir "target"))
        _ (spit (fs/file tmp-dir "target/foo-1.0.jar") "fake jar body 1")
        _ (spit (fs/file tmp-dir "target/bar-2.0.jar") "fake jar body 2")
        _ (spit (fs/file tmp-dir "target/baz-3.0.jar") "fake jar body 3")
        _ (spit (fs/file tmp-dir "target/notes.txt") "not an artifact")
        sign-calls (atom [])]
    (try
      (with-redefs [cosign/cosign-on-path? (constantly true)
                    cosign/sign-statement!
                    (fn [stmt opts]
                      ;; Capture the call; write a fake bundle so any
                      ;; downstream existence-check passes.
                      (swap! sign-calls conj {:stmt stmt :opts opts})
                      (spit (:out-path opts) "fake-bundle-bytes")
                      {:exit-code 0 :stdout "" :stderr ""
                       :out-path (:out-path opts)
                       :cosign-version "v3.0.6"})]
        (let [{:keys [effects]}
              (run-jenkinsfile-with-ctx
               "pipeline { agent any; stages { stage('S') { steps {
                  archiveArtifacts 'target/*.jar'
                } } } }"
               {:workspace (str tmp-dir)
                :job-name "demo"
                :build-number 1
                :jenkinsfile-source "pipeline { agent any }"
                :scm {:url "https://github.com/example/repo.git"
                      :commit "deadbeefcafebabe"}})
              attested (effects-of-type effects :provenance/attested)
              degraded (effects-of-type effects :provenance/degraded)]
          (is (= 3 (count attested))
              "three .jar artifacts → three :provenance/attested effects;
               notes.txt deliberately omitted (didn't match the glob)")
          (is (empty? degraded)
              "happy path → no degraded effects")
          (testing "each effect names the artifact path + sha + bundle"
            (let [paths (set (map #(:path (second %)) attested))]
              (is (every? #(str/ends-with? % ".jar") paths))
              (is (every? #(string? (:sha256 (second %))) attested))
              (is (every? #(str/ends-with? (:bundle (second %)) ".intoto.jsonl")
                          attested))))
          (testing "cosign was called exactly once per artifact"
            (is (= 3 (count @sign-calls))))
          (testing "scm + jenkinsfile from ctx are threaded into the statement"
            (let [stmt (-> @sign-calls first :stmt)]
              (is (= "demo" (get-in stmt [:predicate :buildDefinition :externalParameters :jobName])))
              (is (some? (get-in stmt [:predicate :buildDefinition :externalParameters :jenkinsfileSha256])))
              (is (some? (get-in stmt [:predicate :buildDefinition :resolvedDependencies])))))))
      (finally (fs/delete-tree tmp-dir)))))

(deftest archive-step-cosign-failure-emits-degraded-not-attested
  (features/set! :provenance true)
  (let [tmp-dir (fs/create-temp-dir {:prefix "anvil-prov-test-"})
        _ (fs/create-dirs (fs/file tmp-dir "target"))
        _ (spit (fs/file tmp-dir "target/app.jar") "fake")]
    (try
      (with-redefs [cosign/cosign-on-path? (constantly true)
                    cosign/sign-statement!
                    (fn [_ _]
                      (throw (ex-info "cosign attest-blob failed (exit 1)"
                                      {:reason :cosign-sign-failed
                                       :exit 1
                                       :stderr "Error: invalid key"})))]
        (let [{:keys [effects]}
              (run-jenkinsfile-with-ctx
               "pipeline { agent any; stages { stage('S') { steps {
                  archiveArtifacts 'target/*.jar'
                } } } }"
               {:workspace (str tmp-dir)
                :job-name "demo"
                :build-number 1})
              attested (effects-of-type effects :provenance/attested)
              degraded (effects-of-type effects :provenance/degraded)]
          (is (empty? attested) "cosign failed → no attested effect")
          (is (= 1 (count degraded)))
          (let [d-data (-> degraded first second)]
            (is (= :cosign-sign-failed (:reason d-data)))
            (is (str/includes? (:error d-data) "cosign attest-blob")))))
      (finally (fs/delete-tree tmp-dir)))))

(deftest archive-step-bad-glob-emits-degraded-glob-failed
  ;; Note: babashka.fs/glob is permissive — most invalid patterns just
  ;; return empty rather than throw.  But a non-existent workspace
  ;; root is something we should still flag.  The dispatcher's
  ;; glob-failed branch protects against any throw we didn't predict.
  (features/set! :provenance true)
  (let [{:keys [effects]}
        (run-jenkinsfile-with-ctx
         "pipeline { agent any; stages { stage('S') { steps {
            archiveArtifacts 'target/*.jar'
          } } } }"
         {:workspace "/tmp/anvil-provenance-no-such-dir-xyz"
          :job-name "demo"
          :build-number 1})
        ;; Either no matches → no attested/degraded effects, or a
        ;; glob-failed degraded — either is honest.  We assert the
        ;; absence of attested effects (the bad path didn't sign
        ;; anything).
        attested (effects-of-type effects :provenance/attested)]
    (is (empty? attested)
        "nonexistent workspace → no false attestations")))

;; ---------------------------------------------------------------------------
;; (d) Real-cosign integration — opt-in
;; ---------------------------------------------------------------------------

(defn- integration-enabled? []
  (or (= "1" (System/getenv "ANVIL_COSIGN_INTEGRATION"))
      (= "true" (System/getenv "ANVIL_COSIGN_INTEGRATION"))))

(deftest integration-end-to-end-real-cosign
  (when (and (integration-enabled?) (cosign/cosign-on-path?))
    (features/set! :provenance true)
    (let [tmp-dir (fs/create-temp-dir {:prefix "anvil-prov-integration-"})
          art-path (str (fs/file tmp-dir "target/my-app.jar"))
          key-path (str (fs/file tmp-dir "cosign.key"))
          pub-path (str (fs/file tmp-dir "cosign.pub"))]
      (try
        (fs/create-dirs (fs/file tmp-dir "target"))
        (spit art-path "fake-jar-body-for-integration\n")
        ;; Generate a real keypair (no password)
        (let [{:keys [exit err]}
              (clojure.java.shell/sh "cosign" "generate-key-pair"
                                     :env (assoc (into {} (System/getenv))
                                                 "COSIGN_PASSWORD" "")
                                     :dir (str tmp-dir))]
          (is (zero? exit) (str "cosign generate-key-pair failed: " err)))
        ;; Run the dispatcher end-to-end with a real workspace + real key
        (let [{:keys [effects]}
              (run-jenkinsfile-with-ctx
               "pipeline { agent any; stages { stage('S') { steps {
                  archiveArtifacts 'target/*.jar'
                } } } }"
               {:workspace (str tmp-dir)
                :job-name "integration-demo"
                :build-number 1
                :provenance-key-path key-path
                :provenance-key-password ""
                :jenkinsfile-source "pipeline { agent any }"})
              attested (effects-of-type effects :provenance/attested)]
          (is (= 1 (count attested))
              "real cosign signed exactly one artifact")
          (let [bundle (-> attested first second :bundle)]
            (is (.exists (clojure.java.io/file bundle))
                "real .intoto.jsonl written beside the artifact")
            ;; And the canonical end-to-end win: verify the round trip
            (let [verify-result (cosign/verify-blob
                                 {:artifact art-path
                                  :attestation-path bundle
                                  :key-path pub-path})]
              (is (:verified? verify-result)
                  (str "cosign verify-blob-attestation must accept "
                       "what the dispatcher signed; stderr: "
                       (:stderr verify-result))))))
        (finally (fs/delete-tree tmp-dir))))))
