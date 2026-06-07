(ns anvil.web.views.flaky-page-test
  "Tests for the v0.4 T1.3 /flaky dashboard + per-build widget.

   Pure-data — stub recent-flaky-window via with-redefs; the
   Hiccup rendering layer is the unit under test."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.storage.test-results :as tr]
            [anvil.web.views.flaky-page :as flaky-page]))

(defn- row [test-id build flaky?]
  {:test-id test-id :build-number build :flaky? flaky?})

(deftest page-empty-shows-explanation
  (testing "no rows in the window → friendly empty state"
    (with-redefs [tr/recent-flaky-window (fn [_ _] [])]
      (let [hiccup (flaky-page/page {})
            html (str hiccup)]
        (is (re-find #"Flaky tests" html))
        (is (re-find #"No flaky tests" html)
            "empty state names the absence + how to surface flakes")))))

(deftest page-with-flakes-renders-ranked-table
  (testing "rows with flakes render in flake-rate desc order"
    (with-redefs [tr/recent-flaky-window
                  (fn [_ _]
                    [(row "always-flake" 1 true)
                     (row "always-flake" 2 true)
                     (row "always-flake" 3 true)
                     (row "half-flake" 1 true)
                     (row "half-flake" 2 false)
                     (row "stable-test" 1 false)])]
      (let [html (str (flaky-page/page {}))]
        (is (re-find #"always-flake" html))
        (is (re-find #"half-flake" html))
        (is (not (re-find #"stable-test" html))
            "non-flaky tests stay off the dashboard")
        (let [pos-always (.indexOf html "always-flake")
              pos-half   (.indexOf html "half-flake")]
          (is (< pos-always pos-half)
              "100% flake rate ranks ahead of 50%"))
        (is (re-find #"3</strong>/3" html)
            "always-flake shows 3/3 flake/build")
        (is (re-find #"1</strong>/2" html)
            "half-flake shows 1/2 flake/build")))))

(deftest widget-empty-returns-nil
  (testing "no flakes this build → widget omitted entirely"
    (is (nil? (flaky-page/widget [])))))

(deftest widget-with-flakes-renders-aside
  (testing "1 flake → singular; multiple → plural; retry-count surfaced"
    (let [one (flaky-page/widget [{:test-id "a#b" :retry-count 1}])
          two (flaky-page/widget [{:test-id "a#b" :retry-count 1}
                                  {:test-id "x#y" :retry-count 2}])
          html-one (str one)
          html-two (str two)]
      (is (re-find #"1 flaky test this build" html-one))
      (is (re-find #"1 retry" html-one))
      (is (re-find #"2 flaky tests this build" html-two))
      (is (re-find #"2 retries" html-two)
          "retry-count uses plural form for > 1")
      (is (re-find #"sse:flaky-flagged" html-one)
          "widget carries hx-trigger so T1.4's SSE event swaps it live"))))
