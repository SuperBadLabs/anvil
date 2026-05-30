(ns anvil.web.views.coverage-page
  "Step coverage dashboard — for each registered job, what fraction of
   its Jenkinsfile steps anvil natively handles. Driven by the
   parser + translator + step-translator dictionary, so this view
   stays accurate as anvil's adapter coverage grows."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]))

(defn- summarize-job [job]
  (let [ir-pipeline (t/parse (:jenkinsfile-source job)
                             (str (:name job) "/Jenkinsfile"))
        summary (ir/summarize ir-pipeline)]
    (assoc summary :job-name (:name job))))

(defn- aggregate [per-job]
  (let [total-steps (reduce + 0 (map :total-steps per-job))
        known-steps (reduce + 0 (map :known-steps per-job))
        unknown-steps (reduce + 0 (map :unknown-steps per-job))
        script-blocks (reduce + 0 (map :script-blocks per-job))
        all-unknowns (frequencies (mapcat #(or (:unknown-names %) #{}) per-job))]
    {:total-steps total-steps
     :known-steps known-steps
     :unknown-steps unknown-steps
     :script-blocks script-blocks
     :coverage (if (pos? total-steps)
                 (* 100.0 (/ known-steps total-steps))
                 100.0)
     :unknown-names all-unknowns}))

(defn page [_req]
  (let [js (jobs/list-jobs)
        per-job (mapv summarize-job js)
        agg (aggregate per-job)]
    (layout/page
     {:title "Coverage" :active :coverage}
     [:h2 "Step coverage across registered jobs"]
     [:p.muted "For each job's Jenkinsfile, what fraction of step calls
                anvil has a native adapter for. Steps that fall through
                to " [:code ":jenkins-unsupported"] " count as unknown."]

     (if (empty? js)
       [:p.muted "No jobs registered yet."]
       [:<>
        [:div.stat-row
         [:div.stat
          [:div.stat-label "Total steps"]
          [:div.stat-value (:total-steps agg)]]
         [:div.stat
          [:div.stat-label "Known"]
          [:div.stat-value.green (:known-steps agg)]]
         [:div.stat
          [:div.stat-label "Unknown"]
          [:div.stat-value
           {:class (cond
                     (zero? (:unknown-steps agg)) "muted"
                     :else "red")}
           (:unknown-steps agg)]]
         [:div.stat
          [:div.stat-label "Script blocks"]
          [:div.stat-value.muted (:script-blocks agg)]]
         [:div.stat
          [:div.stat-label "Coverage"]
          [:div.stat-value
           {:class (cond
                     (>= (:coverage agg) 90)  "green"
                     (>= (:coverage agg) 50)  "yellow"
                     :else                    "red")}
           (format "%.1f%%" (:coverage agg))]]]

        [:h3 "Per-job"]
        [:table
         [:thead
          [:tr [:th "Job"] [:th "Stages"] [:th "Steps"] [:th "Known"] [:th "Unknown"] [:th "Script {}"] [:th "Coverage"]]]
         [:tbody
          (for [s (sort-by :coverage > per-job)]
            [:tr
             [:td [:a {:href (str "/jobs/" (:job-name s))} (:job-name s)]]
             [:td (:stage-count s)]
             [:td (:total-steps s)]
             [:td (:known-steps s)]
             [:td (:unknown-steps s)]
             [:td (:script-blocks s)]
             [:td
              [:span {:class (cond
                               (>= (:coverage s) 90.0) "badge blue"
                               (>= (:coverage s) 50.0) "badge yellow"
                               (zero? (:total-steps s)) "badge gray"
                               :else                    "badge red")}
               (format "%.1f%%" (:coverage s))]]])]]

        (when (seq (:unknown-names agg))
          [:<>
           [:h3 "Top unknown step names (the migration UX queue)"]
           [:p.muted "When these appear in an imported Jenkinsfile, the
                      importer flags them with " [:code ":FIXME"] ". Add
                      adapters via "
            [:code "anvil.compat.jenkins.plugins/register!"] "."]
           [:table
            [:thead [:tr [:th "Step name"] [:th "Count across jobs"]]]
            [:tbody
             (for [[name n] (sort-by val > (:unknown-names agg))]
               [:tr [:td [:code name]] [:td n]])]]])]))))
