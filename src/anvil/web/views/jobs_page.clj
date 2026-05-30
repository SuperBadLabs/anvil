(ns anvil.web.views.jobs-page
  "Jobs list page + per-job page showing build history."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn- duration-str [ms]
  (cond
    (nil? ms)         "—"
    (< ms 1000)       (str ms " ms")
    (< ms 60000)      (str (format "%.1f" (/ ms 1000.0)) " s")
    :else             (str (long (/ ms 60000)) "m " (mod (long (/ ms 1000)) 60) "s")))

(defn jobs-list [_req]
  (let [js (jobs/list-jobs)]
    (layout/page
     {:title "Jobs" :active :jobs}
     [:h2 "Jobs (" (count js) ")"]
     (if (empty? js)
       [:p.muted "No jobs registered yet. Use "
        [:code "anvil import jenkinsfile <path>"] " to register one."]
       [:table
        [:thead
         [:tr [:th "Name"] [:th "Status"] [:th "Builds"] [:th "Last build"] [:th "Last success"] [:th "Last failure"]]]
        [:tbody
         (for [j js]
           [:tr
            [:td [:a {:href (str "/jobs/" (:name j))} (:name j)]]
            [:td (layout/badge-for-color (:color j))]
            [:td (count (:builds-by-number j))]
            [:td (if-let [lb (:last-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" lb)} (str "#" lb)]
                   [:span.muted "—"])]
            [:td (if-let [n (:last-successful-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" n)} (str "#" n)]
                   [:span.muted "—"])]
            [:td (if-let [n (:last-failed-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" n)} (str "#" n)]
                   [:span.muted "—"])]])]]))))

(defn job-detail [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (if-not job
      (layout/page
       {:title "Job not found" :active :jobs}
       [:h2 "Job " [:code job-name] " not found"]
       [:p [:a {:href "/jobs"} "← back to jobs"]])
      (let [builds (jobs/list-builds-for-job job-name)]
        (layout/page
         {:title (str "Job " job-name) :active :jobs}
         [:h2 (:name job) " " (layout/badge-for-color (:color job))]
         [:p.muted (count builds) " builds"]
         [:h3 "Jenkinsfile"]
         [:pre.console (or (:jenkinsfile-source job) "")]
         [:h3 "Build history"]
         (if (empty? builds)
           [:p.muted "No builds yet. Trigger one via the Jenkins REST shim or "
            [:code "anvil build " job-name] " (when the CLI lands)."]
           [:table
            [:thead
             [:tr [:th "#"] [:th "Result"] [:th "Duration"] [:th "Started"] [:th "Ended"]]]
            [:tbody
             (for [b builds]
               [:tr
                [:td [:a {:href (str "/jobs/" job-name "/" (:number b))} "#" (:number b)]]
                [:td (cond
                       (:building? b)         [:span.badge.anim "running"]
                       (= :success (:result b)) [:span.badge.blue "success"]
                       (= :failure (:result b)) [:span.badge.red  "failure"]
                       (= :unstable (:result b)) [:span.badge.yellow "unstable"]
                       (= :aborted (:result b)) [:span.badge.gray "aborted"]
                       :else                    [:span.badge.gray "—"])]
                [:td (duration-str (:duration-ms b))]
                [:td.muted (some-> (:started-at b) str)]
                [:td.muted (some-> (:ended-at b) str)]])]]))))))
