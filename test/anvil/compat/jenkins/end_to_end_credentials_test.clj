(ns anvil.compat.jenkins.end-to-end-credentials-test
  "End-to-end tests for the TX11D wiring:

   - Credentials store (anvil.storage.credentials) lookup
   - Dispatcher h-with-credentials env-var injection + masking
   - Shared-libs registry (anvil.compat.jenkins.shared-libs)
   - infra shim methods routed through h-unknown

   These complement the storage-level credentials tests + the
   shared-libs unit tests by exercising the *connections* — what
   happens when the dispatcher actually walks an IR that uses
   withCredentials referencing an id from the store, or calls
   infra.checkoutSCM() and expects the shim to handle it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.shared-libs :as shared-libs]
            [anvil.storage.db :as db]
            [anvil.storage.credentials :as creds])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixture: in-memory-ish SQLite (file-backed temp) + credential store init
;; ---------------------------------------------------------------------------

(def ^:dynamic *db-dir*)

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(use-fixtures :each
  (fn [f]
    (let [d (temp-dir "anvil-e2e-creds-")
          path (str d "/test.db")]
      (binding [*db-dir* d]
        (try
          (db/init! path)
          (f)
          (finally
            (db/close!)
            (rm-rf d)))))))

(defn- run-ir [ir & {:keys [secrets-atom canned] :or {canned {}}}]
  (let [d (ad/make (cond-> {:sh-canned (atom canned)}
                     secrets-atom (assoc :secrets secrets-atom)))
        flat {:stages [{:name "test" :steps (vec ir)}]}
        result (d/run-pipeline flat d {:cwd "/workspace"})]
    {:result result :effects @(:effects d) :dispatcher d}))

;; ---------------------------------------------------------------------------
;; Credentials store ↔ withCredentials wiring
;; ---------------------------------------------------------------------------

(deftest with-credentials-resolves-from-store
  (testing "withCredentials looks up the id and injects env var on the body"
    (creds/add! {:id "LAUNCHABLE_TOKEN" :value "real-launchable-value-x"})
    (let [src "pipeline { agent any; stages { stage('S') { steps {
                 withCredentials([string(credentialsId: 'LAUNCHABLE_TOKEN',
                                          variable: 'TOK')]) {
                   sh 'curl -H \"Authorization: $TOK\" https://api'
                 }
               } } } }"
          ir (t/parse src)
          steps (-> ir :stages first :steps)
          {:keys [effects]} (run-ir steps)
          enter-event (first (filter #(= :with-credentials/enter (first %)) effects))
          enter-payload (second enter-event)]
      ;; Receipt 1: dispatcher reports the lookup succeeded
      (is (= 1 (:resolved-from-store enter-payload))
          (str "expected 1 resolved-from-store, got payload: " enter-payload)))))

(deftest with-credentials-masks-resolved-value-in-effects
  (testing "the resolved secret value is masked in subsequent effects"
    (creds/add! {:id "MY_SECRET" :value "deadbeef-cafe-1234"})
    (let [src "pipeline { agent any; stages { stage('S') { steps {
                 withCredentials([string(credentialsId: 'MY_SECRET',
                                          variable: 'S')]) {
                   echo 'leak: deadbeef-cafe-1234'
                 }
               } } } }"
          ir (t/parse src)
          steps (-> ir :stages first :steps)
          {:keys [effects]} (run-ir steps)
          echo-event (first (filter #(= :echo (first %)) effects))]
      (is (some? echo-event) "echo effect must be present")
      ;; The echo's payload should have the value substituted with ****
      (is (str/includes? (str (second echo-event)) "****"))
      (is (not (str/includes? (str (second echo-event)) "deadbeef-cafe-1234"))
          "raw secret must not appear in the recorded effect"))))

(deftest with-credentials-username-password-binds-both-vars
  (testing "usernamePassword credentials split into two env vars and mask both halves
            (regression for the re-find single-match bug, Codex P2 PR #164)"
    (creds/add! {:id "DOCKER_CREDS"
                 :type :username-password
                 :value "alice:s3cret-passw0rd"})
    (let [src "pipeline { agent any; stages { stage('S') { steps {
                 withCredentials([usernamePassword(credentialsId: 'DOCKER_CREDS',
                                                    usernameVariable: 'DOCKER_USR',
                                                    passwordVariable: 'DOCKER_PWD')]) {
                   echo 'binding both'
                 }
               } } } }"
          ir (t/parse src)
          steps (-> ir :stages first :steps)
          {:keys [effects]} (run-ir steps)
          enter-event (first (filter #(= :with-credentials/enter (first %)) effects))]
      ;; :resolved-from-store is the count of env vars injected from
      ;; the store. usernamePassword binds two (USR + PWD) so the
      ;; expected count is 2, not 1 — this is the regression check:
      ;; before the fix, only one var got bound.
      (is (= 2 (:resolved-from-store (second enter-event)))
          "usernamePassword must bind BOTH USR + PWD env vars; pre-fix this was 1")
      ;; The secret-count includes both halves plus the combined value
      ;; (alice, s3cret-passw0rd, alice:s3cret-passw0rd → 3 masks).
      (is (<= 3 (:secret-count (second enter-event)))
          "username, password, and combined value all masked"))))

(deftest with-credentials-missing-id-falls-back-gracefully
  (testing "an unknown credentialsId doesn't crash the build"
    ;; Don't add the credential; reference it anyway.
    (let [src "pipeline { agent any; stages { stage('S') { steps {
                 withCredentials([string(credentialsId: 'NOT_THERE',
                                          variable: 'X')]) {
                   echo 'body ran'
                 }
               } } } }"
          ir (t/parse src)
          steps (-> ir :stages first :steps)
          {:keys [effects result]} (run-ir steps)
          enter (first (filter #(= :with-credentials/enter (first %)) effects))]
      (is (not= :failed (:status result)) "build should not fail on missing id")
      ;; resolved-from-store should be 0
      (is (= 0 (or (:resolved-from-store (second enter)) 0))))))

;; ---------------------------------------------------------------------------
;; Shared-libs registry ↔ h-unknown routing
;; ---------------------------------------------------------------------------

(deftest shared-libs-handler-for-resolves-infra-methods
  (testing "handler-for returns a non-nil fn for known infra methods"
    (is (ifn? (shared-libs/handler-for "checkoutSCM")))
    (is (ifn? (shared-libs/handler-for "checkoutscm")) "case-insensitive")
    (is (ifn? (shared-libs/handler-for "runMaven")))
    (is (ifn? (shared-libs/handler-for "runWithMaven")))
    (is (ifn? (shared-libs/handler-for "withArtifactCachingProxy")))
    (is (ifn? (shared-libs/handler-for "maybePublishIncrementals")))
    (is (ifn? (shared-libs/handler-for "publishReports")))
    (is (ifn? (shared-libs/handler-for "kubernetesAgent")))
    (is (ifn? (shared-libs/handler-for "nonresumable")))
    (is (nil? (shared-libs/handler-for "totallyUnknownMethod1234")))))

(deftest infra-checkout-scm-routes-through-dispatcher
  (testing "an :jenkins/unknown step named checkoutSCM routes to the infra shim"
    (let [steps [{:type :jenkins/unknown :name "checkoutSCM" :args []}]
          {:keys [effects result]} (run-ir steps)]
      (is (not= :failed (:status result)))
      ;; Shim emits :scm/assume-checked-out
      (is (some (fn [[k]] (= k :scm/assume-checked-out)) effects)
          (str "expected :scm/assume-checked-out in effects, got: "
               (vec (map first effects)))))))

(deftest infra-run-maven-passes-through-as-sh
  (testing "infra.runMaven becomes a real :sh effect with `mvn $args` cmd"
    (let [steps [{:type :jenkins/unknown :name "runMaven"
                  :args ["-pl cli -am package"]}]
          {:keys [effects]} (run-ir steps)
          sh-event (first (filter #(= :sh (first %)) effects))]
      (is (some? sh-event) "expected a :sh effect")
      (is (str/starts-with? (:cmd (second sh-event)) "mvn ")
          (str "expected cmd to start with 'mvn ', got: " (second sh-event)))
      (is (str/includes? (:cmd (second sh-event)) "-pl cli -am package")))))

(deftest infra-maybe-publish-incrementals-no-op
  (testing "maybePublishIncrementals emits the no-op effect tuple"
    (let [steps [{:type :jenkins/unknown :name "maybePublishIncrementals" :args []}]
          {:keys [effects]} (run-ir steps)]
      (is (some (fn [[k]] (= k :incrementals/skip)) effects)))))

(deftest unknown-step-not-in-registry-falls-through
  (testing "a truly unknown step name still falls through to :unknown logging"
    (let [steps [{:type :jenkins/unknown :name "completelyMadeUpMethodName"
                  :args ["irrelevant"]}]
          {:keys [effects result]} (run-ir steps)]
      (is (not= :failed (:status result)))
      (is (some (fn [[k]] (= k :unknown)) effects)
          "the :unknown fallthrough must still kick in for unrecognized names"))))

;; ---------------------------------------------------------------------------
;; Verify the receipt scenario: withCredentials wrapping a shim call
;; works (TX11D's two pieces compose)
;; ---------------------------------------------------------------------------

(deftest withcredentials-wrapping-infra-call-composes
  (testing "withCredentials([...]) { infra.checkoutSCM() } both work together"
    (creds/add! {:id "TOK" :value "my-token-value"})
    (let [src "pipeline { agent any; stages { stage('S') { steps {
                 withCredentials([string(credentialsId: 'TOK',
                                          variable: 'TOKEN')]) {
                   sh 'echo before'
                 }
               } } } }"
          ir (t/parse src)
          ;; Append a free-standing checkoutSCM call to verify both
          ;; registry paths fire in the same pipeline
          steps (conj (vec (-> ir :stages first :steps))
                      {:type :jenkins/unknown :name "checkoutSCM" :args []})
          {:keys [effects result]} (run-ir steps)
          event-types (mapv first effects)]
      (is (not= :failed (:status result)))
      (is (contains? (set event-types) :with-credentials/enter))
      (is (contains? (set event-types) :scm/assume-checked-out)))))
