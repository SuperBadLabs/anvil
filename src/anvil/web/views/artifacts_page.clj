(ns anvil.web.views.artifacts-page
  "Build artifacts list view (TU3.5).

   /jobs/<name>/<n>/artifacts  — table of files matching any
   archiveArtifacts() glob this build configured, with deep-link
   permalinks to the artifact-serve endpoint."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.build-summary :as summary]))

(defn- size-str [^long b]
  (cond
    (< b 1024)               (str b " B")
    (< b (* 1024 1024))      (str (format "%.1f" (/ b 1024.0)) " KiB")
    (< b (* 1024 1024 1024)) (str (format "%.1f" (/ b 1024.0 1024.0)) " MiB")
    :else                    (str (format "%.2f" (/ b 1024.0 1024.0 1024.0)) " GiB")))

(defn page [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))
        arts (when b (summary/artifacts-for b))]
    (if-not b
      (layout/page
       {:title "Build not found" :active :jobs}
       [:h2 "Build " [:code (str job-name "#" n)] " not found"])
      (layout/page
       {:title (str job-name " #" n " — artifacts") :active :jobs}
       [:h2 job-name " " [:code (str "#" n)] " — artifacts"]
       [:p.muted
        [:a {:href (str "/jobs/" job-name "/" n)} "← back to build"]]
       (cond
         (nil? arts)
         [:p.muted
          "No artifacts archived by this build. Add "
          [:code "archiveArtifacts 'path/to/*.jar'"]
          " to a "
          [:code "post { always { ... } }"]
          " block."]

         (empty? arts)
         [:p.muted "Archive globs were configured but no matching files exist in the workspace."]

         :else
         [:table
          [:thead [:tr [:th "Path"] [:th "Size"] [:th "Download"]]]
          [:tbody
           (for [{:keys [rel-path size-bytes]} arts]
             [:tr
              [:td [:code rel-path]]
              [:td (size-str size-bytes)]
              [:td [:a {:href (str "/jobs/" job-name "/" n "/artifact/" rel-path)
                        :download (str job-name "-" n "-" rel-path)}
                    "↓ download"]]])]])))))
