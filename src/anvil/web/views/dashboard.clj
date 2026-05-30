(ns anvil.web.views.dashboard
  "Main dashboard — landing page. Shows the operator: how many jobs,
   how many builds, what's running right now, recent build outcomes."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]))

(defn- count-builds-by-outcome []
  (reduce (fn [acc job]
            (case (:color job)
              :blue        (update acc :success inc)
              :blue_anime  (update acc :running inc)
              :red         (update acc :failure inc)
              :yellow      (update acc :unstable inc)
              :aborted     (update acc :aborted inc)
              acc))
          {:success 0 :failure 0 :unstable 0 :aborted 0 :running 0}
          (jobs/list-jobs)))

(defn page [_req]
  (let [all-jobs (jobs/list-jobs)
        outcomes (count-builds-by-outcome)
        queued (queue/queue-snapshot)
        running (queue/running-snapshot)
        ;; Show every job; sort jobs that have built recently first.
        recent-jobs (->> all-jobs
                         (sort-by (fn [j] [(or (:last-build j) 0)
                                           (:name j)])
                                  #(compare %2 %1))
                         (take 10))]
    (layout/page
     {:title "Dashboard" :active :dashboard}
     [:div.stat-row
      [:div.stat
       [:div.stat-label "Jobs"]
       [:div.stat-value (count all-jobs)]]
      [:div.stat
       [:div.stat-label "Queue"]
       [:div.stat-value {:class (if (seq queued) "yellow" "muted")}
        (count queued)]]
      [:div.stat
       [:div.stat-label "Running"]
       [:div.stat-value {:class (if (seq running) "yellow" "muted")}
        (reduce + 0 (vals running))]]
      [:div.stat
       [:div.stat-label "Passing"]
       [:div.stat-value.green (:success outcomes)]]
      [:div.stat
       [:div.stat-label "Failing"]
       [:div.stat-value.red (:failure outcomes)]]]

     (when (seq queued)
       [:div
        [:h3 "Queued"]
        [:table
         [:thead
          [:tr [:th "id"] [:th "job"] [:th "since"]]]
         [:tbody
          (for [q queued]
            [:tr
             [:td "#" (:queue-id q)]
             [:td [:a {:href (str "/jobs/" (:job-name q))} (:job-name q)]]
             [:td (str (:enqueued-at q))]])]]])

     (when (seq running)
       [:div
        [:h3 "Running"]
        [:ul
         (for [[job-name n] running :when (pos? n)]
           [:li [:a {:href (str "/jobs/" job-name)} job-name]
            " — " n " active"])]])

     [:h3 "Recent builds"]
     (if (seq recent-jobs)
       [:table
        [:thead
         [:tr [:th "Job"] [:th "Last build"] [:th "Status"] [:th "Last success"]]]
        [:tbody
         (for [j recent-jobs]
           [:tr
            [:td [:a {:href (str "/jobs/" (:name j))} (:name j)]]
            [:td (if-let [lb (:last-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" lb)} (str "#" lb)]
                   [:span.muted "—"])]
            [:td (layout/badge-for-color (:color j))]
            [:td (if-let [lsb (:last-successful-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" lsb)} (str "#" lsb)]
                   [:span.muted "—"])]])]]
       [:p.muted "No jobs registered yet. Use "
        [:code "anvil import jenkinsfile <path>"] " to convert a Jenkinsfile."])

     [:h3 "Quick links"]
     [:ul
      [:li [:a {:href "/jenkins/api/json"} "Jenkins-shape REST root"]]
      [:li [:a {:href "/api/status"} "anvil internal status JSON"]]
      [:li [:a {:href "/api/health"} "Health probe"]]])))
