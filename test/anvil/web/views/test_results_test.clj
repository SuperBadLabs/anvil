(ns anvil.web.views.test-results-test
  "Tests for the v0.3 T1.4 test-results dashboard view (pure Hiccup —
   no DB, no bus). Walks the returned vector structure looking for
   expected elements / strings."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.web.views.test-results :as v]))

(defn- flatten-strings
  "Concatenate every string leaf in a Hiccup tree (handles vectors,
   lazy seqs, nested attrs maps)."
  [hic]
  (cond
    (string? hic) hic
    (nil? hic) ""
    (map? hic) "" ; attrs map — skip
    (sequential? hic)
    (str/join " " (map flatten-strings hic))
    :else (str hic)))

(deftest panel-returns-nil-when-no-summary
  (is (nil? (v/panel {:summary nil :results [] :failed-results [] :history []}))))

(deftest summary-card-shows-each-status-pill
  (let [text (flatten-strings
              (v/summary-card {:tests 10 :passed 7 :failed 2
                               :errored 1 :skipped 0 :duration-ms 1500
                               :parse-errors 0}))]
    (is (re-find #"7 passed" text))
    (is (re-find #"2 failed" text))
    (is (re-find #"1 errored" text))
    (is (re-find #"0 skipped" text))
    (is (re-find #"10 tests" text))
    (is (re-find #"70.0% pass rate" text))))

(deftest summary-card-shows-parse-errors-when-present
  (let [text (flatten-strings
              (v/summary-card {:tests 10 :passed 10 :failed 0
                               :errored 0 :skipped 0 :duration-ms 100
                               :parse-errors 3}))]
    (is (re-find #"3 reports failed to parse" text))))

(deftest summary-card-pass-rate-handles-zero-tests
  (testing "no tests means no pass-rate display (avoids divide-by-zero)"
    (let [text (flatten-strings
                (v/summary-card {:tests 0 :passed 0 :failed 0
                                 :errored 0 :skipped 0 :duration-ms 0}))]
      (is (not (re-find #"pass rate" text)))
      (is (re-find #"0 tests" text)))))

(deftest failures-section-nil-on-empty
  (is (nil? (v/failures-section [])))
  (is (nil? (v/failures-section nil))))

(deftest failures-section-renders-each-failure
  (let [hic (v/failures-section
             [{:test-id "X#a" :name "a" :class "X" :status :failed
               :failure-msg "expected 1 got 2" :failure-type "AssertionError"
               :failure-trace "  at X.a(X.java:12)" :duration-ms 50}
              {:test-id "X#b" :name "b" :class "X" :status :errored
               :failure-msg "NPE" :failure-type "NullPointerException"
               :failure-trace nil :duration-ms 5}])
        text (flatten-strings hic)]
    (is (re-find #"2 failures" text))
    (is (re-find #"X#a" text))
    (is (re-find #"X#b" text))
    (is (re-find #"expected 1 got 2" text))
    (is (re-find #"X.a\(X.java:12\)" text))))

(deftest results-table-sorts-slowest-first
  (let [hic (v/results-table
             [{:test-id "X#fast" :name "fast" :class "X" :status :passed :duration-ms 1}
              {:test-id "X#slow" :name "slow" :class "X" :status :passed :duration-ms 999}
              {:test-id "X#mid"  :name "mid"  :class "X" :status :passed :duration-ms 50}])
        text (flatten-strings hic)
        idx-slow (.indexOf text "slow")
        idx-mid  (.indexOf text "mid")
        idx-fast (.indexOf text "fast")]
    (is (< -1 idx-slow idx-mid idx-fast)
        (str "expected slow→mid→fast ordering, got slow=" idx-slow
             " mid=" idx-mid " fast=" idx-fast))))

(deftest results-table-includes-all-cases-as-rows
  (let [hic (v/results-table
             (for [i (range 5)]
               {:test-id (str "X#t" i) :name (str "t" i) :class "X"
                :status :passed :duration-ms (* i 10)}))
        text (flatten-strings hic)]
    (doseq [i (range 5)]
      (is (re-find (re-pattern (str "t" i)) text)))))

(deftest sparkline-nil-with-too-few-points
  (is (nil? (v/pass-rate-sparkline [])))
  (is (nil? (v/pass-rate-sparkline
             [{:tests 10 :passed 10}]))))

(deftest sparkline-produces-svg-with-N-circles
  (let [hist [{:tests 10 :passed 10}
              {:tests 10 :passed 8}
              {:tests 10 :passed 9}]
        hic (v/pass-rate-sparkline hist)]
    (is (some? hic))
    (is (= :svg.test-pass-rate-sparkline (first hic)))
    ;; One circle per point
    (let [circle-count (count (filter #(and (vector? %)
                                            (= :circle (first %)))
                                      (tree-seq sequential? seq hic)))]
      (is (= 3 circle-count)))))

(deftest panel-composes-all-sections
  (let [hic (v/panel
             {:summary {:tests 5 :passed 4 :failed 1 :errored 0 :skipped 0
                        :duration-ms 234 :parse-errors 0}
              :results (for [i (range 5)]
                         {:test-id (str "X#t" i) :name (str "t" i)
                          :class "X" :status (if (zero? i) :failed :passed)
                          :duration-ms (* (inc i) 10)
                          :failure-msg (when (zero? i) "boom")
                          :failure-trace (when (zero? i) "  at X.t0")})
              :failed-results [{:test-id "X#t0" :name "t0" :class "X"
                                :status :failed :failure-msg "boom"
                                :failure-trace "  at X.t0"
                                :duration-ms 10}]
              :history []})
        text (flatten-strings hic)]
    (is (= :section.test-results (first hic)))
    (is (re-find #"4 passed" text))
    (is (re-find #"1 failure" text))
    (is (re-find #"all 5 tests" (str/lower-case text)))))
