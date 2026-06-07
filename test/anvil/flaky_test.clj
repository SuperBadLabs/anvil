(ns anvil.flaky-test
  "Tests for the v0.4 T1.1 passed-on-retry analyzer.

   Hermetic — operates on synthetic per-attempt row maps in the exact
   shape `anvil.storage.test-results/find-results-all-attempts`
   returns.  No SQLite, no scanner, no dispatcher.  T1.5 wires the
   real path; T1.6 layers a 4-fixture browser test on top of this."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.flaky :as flaky]))

;; ---------------------------------------------------------------------------
;; Row builder — mirrors anvil_test_results column names mapped to
;; the keyword form result-row->map produces.
;; ---------------------------------------------------------------------------

(defn- row [test-id attempt status]
  {:test-id        test-id
   :class          (or (some-> test-id (clojure.string/split #"#") first) test-id)
   :name           (or (some-> test-id (clojure.string/split #"#") second) test-id)
   :status         status
   :attempt-number attempt
   :duration-ms    0
   :build-number   1
   :job-name       "demo"})

(defn- rows-for
  "Build per-attempt rows for one test from a status sequence.
   `(rows-for \"a#b\" [:failed :passed])` → two rows."
  [test-id status-seq]
  (vec (map-indexed (fn [i status]
                      (row test-id (inc i) status))
                    status-seq)))

;; ---------------------------------------------------------------------------
;; detect-flaky-tests — the canonical T1.1 entry point
;; ---------------------------------------------------------------------------

(deftest detect-empty-rows
  (testing "no rows → no flake"
    (is (= {} (flaky/detect-flaky-tests [])))))

(deftest detect-single-attempt-never-flaky
  (testing "a single :passed attempt is not flake"
    (is (= {} (flaky/detect-flaky-tests (rows-for "a#b" [:passed])))))
  (testing "a single :failed attempt is not flake — it's an honest fail"
    (is (= {} (flaky/detect-flaky-tests (rows-for "a#b" [:failed])))))
  (testing "a single :errored attempt is not flake"
    (is (= {} (flaky/detect-flaky-tests (rows-for "a#b" [:errored]))))))

(deftest detect-passed-first-try-not-flaky
  (testing "two attempts, both :passed — not flaky (no retry happened)"
    (is (= {} (flaky/detect-flaky-tests (rows-for "a#b" [:passed :passed]))))))

(deftest detect-failed-then-passed-is-flake-shape
  (testing "the canonical flake — fail then pass within the build"
    (is (= {"a#b" 1}
           (flaky/detect-flaky-tests (rows-for "a#b" [:failed :passed]))))))

(deftest detect-errored-then-passed-is-flake-shape
  (testing "an :errored attempt counts as a failed attempt for flake"
    (is (= {"a#b" 1}
           (flaky/detect-flaky-tests (rows-for "a#b" [:errored :passed]))))))

(deftest detect-mixed-fail-pass-fail-pass-rolls-up
  (testing "two retries before passing — retry-count = 3 (attempts 1,2,3 all failed; 4 passed)"
    (is (= {"a#b" 3}
           (flaky/detect-flaky-tests
            (rows-for "a#b" [:failed :failed :errored :passed]))))))

(deftest detect-final-attempt-still-failed-is-not-flake
  (testing "a test that retried but never recovered is an honest fail, not a flake"
    (is (= {} (flaky/detect-flaky-tests
               (rows-for "a#b" [:failed :failed :failed]))))))

(deftest detect-skipped-attempts-ignored
  (testing ":skipped at the head does NOT count as a failure"
    (is (= {} (flaky/detect-flaky-tests
               (rows-for "a#b" [:skipped :passed])))
        ":skipped → :passed is not a flake (no failed attempt)"))
  (testing ":passed at the end after a real :failed → flake even with :skipped between"
    (is (= {"a#b" 2}
           (flaky/detect-flaky-tests
            (rows-for "a#b" [:failed :skipped :passed]))))))

(deftest detect-handles-multiple-tests
  (testing "two tests, one flaky, one not — only the flaky one is returned"
    (let [rows (concat (rows-for "ok#test"     [:passed])
                       (rows-for "shaky#test"  [:failed :passed])
                       (rows-for "dead#test"   [:failed :failed]))]
      (is (= {"shaky#test" 1}
             (flaky/detect-flaky-tests rows))))))

(deftest detect-survives-unsorted-input
  (testing "if the caller hands us rows out of attempt order, we still get it right"
    (let [;; attempts 1-passed, 2-failed, 3-passed: this is :failed
          ;; appearing between two :passes — which is still a flake
          ;; under the passed-on-retry definition (final attempt
          ;; passed, prior attempt failed).
          rows [(row "z" 3 :passed)
                (row "z" 1 :passed)
                (row "z" 2 :failed)]]
      (is (= {"z" 2}
             (flaky/detect-flaky-tests rows))))))

;; ---------------------------------------------------------------------------
;; flaky-count
;; ---------------------------------------------------------------------------

(deftest flaky-count-tracks-detect
  (is (= 0 (flaky/flaky-count [])))
  (is (= 0 (flaky/flaky-count (rows-for "a#b" [:passed]))))
  (is (= 1 (flaky/flaky-count (rows-for "a#b" [:failed :passed]))))
  (is (= 2 (flaky/flaky-count (concat (rows-for "x#a" [:failed :passed])
                                      (rows-for "y#b" [:errored :passed])
                                      (rows-for "z#c" [:passed]))))))

;; ---------------------------------------------------------------------------
;; rank-by-flake-rate — T1.3 dashboard substrate
;; ---------------------------------------------------------------------------

(defn- cross-build-row [test-id build flaky?]
  {:test-id      test-id
   :build-number build
   :flaky?       flaky?})

(deftest rank-empty
  (is (= [] (flaky/rank-by-flake-rate []))))

(deftest rank-no-flakes
  (testing "tests that never flaked are excluded"
    (is (= [] (flaky/rank-by-flake-rate
               [(cross-build-row "stable#1" 1 false)
                (cross-build-row "stable#1" 2 false)])))))

(deftest rank-single-flake
  (let [rows [(cross-build-row "stable#a" 1 false)
              (cross-build-row "stable#a" 2 false)
              (cross-build-row "shaky#b"  1 true)
              (cross-build-row "shaky#b"  2 false)]
        ranked (flaky/rank-by-flake-rate rows)]
    (is (= 1 (count ranked)))
    (is (= "shaky#b" (:test-id (first ranked))))
    (is (= 1        (:flake-count (first ranked))))
    (is (= 2        (:build-count (first ranked))))
    (is (= 1/2      (:flake-rate (first ranked))))))

(deftest rank-orders-by-rate-desc-then-count-desc-then-id-asc
  (let [rows [;; high-rate: 2/2
              (cross-build-row "always-flake" 1 true)
              (cross-build-row "always-flake" 2 true)
              ;; medium-rate: 1/2
              (cross-build-row "half-flake"   1 true)
              (cross-build-row "half-flake"   2 false)
              ;; medium-rate higher count: 2/4
              (cross-build-row "half-flake-2" 1 true)
              (cross-build-row "half-flake-2" 2 true)
              (cross-build-row "half-flake-2" 3 false)
              (cross-build-row "half-flake-2" 4 false)
              ;; same as half-flake but alpha-later test-id (tie-break test)
              (cross-build-row "z-half"       1 true)
              (cross-build-row "z-half"       2 false)]
        ranked (flaky/rank-by-flake-rate rows)]
    (is (= ["always-flake"  ; 1.0
            "half-flake-2"  ; 0.5, count=2
            "half-flake"    ; 0.5, count=1, alpha first
            "z-half"]       ; 0.5, count=1, alpha last
           (mapv :test-id ranked)))))
