(ns anvil.web.views.cost-pill
  "v0.5 T2.4 — per-build cost pill on /jobs/<j>/<n>.

   Reads from `anvil.storage.costs/for-build`; returns nil if no cost
   row exists yet (the recorder fires on :build-done, so in-progress
   builds won't show a pill). Gated by :cost-reporting feature flag.

   SSE swap: the pill carries hx-trigger='sse:cost-tallied' so the
   recorder's publish (T2.2) can update the pill live when the build
   finishes — no page reload needed."
  (:require [anvil.features :as features]
            [anvil.storage.costs :as cost-store]))

(defn- format-cents
  "Format an integer cents value for display.
   ≥100 cents → '$X.XX'; < 100 → '<$0.01' (never shows $0.00 for
   non-zero cost — that would be dishonest)."
  [cents]
  (cond
    (nil? cents)    "$0.00"
    (zero? cents)   "$0.00"
    (< cents 1)     "<$0.01"
    :else
    (let [dollars (quot cents 100)
          frac    (mod cents 100)]
      (format "$%d.%02d" dollars frac))))

(defn pill
  "Render the cost pill for a build, or nil when nothing to show.

   Returns nil when:
     - :cost-reporting flag is off
     - no cost row exists for this build yet (still running, or the
       recorder has not fired)

   Otherwise returns a hiccup fragment showing total cost + optional
   cache savings badge."
  [{:keys [job-name number]}]
  (when (features/enabled? :cost-reporting)
    (when-let [cost (cost-store/for-build job-name number)]
      (let [total      (:total-cents cost)
            saved      (:cache-saved-cents cost)
            currency   (name (or (:currency cost) :usd))
            dur-ms     (:duration-ms cost)
            show-saved (pos? (or saved 0))]
        [:div.cost-pill
         {:id         "cost-pill"
          ;; htmx-sse: swap this element when the recorder fires the
          ;; cost-tallied event for any build on this job's topic.
          :hx-trigger "sse:cost-tallied"
          :hx-target  "this"
          :hx-swap    "outerHTML"
          :style      "margin:0.5em 0; padding:0.5em 0.75em; border:1px solid #ddd; border-radius:4px; display:inline-block;"}
         [:strong "Cost: "]
         [:span (format-cents total)]
         [:span.muted {:style "font-size:0.85em; margin-left:0.4em;"}
          (str "(" currency ", " dur-ms " ms)")]
         (when show-saved
           [:span.muted
            {:style "font-size:0.85em; margin-left:0.5em; color:#078;"}
            (str " · saved ~" (format-cents saved) " via cache")])]))))
