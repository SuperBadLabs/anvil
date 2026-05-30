(ns anvil.web.views.queue-page
  "Live queue + running-builds view."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.queue :as queue]))

(defn page [_req]
  (let [items (queue/queue-snapshot)
        running (queue/running-snapshot)
        running-jobs (filter (fn [[_ n]] (pos? n)) running)]
    (layout/page
     {:title "Queue" :active :queue}
     [:h2 "Queue"]

     [:div.stat-row
      [:div.stat
       [:div.stat-label "Queued"]
       [:div.stat-value {:class (if (seq items) "yellow" "muted")} (count items)]]
      [:div.stat
       [:div.stat-label "Running"]
       [:div.stat-value {:class (if (seq running-jobs) "yellow" "muted")}
        (reduce + 0 (map second running-jobs))]]]

     [:h3 "Queued"]
     (if (empty? items)
       [:p.muted "Nothing queued."]
       [:table
        [:thead [:tr [:th "id"] [:th "job"] [:th "since"] [:th "cancelled?"]]]
        [:tbody
         (for [it items]
           [:tr
            [:td "#" (:queue-id it)]
            [:td [:a {:href (str "/jobs/" (:job-name it))} (:job-name it)]]
            [:td (str (:enqueued-at it))]
            [:td (if (:cancelled? it) "yes" "—")]])]])

     [:h3 "Running"]
     (if (empty? running-jobs)
       [:p.muted "No builds currently running."]
       [:ul
        (for [[job-name n] running-jobs]
          [:li [:a {:href (str "/jobs/" job-name)} job-name] " — " n " active"])]))))
