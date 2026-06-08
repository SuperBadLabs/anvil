(ns anvil.integration.gitlab-subscriber
  "v0.5 T3.1 -- GitLab MR commit-status subscriber.

   Mirrors anvil.integration.github-subscriber: subscribes to the global
   bus, dispatches :build-started to 'running' and :build-done to final state.

   Wire-up in anvil.core (gated by :gitlab-mr feature flag).

   Config (anvil.edn):

       {:anvil.gitlab/jobs
        {\"my-job\" {:project-id   \"42\"
                    :checks-enabled? true}}}

   SHA injection: the subscriber reads :sha from the bus event payload.
   When :sha is absent no status is posted (honest per AV5-6)."
  (:require [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [anvil.config :as config]
            [anvil.integration.gitlab :as gitlab]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Config helpers
;; ---------------------------------------------------------------------------

(defn- gitlab-jobs-config []
  (:anvil.gitlab/jobs (config/load-edn "anvil" {})))

(defn- job-config [job-name]
  (get (gitlab-jobs-config) job-name))

(defn- checks-enabled? [job-name]
  (when-let [cfg (job-config job-name)]
    (not (false? (:checks-enabled? cfg)))))

(defn- project-id [job-name]
  (:project-id (job-config job-name)))

;; ---------------------------------------------------------------------------
;; Event handlers
;; ---------------------------------------------------------------------------

(defn- on-build-started [event]
  (try
    (when (features/enabled? :gitlab-mr)
      (let [{:keys [job-name build-number sha]} (:payload event)]
        (if-not sha
          (log/warn "anvil.gitlab-subscriber: no :sha in :build-started payload for"
                    job-name "#" build-number "; no status posted")
          (when (checks-enabled? job-name)
            (let [pid (project-id job-name)]
              (if-not pid
                (log/warn "anvil.gitlab-subscriber: no :project-id configured for"
                          job-name "; add :anvil.gitlab/jobs config in anvil.edn")
                (do
                  ;; Record SHA so on-build-done can look it up.
                  (gitlab/record-sha! job-name build-number sha)
                  (let [resp (gitlab/post-commit-status!
                              {:project-id  pid
                               :sha         sha
                               :state       "running"
                               :description (str "anvil build #" build-number " running")})]
                    (log/debug "anvil.gitlab-subscriber: posted 'running' for"
                               job-name "#" build-number
                               "-> HTTP" (:status resp))))))))))
    (catch Throwable t
      (log/warn t "anvil.gitlab-subscriber/on-build-started"))))

(defn- on-build-done [event]
  (try
    (when (features/enabled? :gitlab-mr)
      (let [{:keys [job-name build-number sha result]} (:payload event)
            resolved-sha (or sha (gitlab/sha-for job-name build-number))]
        (if-not resolved-sha
          (log/warn "anvil.gitlab-subscriber: no :sha for :build-done"
                    job-name "#" build-number "; no status posted")
          (when (checks-enabled? job-name)
            (let [pid   (project-id job-name)
                  state (gitlab/build-result->state result)]
              (if-not pid
                (log/warn "anvil.gitlab-subscriber: no :project-id configured for"
                          job-name)
                (let [resp (gitlab/post-commit-status!
                            {:project-id  pid
                             :sha         resolved-sha
                             :state       state
                             :description (str "anvil build #" build-number
                                               " -> " (name result))})]
                  ;; Publish :evt-mr-checked so SSE streams can notify the UI.
                  (bus/publish! (topics/topic-job job-name)
                                {:type         topics/evt-mr-checked
                                 :job-name     job-name
                                 :build-number build-number
                                 :state        state
                                 :http-status  (:status resp)})
                  (log/debug "anvil.gitlab-subscriber: posted" state "for"
                             job-name "#" build-number
                             "-> HTTP" (:status resp)))))))))
    (catch Throwable t
      (log/warn t "anvil.gitlab-subscriber/on-build-done"))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private subscription-tokens (atom []))

(defn start!
  "Subscribe to the global bus for :build-started / :build-done.
   Idempotent via stop!/reset first."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens
          [(bus/subscribe! topics/topic-global
                           (fn [ev]
                             (case (:type ev)
                               :build-started (on-build-started ev)
                               :build-done    (on-build-done ev)
                               nil)))])
  (log/info "anvil.gitlab-subscriber: started"))

(defn stop!
  "Unsubscribe all GitLab-subscriber bus tokens."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens [])
  (log/info "anvil.gitlab-subscriber: stopped"))
