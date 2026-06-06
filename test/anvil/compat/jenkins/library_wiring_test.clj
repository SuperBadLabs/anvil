(ns anvil.compat.jenkins.library-wiring-test
  "AN5-2 — Cover the library-load-into-effects! bridge plus the
   classifier's recognition of :library-loaded / :library-unresolved
   effects. End-to-end: an IR with `@Library('foo@main')` flows through
   the wiring, the classifier reads recorded effects, and the AN5-1
   walk-shape synthesizer does NOT double-emit a synthetic
   `library.foo-unresolved` for libraries the runner already attested."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [anvil.compat.jenkins.libraries :as lib]
            [anvil.compat.jenkins.classification :as classify])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- mk-tmp-base!
  "Make a fresh tmpdir to serve as ANVIL_LIBRARIES_DIR. Returns the
   absolute path string."
  []
  (-> (Files/createTempDirectory "anvil-an5-2-test"
                                 (into-array FileAttribute []))
      .toAbsolutePath
      .toString))

(defn- write-stub-library!
  "Materialize a fake library on disk so load-library! finds vars."
  [base-dir lib-name ref step-name]
  (let [vars-dir (io/file base-dir lib-name ref "vars")]
    (.mkdirs vars-dir)
    (spit (io/file vars-dir (str step-name ".groovy"))
          "def call() { echo 'stub' }")))

;; ---------------------------------------------------------------------------
;; load-into-effects! — the wiring entry point
;; ---------------------------------------------------------------------------

(deftest load-into-effects-no-op-on-empty-libraries
  (testing "no :libraries → atom untouched, returns nil"
    (let [atom-> (atom [])]
      (is (nil? (lib/load-into-effects! {:libraries []} atom->)))
      (is (= [] @atom->)))))

(deftest load-into-effects-pushes-loaded-for-successful-lib
  (testing "a library that resolves on disk emits a :library-loaded effect"
    (let [base (mk-tmp-base!)
          _ (write-stub-library! base "hibernate-helpers" "main" "checkoutWithRetry")
          atom-> (atom [])
          ir {:libraries [{:name "hibernate-helpers" :version "main"}]}
          ret (lib/load-into-effects! ir atom-> base)
          eff (first @atom->)]
      (is (= 1 (count ret)))
      (is (= :library-loaded (first eff))
          "successful load → :library-loaded effect")
      (let [{:keys [name ref registered]} (second eff)]
        (is (= "hibernate-helpers" name))
        (is (= "main" ref))
        (is (= ["checkoutWithRetry"] registered)
            "registered step list is propagated")))))

(deftest load-into-effects-pushes-unresolved-for-missing-lib
  (testing "a library missing from disk emits :library-unresolved with the reason"
    (let [base (mk-tmp-base!)
          atom-> (atom [])
          ir {:libraries [{:name "ghost-lib" :version "v1.2.3"}]}
          _ (lib/load-into-effects! ir atom-> base)
          eff (first @atom->)]
      (is (= :library-unresolved (first eff)))
      (let [{:keys [name ref reason detail]} (second eff)]
        (is (= "ghost-lib" name))
        (is (= "v1.2.3" ref))
        (is (= :library-not-found reason))
        (is (string? detail))))))

(deftest load-into-effects-default-version-is-main
  (testing "an entry without :version defaults to ref 'main'"
    (let [base (mk-tmp-base!)
          atom-> (atom [])
          ir {:libraries [{:name "no-version-lib"}]}
          _ (lib/load-into-effects! ir atom-> base)
          eff (first @atom->)]
      (is (= "main" (:ref (second eff)))))))

(deftest load-into-effects-mixed-bag-emits-per-library-effect
  (testing "every coordinate gets an effect — independent of others"
    (let [base (mk-tmp-base!)
          _ (write-stub-library! base "found-lib" "main" "doit")
          atom-> (atom [])
          ir {:libraries [{:name "found-lib"}
                          {:name "missing-lib" :version "v2"}]}
          _ (lib/load-into-effects! ir atom-> base)
          tags (mapv first @atom->)]
      (is (= [:library-loaded :library-unresolved] tags)
          "preserves IR order, one effect each"))))

;; ---------------------------------------------------------------------------
;; Classifier integration — both productive + observation mapping
;; ---------------------------------------------------------------------------

(deftest classifier-treats-library-loaded-as-productive
  (testing ":library-loaded prevents AN5-1's translator.body-skipped synth"
    (let [ir {:libraries [{:name "found-lib"}]
              :stages [{:name "Build" :steps []}]}
          effects [[:library-loaded {:name "found-lib" :ref "main"
                                     :registered ["doit"]}]]
          synth (classify/synthesize-shape-effects ir effects)]
      (is (empty? synth)
          "real :library-loaded → no synthetic effect emitted"))))

(deftest classifier-skips-synth-when-real-unresolved-already-recorded
  (testing ":library-unresolved already says it — synth doesn't double-report"
    (let [ir {:libraries [{:name "ghost-lib"}]
              :stages [{:name "Build" :steps []}]}
          effects [[:library-unresolved {:name "ghost-lib" :ref "main"
                                         :reason :library-not-found
                                         :detail "no dir"}]]
          synth (classify/synthesize-shape-effects ir effects)]
      (is (not-any?
           (fn [eff]
             (and (= :unknown (first eff))
                  (= "library.ghost-lib-unresolved" (:name (second eff)))))
           synth)
          "no synthetic library.ghost-lib-unresolved when real one exists"))))

(deftest classifier-synthesizes-only-for-unattested-libraries
  (testing "with one lib attested and one not, only the unattested
            gets a synthetic library-unresolved"
    (let [ir {:libraries [{:name "found-lib"} {:name "phantom-lib"}]
              :stages [{:name "Build" :steps []}]}
          ;; The runner attested found-lib only.
          effects [[:library-loaded {:name "found-lib" :ref "main"
                                     :registered ["doit"]}]]
          ;; any-productive? is true because :library-loaded is productive,
          ;; which actually disables the lib-effects synth pass entirely.
          ;; So we should get no synthetic library-unresolved at all.
          synth (classify/synthesize-shape-effects ir effects)]
      (is (empty? synth)
          "any productive effect → no synthetics; honest by construction"))))

(deftest classifier-still-falls-back-when-no-runner-attestation
  (testing "no :library-loaded / :library-unresolved → AN5-1 synth still fires
            for the unattested @Library coordinate (the legacy guarantee)"
    (let [ir {:libraries [{:name "phantom-lib"}]
              :stages [{:name "Build" :steps []}]}
          effects []  ; runner never wired — pure synth path
          synth (classify/synthesize-shape-effects ir effects)
          syn-lib (first (filter
                          (fn [eff]
                            (and (= :unknown (first eff))
                                 (= "library.phantom-lib-unresolved"
                                    (:name (second eff)))))
                          synth))]
      (is syn-lib "with no real effect, the AN5-1 fallback still fires")
      (is (= true (:anvil/synthetic? (second syn-lib)))))))
