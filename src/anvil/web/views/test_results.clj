(ns anvil.web.views.test-results
  "Per-build test-results panel for the build page (T1.4 of the v0.3
   board).

   What's on screen, top to bottom:

     1. Summary card — counts (passed / failed / errored / skipped),
        total duration, parse-error diagnostic if any.
     2. Failed-test list — collapsible per-test stack-trace blocks,
        sorted failed → errored → name.
     3. Sortable test table — every case with name / class / status
        / duration. Default sort: slowest first (operator's question
        most of the time is 'what's taking forever?').
     4. Pass-rate sparkline — SVG showing pass-rate across the last
        N builds. Empty until we have ≥2 builds.

   This namespace renders Hiccup; it never touches the bus or the
   DB. The build-page caller is responsible for fetching summary +
   results + history via anvil.storage.test-results."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Summary card
;; ---------------------------------------------------------------------------

(defn- duration-pretty
  "Format ms as the human-friendly 1.2s / 1m 2s / 1h 2m form."
  [ms]
  (let [ms (or ms 0)]
    (cond
      (< ms 1000)         (str ms " ms")
      (< ms 60000)        (format "%.2f s" (double (/ ms 1000.0)))
      (< ms 3600000)      (format "%dm %ds"
                                  (quot ms 60000)
                                  (quot (mod ms 60000) 1000))
      :else               (format "%dh %dm"
                                  (quot ms 3600000)
                                  (quot (mod ms 3600000) 60000)))))

(defn- pass-rate
  "Returns a float in [0,1] given a summary map, or nil if no tests."
  [{:keys [tests passed]}]
  (when (and tests (pos? tests))
    (double (/ (or passed 0) tests))))

(defn- pct [r]
  (when r (format "%.1f%%" (* 100.0 r))))

(defn summary-card
  "Summary card Hiccup for a build's test-results panel."
  [{:keys [tests passed failed errored skipped duration-ms parse-errors]
    :as summary}]
  [:div.test-summary
   [:div.test-summary-counts
    [:span.summary-pill.passed  (str "✓ " (or passed 0) " passed")]
    [:span.summary-pill.failed  (str "✗ " (or failed 0) " failed")]
    [:span.summary-pill.errored (str "⚠ " (or errored 0) " errored")]
    [:span.summary-pill.skipped (str "↷ " (or skipped 0) " skipped")]]
   [:div.test-summary-meta
    [:span (str "Total: " (or tests 0) " tests · "
                (duration-pretty duration-ms)
                (when-let [p (pct (pass-rate summary))]
                  (str " · " p " pass rate")))]
    (when (and parse-errors (pos? parse-errors))
      [:span.parse-error-warn (str " · ⚠ " parse-errors
                                   " report" (when-not (= 1 parse-errors) "s")
                                   " failed to parse")])]])

;; ---------------------------------------------------------------------------
;; Failed-test list (collapsible)
;; ---------------------------------------------------------------------------

(defn- failure-block [{:keys [test-id name class status failure-msg
                              failure-type failure-trace duration-ms]}]
  [:details.test-failure
   [:summary
    (case status
      :failed  [:span.badge.red "failed"]
      :errored [:span.badge.orange "errored"]
      [:span.badge.gray (clojure.core/name status)])
    " "
    [:code (str class "#" name)]
    (when duration-ms [:span.muted (str " · " (duration-pretty duration-ms))])
    (when failure-type [:span.muted (str " · " failure-type)])]
   (when failure-msg
     [:p.failure-msg [:strong "Message:"] " " failure-msg])
   (when failure-trace
     [:pre.failure-trace failure-trace])])

(defn failures-section
  "Hiccup for the failures list. Returns nil if no failures."
  [failed-results]
  (when (seq failed-results)
    [:div.test-failures
     [:h4 (str (count failed-results)
               " failure" (when-not (= 1 (count failed-results)) "s"))]
     (for [r (sort-by (juxt #(if (= :errored (:status %)) 1 0) :class :name)
                      failed-results)]
       (failure-block r))]))

;; ---------------------------------------------------------------------------
;; Sortable table (slowest first by default)
;; ---------------------------------------------------------------------------

(defn- status-badge [s]
  (case s
    :passed  [:span.badge.blue   "✓"]
    :failed  [:span.badge.red    "✗"]
    :errored [:span.badge.orange "⚠"]
    :skipped [:span.badge.gray   "↷"]
    [:span.badge.gray (str s)]))

(defn results-table
  "Hiccup for the per-case table. Sorted slowest → fastest by default
   (operator's most common question: what's taking the time)."
  [results]
  (when (seq results)
    (let [sorted (->> results (sort-by :duration-ms >))]
      [:details.test-results-table-fold {:open false}
       [:summary [:h4 {:style "display:inline"}
                  (str "All " (count results) " tests (by duration)")]]
       [:table.test-results-table
        [:thead
         [:tr [:th "Status"] [:th "Class"] [:th "Test"] [:th "Duration"]]]
        [:tbody
         (for [r sorted]
           [:tr {:class (clojure.core/name (or (:status r) :passed))}
            [:td (status-badge (:status r))]
            [:td [:code (:class r)]]
            [:td (:name r)]
            [:td.duration (duration-pretty (:duration-ms r))]])]]])))

;; ---------------------------------------------------------------------------
;; Pass-rate sparkline (SVG)
;; ---------------------------------------------------------------------------

(defn pass-rate-sparkline
  "Tiny SVG showing pass-rate across the recent build summaries.
   `history` is a vector of summary maps, most-recent first (matches
   anvil.storage.test-results/recent-summaries). Returns nil if there
   aren't enough data points (< 2) to draw a line."
  [history]
  (let [points (->> history reverse (keep (fn [s]
                                            (when (pos? (or (:tests s) 0))
                                              (pass-rate s)))))
        n (count points)]
    (when (>= n 2)
      (let [w 200 h 40 pad 4
            xs (map-indexed (fn [i _]
                              (+ pad (* (/ (- w (* 2 pad)) (dec n)) i)))
                            points)
            ys (map (fn [r] (- h pad (* r (- h (* 2 pad))))) points)
            d (str "M " (str/join " L "
                                  (map (fn [x y] (format "%.1f,%.1f" (double x) (double y)))
                                       xs ys)))]
        [:svg.test-pass-rate-sparkline {:width w :height h
                                        :viewBox (str "0 0 " w " " h)
                                        :role "img"
                                        :aria-label
                                        (str "Pass rate across last " n
                                             " builds")}
         [:rect {:x 0 :y 0 :width w :height h :fill "#fafafa" :stroke "#ddd"}]
         [:path {:d d :fill "none" :stroke "#2a7" :stroke-width 1.5}]
         (for [[x y] (map vector xs ys)]
           [:circle {:cx x :cy y :r 2 :fill "#2a7"}])]))))

;; ---------------------------------------------------------------------------
;; Top-level panel
;; ---------------------------------------------------------------------------

(defn panel
  "Full test-results panel for the build page.

   Arguments:
     :summary         the per-build summary map (or nil if no scan)
     :results         per-case results (used for the table)
     :failed-results  pre-filtered failed/errored (used for the
                      collapsible failures list)
     :history         vector of recent summaries for the sparkline

   Returns nil if `:summary` is nil (no junit step ran for this build)
   so the caller can use `when` without thinking."
  [{:keys [summary results failed-results history]}]
  (when summary
    [:section.test-results
     [:div.test-results-header
      [:h3 {:style "display:inline"} "Tests"]
      (when-let [spark (pass-rate-sparkline history)]
        [:span.test-pass-rate-spark spark])]
     (summary-card summary)
     (failures-section failed-results)
     (results-table results)]))
