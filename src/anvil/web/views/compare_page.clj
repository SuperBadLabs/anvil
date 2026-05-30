(ns anvil.web.views.compare-page
  "Side-by-side comparison of two builds (TU3.4).

   /jobs/<name>/<n>/compare?vs=<m>  — defaults to last-successful

   Renders each stage twice: same row, two columns. Per-stage:
   step count, failed-count badge, list of commands with exit codes.
   The two columns sit at exactly the same vertical position so the
   diffs are obvious at a glance."
  (:require [clojure.set :as set]
            [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.build-summary :as summary]))

(defn- stage-card [s]
  (when s
    [:div.stage-card
     {:class (when (:failed? s) "failed")}
     [:div.stage-card-head
      [:strong (or (:stage s) "<unnamed>")]
      " "
      [:span.muted (count (:steps s)) " step"
       (when-not (= 1 (count (:steps s))) "s")]]
     (if (empty? (:steps s))
       [:p.muted "(no commands)"]
       [:ul.steps-mini
        (for [st (:steps s)]
          [:li
           [:span.badge {:class (cond
                                  (nil? (:exit st)) "gray"
                                  (zero? (:exit st)) "blue"
                                  :else "red")}
            (if (some? (:exit st)) (str "exit " (:exit st)) "—")]
           " "
           [:code (:cmd st)]])])]))

(defn- aligned-stages [a-stages b-stages]
  ;; Align by stage name in the order they appear in `b` (the newer
  ;; build). Stages only in `a` come at the end as orphans.
  (let [a-by (into {} (map (juxt :stage identity) a-stages))
        b-names (map :stage b-stages)
        b-set (set b-names)
        orphans (filter #(not (b-set (:stage %))) a-stages)]
    (concat
     (map (fn [bs]
            {:name (:stage bs)
             :a (a-by (:stage bs))
             :b bs})
          b-stages)
     (map (fn [as]
            {:name (:stage as)
             :a as
             :b nil})
          orphans))))

(defn page [req]
  (let [job-name (get-in req [:path-params :name])
        n  (try (Integer/parseInt (str (get-in req [:path-params :number])))
                (catch Exception _ nil))
        vs-raw (get-in req [:query-params "vs"])
        b (when n (jobs/find-build job-name n))
        job (jobs/find-job job-name)
        vs-n (or (when vs-raw
                   (try (Integer/parseInt vs-raw) (catch Exception _ nil)))
                 (:last-successful-build job))
        a (when vs-n (jobs/find-build job-name vs-n))
        a-stages (when a (summary/steps-by-stage (:effects a)))
        b-stages (when b (summary/steps-by-stage (:effects b)))
        aligned (aligned-stages (or a-stages []) (or b-stages []))]
    (cond
      (nil? b)
      (layout/page
       {:title "Build not found" :active :jobs}
       [:h2 "Build " [:code (str job-name "#" n)] " not found"])

      (nil? a)
      (layout/page
       {:title (str "Compare " job-name " #" n) :active :jobs}
       [:h2 "Compare " job-name " #" n]
       [:p.muted "No build to compare against. Pass "
        [:code "?vs=&lt;number&gt;"] " in the URL, or run a successful build first."]
       [:p [:a {:href (str "/jobs/" job-name "/" n)} "← back to #" n]])

      :else
      (layout/page
       {:title (str "Compare " job-name " #" n " vs #" vs-n) :active :jobs}
       [:h2 "Compare " job-name " "
        [:code (str "#" vs-n)] " ⟷ " [:code (str "#" n)]]
       [:p.muted
        [:a {:href (str "/jobs/" job-name "/" n)} (str "← back to #" n)]
        " · "
        [:span [:code (str "#" vs-n)] " " (some-> a :result name) " · "
         (or (:duration-ms a) "—") " ms"]
        " vs "
        [:span [:code (str "#" n)] " " (some-> b :result name) " · "
         (or (:duration-ms b) "—") " ms"]]

       [:div.compare-grid
        [:div.compare-head [:strong (str "#" vs-n)]]
        [:div.compare-head [:strong (str "#" n)]]
        (for [{:keys [a b]} aligned]
          (list
           [:div.compare-col (stage-card a)]
           [:div.compare-col (stage-card b)]))]))))
