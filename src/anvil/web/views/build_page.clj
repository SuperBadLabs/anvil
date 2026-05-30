(ns anvil.web.views.build-page
  "Per-build page — shows the full console log + metadata."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn build-detail [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))]
    (if-not b
      (layout/page
       {:title "Build not found" :active :jobs}
       [:h2 "Build " [:code (str job-name "#" n)] " not found"]
       [:p [:a {:href (str "/jobs/" job-name)} "← back to " job-name]])
      (layout/page
       {:title (str job-name " #" n) :active :jobs}
       [:h2 job-name " " [:code (str "#" n)]
        " "
        (cond
          (:building? b)         [:span.badge.anim "running"]
          (= :success (:result b)) [:span.badge.blue "success"]
          (= :failure (:result b)) [:span.badge.red  "failure"]
          :else                    [:span.badge.gray "—"])]
       [:p.muted
        [:a {:href (str "/jobs/" job-name)} (str "← " job-name)]
        " · "
        "started " (str (:started-at b))
        (when-let [e (:ended-at b)] (str " · ended " e))
        (when-let [d (:duration-ms b)] (str " · " d " ms"))]

       [:h3 "Console"]
       [:pre.console (jobs/console-log-for b)]

       (when (seq (:effects b))
         [:div
          [:h3 (str "Effects (" (count (:effects b)) ")")]
          [:pre.console (with-out-str
                          (binding [clojure.pprint/*print-right-margin* 120]
                            (clojure.pprint/pprint (:effects b))))]])

       (when (seq (:parameters b))
         [:div
          [:h3 "Parameters"]
          [:pre.console (with-out-str
                          (clojure.pprint/pprint (:parameters b)))]])))))
