(ns anvil.web.views.sparkline
  "Inline-SVG sparkline widget (TU3.6).

   Each bar = one build's duration, height ∝ duration relative to the
   max bar in the window, colour by outcome. Renders server-side as
   pure hiccup — no chart library, no JS. Drop into any hiccup tree.

   Designed to be cheap: ~30 lines of SVG per sparkline, server is
   sub-ms anyway. Dashboard renders 50 of them per page load without
   noticeable cost (validated in TU0.7 bench window).

   Colours come from the design tokens defined in layout.clj
   (`--ok`, `--err`, `--warn`, etc.) — so the sparkline dark-modes
   itself."
  (:require [anvil.web.jenkins-api.jobs :as jobs]))

(def ^:private result->color-css
  {:success "var(--ok)"
   :failure "var(--err)"
   :unstable "var(--warn)"
   :aborted "var(--fg-faint)"
   :running "var(--accent)"})

(defn- bar-color [b]
  (cond
    (:building? b) (result->color-css :running)
    :else          (or (result->color-css (:result b))
                       "var(--fg-faint)")))

(defn job-sparkline
  "Render a sparkline for `job-name`. Returns nil if the job has zero
   builds (caller can emit a '—' placeholder).

   Options:
     :n         number of recent builds to show (default 30)
     :width-px  total SVG width (default 120)
     :height-px total SVG height (default 28)"
  ([job-name] (job-sparkline job-name {}))
  ([job-name {:keys [n width-px height-px]
              :or {n 30 width-px 120 height-px 28}}]
   (let [all (jobs/list-builds-for-job job-name)
         ;; Most-recent N, oldest on the left so the sparkline reads
         ;; left→right newest-on-right (matches every other time-
         ;; series chart in CI tooling).
         recent (->> all
                     (sort-by :number #(compare %2 %1))
                     (take n)
                     reverse
                     vec)
         count-shown (count recent)]
     (when (pos? count-shown)
       (let [max-dur (max 1 (apply max (map #(or (:duration-ms %) 0) recent)))
             ;; Even a running build with dur=0 deserves a visible
             ;; sliver — clamp the minimum bar height to 2px.
             min-bar 2
             bar-w (max 1.0 (/ (double width-px) n))
             gap 1.0]
         [:svg {:class "job-sparkline"
                :viewBox (str "0 0 " width-px " " height-px)
                :width width-px :height height-px
                :role "img"
                :aria-label (str "Last " count-shown " build outcomes for " job-name)}
          (for [[i b] (map-indexed vector recent)
                :let [raw-h (* height-px (/ (or (:duration-ms b) 0) (double max-dur)))
                      h (max min-bar raw-h)
                      x (* i bar-w)
                      y (- height-px h)
                      title (str "#" (:number b) " · "
                                 (some-> b :result name)
                                 (when-let [d (:duration-ms b)] (str " · " d " ms")))]]
            [:rect {:x x :y y
                    :width (max 1.0 (- bar-w gap))
                    :height h
                    :fill (bar-color b)
                    :rx 1}
             [:title title]])])))))
