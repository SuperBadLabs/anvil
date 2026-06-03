(ns anvil.web.views.jobs-page
  "Jobs list page + per-job page showing build history.

   TU3.1: per-job builds-table is a live widget — htmx-sse refreshes
   the table when this job emits :build-started or :build-done. The
   running build's row shows the live elapsed-time tick from TU2.7.

   TU3.6: jobs-list table grows a sparkline column."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.views.sparkline :as spark]
            [anvil.web.views.empty-state :as empty-state]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn- duration-str [ms]
  (cond
    (nil? ms)         "—"
    (< ms 1000)       (str ms " ms")
    (< ms 60000)      (str (format "%.1f" (/ ms 1000.0)) " s")
    :else             (str (long (/ ms 60000)) "m " (mod (long (/ ms 1000)) 60) "s")))

(defn- result-badge [b]
  (cond
    (:building? b)           [:span.badge.anim "running"]
    (= :success  (:result b)) [:span.badge.blue "success"]
    (= :failure  (:result b)) [:span.badge.red  "failure"]
    (= :unstable (:result b)) [:span.badge.yellow "unstable"]
    (= :aborted  (:result b)) [:span.badge.gray "aborted"]
    :else                     [:span.badge.gray "—"]))

;; ---------------------------------------------------------------------------
;; Jobs LIST page (with sparklines — TU3.6)
;; ---------------------------------------------------------------------------

(defn jobs-list [_req]
  (let [js (jobs/list-jobs)]
    (layout/page
     {:title "Jobs" :active :jobs}
     [:h2 "Jobs (" (count js) ")"]
     (if (empty? js)
       (empty-state/first-run-cta)
       [:table
        [:thead
         [:tr [:th "Name"] [:th "Status"] [:th "Builds"] [:th "Recent"]
          [:th "Last build"] [:th "Last success"] [:th "Last failure"]]]
        [:tbody
         (for [j js]
           [:tr
            [:td [:a {:href (str "/jobs/" (:name j))} (:name j)]]
            [:td (layout/badge-for-color (:color j))]
            [:td (count (:builds-by-number j))]
            [:td (or (spark/job-sparkline (:name j))
                     [:span.muted "—"])]
            [:td (if-let [lb (:last-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" lb)} (str "#" lb)]
                   [:span.muted "—"])]
            [:td (if-let [n (:last-successful-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" n)} (str "#" n)]
                   [:span.muted "—"])]
            [:td (if-let [n (:last-failed-build j)]
                   [:a {:href (str "/jobs/" (:name j) "/" n)} (str "#" n)]
                   [:span.muted "—"])]])]]))))

;; ---------------------------------------------------------------------------
;; Live build-history fragment (TU3.1)
;;
;; Same convention as TU1.6's dashboard stats fragment:
;;   - hx-ext sse + sse-connect to this job's topic
;;   - hx-trigger on the events that change the table
;;   - hx-get pulls the fragment endpoint, swaps outerHTML
;; The widget endpoint in anvil.web.widgets returns the same fragment.
;; ---------------------------------------------------------------------------

(defn builds-table-fragment
  "Render the build-history table as a self-contained live fragment.
   Used by both the full job page and the SSE-refresh widget endpoint
   /anvil/widgets/job-builds/:name."
  [job-name]
  (let [builds (jobs/list-builds-for-job job-name)
        topic-str (str "job:" job-name)]
    [:div.builds-table-frame
     {:id (str "builds-table-" job-name)
      :hx-ext "sse"
      :sse-connect (str "/anvil/events?topics=" topic-str)
      :hx-get (str "/anvil/widgets/job-builds/" job-name)
      :hx-trigger "sse:build-started, sse:build-done"
      :hx-swap "outerHTML"}
     (if (empty? builds)
       (empty-state/no-builds-cta job-name)
       [:table
        [:thead
         [:tr [:th "#"] [:th "Result"] [:th "Duration"] [:th "Started"] [:th "Ended"]]]
        [:tbody
         (for [b builds]
           [:tr
            [:td [:a {:href (str "/jobs/" job-name "/" (:number b))} "#" (:number b)]]
            [:td (result-badge b)]
            [:td (if (:building? b)
                   ;; Live elapsed timer (TU2.7 pattern, reused).
                   [:span.elapsed {:data-started-at-ms (some-> (:started-at b)
                                                               (.toEpochMilli) str)}
                    "…"]
                   (duration-str (:duration-ms b)))]
            [:td.muted (some-> (:started-at b) str)]
            [:td.muted (some-> (:ended-at b) str)]])]])]))

(defn job-detail [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (if-not job
      (layout/page
       {:title "Job not found" :active :jobs}
       [:h2 "Job " [:code job-name] " not found"]
       [:p [:a {:href "/jobs"} "← back to jobs"]])
      (layout/page
       {:title (str "Job " job-name) :active :jobs}
       [:h2 (:name job) " " (layout/badge-for-color (:color job))]
       [:p.muted
        (count (jobs/list-builds-for-job job-name)) " builds"
        " · "
        (or (spark/job-sparkline job-name {:n 50 :width-px 300 :height-px 36})
            "no history yet")]
       [:p
        [:a.btn-trigger {:href (str "/jobs/" job-name "/build-form")
                         :style "text-decoration:none;"}
         "▶ Build with parameters"]]
       ;; v0.3 T5.5 — Next scheduled run pill (when flag on + cron registered).
       (when (try ((requiring-resolve 'anvil.features/enabled?) :scheduler)
                  (catch Throwable _ false))
         (when-let [next-fire (try ((requiring-resolve 'anvil.scheduler.engine/next-fire-for) job-name)
                                   (catch Throwable _ nil))]
           [:p.next-run-pill
            [:strong "Next scheduled run: "]
            (str next-fire)]))
       [:h3 "Jenkinsfile"]
       [:pre.console (or (:jenkinsfile-source job) "")]
       [:h3 "Build history"]
       (builds-table-fragment job-name)))))
