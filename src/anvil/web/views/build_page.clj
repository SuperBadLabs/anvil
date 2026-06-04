(ns anvil.web.views.build-page
  "Per-build page — TU3.2 + TU3.3.

   Top stripe: build #, status badge, timing.
   Toolbar: link to console, retry button, link to compare, artifacts.
   Body: steps timeline (per-stage rollup), parameters block,
         param-diff vs last-successful build (honest v1 of 'env-diff').
   Effects block: keep, but folded by default — useful for
                  engine-level debugging."
  (:require [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.build-summary :as summary]
            [anvil.web.views.test-results :as test-results-view]
            [anvil.web.views.problems :as problems-view]
            [anvil.storage.test-results :as tr-store]
            [anvil.storage.problems :as problems-store]
            [anvil.integration.github :as gh]
            [anvil.features :as features]))

(defn- result-badge [b]
  (cond
    (:building? b)              [:span.badge.anim "running"]
    (= :success     (:result b)) [:span.badge.blue "success"]
    (= :failure     (:result b)) [:span.badge.red  "failure"]
    (= :unstable    (:result b)) [:span.badge.yellow "unstable"]
    (= :aborted     (:result b)) [:span.badge.gray "aborted"]
    ;; AN4 added :neutral and :unsupported as honest result classes.
    ;; :neutral renders muted (empty walk, did nothing) and
    ;; :unsupported renders amber (agent/step the runner can't honor).
    ;; Neither colors red — these are NOT failures, they're
    ;; capability gaps the operator should see.
    (= :neutral     (:result b)) [:span.badge.gray "neutral"]
    (= :unsupported (:result b)) [:span.badge.amber "unsupported"]
    :else                        [:span.badge.gray "—"]))

(defn- result-explain-banner
  "AN4-5: when a build is NOT :success, render a short banner with the
   classifier's :rule and :explain so operators can see at a glance
   WHY the build was reclassified. Returns nil for green builds."
  [{:keys [result classify-rule classify-explain]}]
  (when (and (not= :success result) classify-explain)
    (let [css-class (case result
                      :failure     "result-banner result-banner--failure"
                      :unstable    "result-banner result-banner--unstable"
                      :aborted     "result-banner result-banner--aborted"
                      :neutral     "result-banner result-banner--neutral"
                      :unsupported "result-banner result-banner--unsupported"
                      "result-banner")]
      [:div {:class css-class}
       [:strong (str (name (or result :unknown)) " · "
                     (some-> classify-rule name))]
       [:span " — " classify-explain]])))

(defn- step-row [{:keys [cmd exit cwd]}]
  [:tr
   [:td (cond
          (nil? exit)             [:span.badge.gray "—"]
          (zero? exit)            [:span.badge.blue "ok"]
          :else                   [:span.badge.red  (str "exit " exit)])]
   [:td [:code cmd]]
   [:td.muted (or cwd "")]])

(defn- stage-block [{:keys [stage steps failed?]}]
  [:details.stage-fold {:open (or failed? false)}
   [:summary
    [:span (or stage "<unnamed>")]
    [:span.stage-meta
     (str (count steps) " step" (when-not (= 1 (count steps)) "s"))
     (when failed? " — failed")]]
   (if (empty? steps)
     [:p.muted "(no commands recorded for this stage)"]
     [:table
      [:thead [:tr [:th "Status"] [:th "Command"] [:th "Cwd"]]]
      [:tbody (for [s steps] (step-row s))]])])

(defn- param-diff-block [b last-green]
  (let [diff (summary/param-diff (:parameters last-green) (:parameters b))
        empty? (and (empty? (:added diff))
                    (empty? (:removed diff))
                    (empty? (:changed diff)))]
    (when last-green
      [:div
       [:h3 "Parameter diff vs last green ("
        [:a {:href (str "/jobs/" (:job-name b) "/" (:number last-green))}
         "#" (:number last-green)] ")"]
       (cond
         (= (:number b) (:number last-green))
         [:p.muted "(this IS the last green build)"]

         empty?
         [:p.muted "no parameter differences"]

         :else
         [:dl.diff-list
          (concat
           (for [[k v] (:added diff)]
             [:div
              [:dt [:span.badge.blue "added"] " " [:code (str k)]]
              [:dd [:code (pr-str v)]]])
           (for [[k v] (:removed diff)]
             [:div
              [:dt [:span.badge.gray "removed"] " " [:code (str k)]]
              [:dd [:code (pr-str v)]]])
           (for [[k [old new]] (:changed diff)]
             [:div
              [:dt [:span.badge.yellow "changed"] " " [:code (str k)]]
              [:dd
               [:code.muted (pr-str old)] " → " [:code (pr-str new)]]]))])
       [:p.muted "(Param diff shown today; full env-var diff lands once "
        [:code "build-env"] " values are persisted with each build — TU3.x.)"]])))

(defn build-detail [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))
        b (when b (assoc b :job-name job-name))
        last-green-n (when b
                       (let [job (jobs/find-job job-name)
                             lsb (:last-successful-build job)]
                         (when (and lsb (not= lsb (:number b))) lsb)))
        last-green (when last-green-n (jobs/find-build job-name last-green-n))
        stages (when b (summary/steps-by-stage (:effects b)))
        agg (when b (summary/step-summary (:effects b)))]
    (if-not b
      (layout/page
       {:title "Build not found" :active :jobs}
       [:h2 "Build " [:code (str job-name "#" n)] " not found"]
       [:p [:a {:href (str "/jobs/" job-name)} "← back to " job-name]])
      (layout/page
       {:title (str job-name " #" n) :active :jobs}
       [:h2 job-name " " [:code (str "#" n)] " " (result-badge b)]
       (result-explain-banner b)
       [:p.muted
        [:a {:href (str "/jobs/" job-name)} (str "← " job-name)]
        " · "
        (or (:stage-count agg) 0) " stage" (when-not (= 1 (:stage-count agg)) "s")
        " · "
        (or (:step-count agg) 0) " step" (when-not (= 1 (:step-count agg)) "s")
        (when (pos? (or (:failed-step-count agg) 0))
          [:span " · " [:span.badge.red (str (:failed-step-count agg) " failed")]])
        " · "
        "started " (str (:started-at b))
        (when-let [e (:ended-at b)] (str " · ended " e))
        (when-let [d (:duration-ms b)] (str " · " d " ms"))]

       ;; v0.3 T3.6 — PR-check pill. Shown when :pr-checks flag is on
       ;; AND the bus subscriber recorded a check-run for this build.
       (when (features/enabled? :pr-checks)
         (when-let [cr (gh/check-run-for job-name n)]
           [:p.pr-check-pill
            [:strong "GitHub check: "]
            [:a {:href (str "https://github.com/" (:repo cr)
                            "/runs/" (:check-run-id cr))
                 :target "_blank" :rel "noopener"}
             (str (:repo cr) " #" (:check-run-id cr))]]))

       ;; Toolbar (TU3 navigation strip)
       [:div.console-toolbar
        [:a {:href (str "/jobs/" job-name "/" n "/console")} "📜 Console"]
        " · "
        (when last-green-n
          [:a {:href (str "/jobs/" job-name "/" n "/compare?vs=" last-green-n)}
           "⟷ Compare vs last green (#" last-green-n ")"])
        " · "
        [:a {:href (str "/jobs/" job-name "/" n "/artifacts")} "📦 Artifacts"]
        " · "
        ;; TU3.3 retry button. POST → redirect to the new build page.
        ;; htmx posts so the page doesn't navigate away yet — server
        ;; sends an HX-Redirect header to bounce.
        [:button {:type "button"
                  :hx-post (str "/jobs/" job-name "/" n "/retry")
                  :hx-confirm (str "Retry build #" n " with the same parameters?")
                  :class "btn-retry"}
         "↻ Retry build"]]

       ;; v0.3 T1.4 — Test-results panel. Renders only when the
       ;; :junit feature flag is enabled AND a summary row was
       ;; persisted by T1.6's h-junit handler. Otherwise the panel
       ;; returns nil and the page is unchanged from v0.2.
       (when (features/enabled? :junit)
         (let [summary (tr-store/find-summary job-name n)]
           (when summary
             (test-results-view/panel
              {:summary summary
               :results        (tr-store/find-results job-name n)
               :failed-results (tr-store/find-failed-results job-name n)
               :history        (tr-store/recent-summaries job-name 30)}))))

       ;; v0.3 T2.4 — Problems panel. Same posture: only when
       ;; :problem-matchers flag is on AND the log-tail extension
       ;; persisted at least one matched diagnostic.
       (when (features/enabled? :problem-matchers)
         (let [problems (problems-store/find-problems job-name n)]
           (problems-view/panel
            {:summary (problems-store/find-summary job-name n)
             :problems problems})))

       [:h3 "Steps"]
       (if (seq stages)
         (for [s stages] (stage-block s))
         [:p.muted "No stages recorded yet."])

       (param-diff-block b last-green)

       (when (seq (:parameters b))
         [:div
          [:h3 "Parameters (this build)"]
          [:pre.console (with-out-str
                          (clojure.pprint/pprint (:parameters b)))]])

       (when (seq (:effects b))
         [:details
          [:summary [:h3 {:style "display:inline"}
                     (str "Raw effects (" (count (:effects b)) ")")]]
          [:pre.console (with-out-str
                          (binding [clojure.pprint/*print-right-margin* 120]
                            (clojure.pprint/pprint (:effects b))))]])))))
