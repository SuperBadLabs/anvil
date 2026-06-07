(ns anvil.web.views.flaky-page
  "v0.4 T1.3 — `/flaky` dashboard.  Lists the top flaky tests across
   the instance over a rolling 30-build window per the v0.4 board.

   A flaky test is one passed-on-retry within a single build (per
   AV4-3, the only definition shipping at v0.4.0).
   `anvil.flaky/rank-by-flake-rate` does the cross-build aggregation;
   `anvil.storage.test-results/recent-flaky-window` supplies the
   minimal projection it consumes.

   The per-job widget rendered on a build's page uses the per-build
   helper (`find-flaky-tests` + this view's `widget` fn) — same code
   path, different scope.

   Gated by `:anvil.features/flaky` (closed by default) at the route
   layer in `anvil.web.routes`."
  (:require [anvil.web.views.layout :as layout]
            [anvil.flaky :as flaky]
            [anvil.storage.test-results :as tr]))

(def ^:private window-size
  "How many recent builds to include in the cross-build aggregation.
   Matches the v0.4 board's '30-build pass-rate sparkline' spec."
  30)

(def ^:private top-n
  "How many flaky tests to surface on the dashboard.  20 is a
   reasonable scroll-free landing experience; pagination is v0.4.x
   territory if anyone asks for it."
  20)

;; ---------------------------------------------------------------------------
;; Hiccup rendering
;; ---------------------------------------------------------------------------

(defn- flake-rate-cell
  "Render the flake-rate as '3/10 (30%)' — denominator helps operators
   judge confidence without an extra hover."
  [{:keys [flake-count build-count flake-rate]}]
  (let [pct (Math/round (double (* 100 flake-rate)))]
    [:span [:strong flake-count] "/" build-count
     " (" pct "%)"]))

(defn- ranked-table [rows]
  (if (empty? rows)
    [:p.muted
     "No flaky tests detected in the last "
     window-size
     " builds.  When a build uses "
     [:code "retry(N) { sh 'mvn test' }"]
     " and a test fails an attempt before passing a later one, it'll appear here."]
    [:table
     [:thead
      [:tr
       [:th "Test"]
       [:th "Flake rate"]
       [:th "Last seen build"]]]
     [:tbody
      (for [r rows]
        [:tr
         [:td [:code (:test-id r)]]
         [:td (flake-rate-cell r)]
         [:td (str "#" (:last-build r))]])]]))

(defn- enrich-with-last-seen
  "ranked-by-flake-rate returns flake-counts but not the most-recent
   build number for each test.  Walk the source rows once and stitch
   it on for the table column."
  [ranked source-rows]
  (let [last-build-by-test
        (reduce
         (fn [acc {:keys [test-id build-number flaky?]}]
           (if flaky?
             (update acc test-id #(max (or % 0) build-number))
             acc))
         {}
         source-rows)]
    (mapv #(assoc % :last-build (get last-build-by-test (:test-id %) "?"))
          ranked)))

;; ---------------------------------------------------------------------------
;; Page handler
;; ---------------------------------------------------------------------------

(defn page
  "Ring handler for `/flaky` (T1.3)."
  [_req]
  (let [;; nil job-name = instance-wide aggregation
        rows    (tr/recent-flaky-window nil window-size)
        ranked  (enrich-with-last-seen (take top-n (flaky/rank-by-flake-rate rows))
                                       rows)]
    (layout/page
     {:title "Flaky tests" :active :flaky}
     [:h2 (str "Flaky tests — last " window-size " builds")]
     [:p.muted
      "A test is flagged when it failed an earlier attempt but passed a later attempt "
      "within the same build (the "
      [:code "retry(N)"]
      " path). Per "
      [:strong "AV4-3"]
      ", passed-on-retry is the only definition at v0.4.0; statistical "
      "flake-rate models layer on in v0.4.x."]
     (ranked-table ranked))))

;; ---------------------------------------------------------------------------
;; Per-build widget — embedded on /jobs/<j>/<n>
;; ---------------------------------------------------------------------------

(defn widget
  "Render the per-build flaky widget given the latest-attempt rows
   returned by `tr/find-flaky-tests`.  Empty input → nil so the
   build page can omit the section entirely; non-empty → an
   `<aside class=\"flaky-widget\">` with the count + per-test list."
  [flaky-rows]
  (when (seq flaky-rows)
    [:aside.flaky-widget
     {:id "flaky-widget"
      ;; SSE swap target for T1.4's :flaky-flagged event — htmx will
      ;; re-render this region after the producer publishes.
      :hx-trigger "sse:flaky-flagged"
      :hx-target "this"
      :hx-swap "outerHTML"}
     [:h4 (str (count flaky-rows)
               (if (= 1 (count flaky-rows))
                 " flaky test this build"
                 " flaky tests this build"))]
     [:ul
      (for [r flaky-rows]
        [:li
         [:code (:test-id r)]
         " — "
         [:span.muted (str "recovered after " (:retry-count r)
                           (if (= 1 (:retry-count r)) " retry" " retries"))]])]]))
