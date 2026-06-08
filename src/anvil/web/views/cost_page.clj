(ns anvil.web.views.cost-page
  "v0.5 T2.3 — `/cost` dashboard.

   Shows the top-N most expensive builds over a rolling 30-day window,
   a 30-day totals summary, and (T2.5) cache savings when present.

   Gated by :cost-reporting feature flag at the route layer in
   `anvil.web.routes`."
  (:require [anvil.web.views.layout :as layout]
            [anvil.storage.costs :as cost-store]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private top-n
  "How many rows to surface on the dashboard. Pagination is v0.5.x if needed."
  20)

(defn- format-cents
  "Integer cents → '$X.XX' for the table. Matches cost-pill's logic."
  [cents]
  (cond
    (nil? cents)  "$0.00"
    (zero? cents) "$0.00"
    (< cents 1)   "<$0.01"
    :else
    (let [d (quot cents 100)
          f (mod  cents 100)]
      (format "$%d.%02d" d f))))

(defn- duration-str
  "Friendly duration: ms → 'Xh Ym Zs' or 'Ym Zs' or 'Zs' depending on length."
  [ms]
  (let [s  (quot ms 1000)
        m  (quot s 60)
        h  (quot m 60)
        ss (mod s 60)
        mm (mod m 60)]
    (cond
      (pos? h)  (format "%dh %dm %ds" h mm ss)
      (pos? mm) (format "%dm %ds" mm ss)
      :else     (format "%ds" ss))))

;; ---------------------------------------------------------------------------
;; Hiccup fragments
;; ---------------------------------------------------------------------------

(defn- totals-banner [totals]
  (let [{:keys [total-cents cache-saved-cents build-count]} totals]
    [:div.cost-summary-banner
     {:style "margin-bottom:1.5em; padding:0.75em 1em; background:#f7f7f7; border-radius:4px; border:1px solid #e0e0e0;"}
     [:strong "30-day totals — "]
     (format-cents total-cents)
     [:span.muted {:style "font-size:0.85em; margin-left:0.5em;"}
      (str "(across " build-count " build" (when (not= 1 build-count) "s") ")")]
     (when (pos? (or cache-saved-cents 0))
       [:span {:style "margin-left:0.75em; color:#078;"}
        (str "· saved ~" (format-cents cache-saved-cents) " via cache")])]))

(defn- cost-table [rows]
  (if (empty? rows)
    [:p.muted
     "No builds with cost data in the last 30 days. "
     "Enable the "
     [:code ":cost-reporting"]
     " feature flag and wait for a build to finish — costs appear here "
     "as soon as the recorder fires."]
    [:table
     [:thead
      [:tr
       [:th "Rank"]
       [:th "Build"]
       [:th "Total cost"]
       [:th "Cache savings"]
       [:th "Duration"]
       [:th "Host"]
       [:th "Recorded"]]]
     [:tbody
      (map-indexed
       (fn [i row]
         (let [{:keys [job-name build-number total-cents cache-saved-cents
                       duration-ms host recorded-at]} row]
           [:tr
            [:td.muted (str (inc i))]
            [:td [:a {:href (str "/jobs/" job-name "/" build-number)}
                  (str job-name " #" build-number)]]
            [:td [:strong (format-cents total-cents)]]
            [:td (if (pos? (or cache-saved-cents 0))
                   [:span {:style "color:#078;"}
                    (str "~" (format-cents cache-saved-cents))]
                   [:span.muted "—"])]
            [:td.muted (duration-str (or duration-ms 0))]
            [:td.muted [:code (or host "?")]]
            [:td.muted (str recorded-at)]]))
       rows)]]))

;; ---------------------------------------------------------------------------
;; Page handler
;; ---------------------------------------------------------------------------

(defn page
  "Ring handler for `/cost` (T2.3)."
  [_req]
  (let [rows   (cost-store/top-by-cost {:limit top-n :days 30 :min-cents 0})
        totals (cost-store/totals-30d)]
    (layout/page
     {:title "Cost dashboard" :active :cost}
     [:h2 "Build cost — last 30 days"]
     [:p.muted
      "Build cost = wall-time × per-host rate, tracked in whole cents. "
      "Declare host rates under "
      [:code ":anvil.cost/host-rates"]
      " in "
      [:code "anvil.edn"]
      ". Hosts without a declared rate show "
      [:code "$0.00"]
      " — see "
      [:strong "AV5-6 honesty rule"]
      ": anvil never invents numbers it doesn't have."]
     (when totals (totals-banner totals))
     (cost-table (or rows [])))))
