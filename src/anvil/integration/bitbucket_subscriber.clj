(ns anvil.integration.bitbucket-subscriber
  "v0.5 T3.2 -- Bitbucket PR commit-status subscriber.

   Mirrors anvil.integration.gitlab-subscriber: subscribes to the global
   bus, dispatches :build-started to INPROGRESS and :build-done to final state.

   Wire-up in anvil.core (gated by :bitbucket-pr feature flag).

   Config (anvil.edn):

       {:anvil.bitbucket/jobs
        {\"my-job\" {:workspace  \"my-workspace\"
                    :repo-slug  \"my-repo\"
                    :checks-enabled? true}}}

   SHA injection: the subscriber reads :sha from the bus event payload.
   When :sha is absent no status is posted (honest per AV5-6)."
  (:require [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [anvil.config :as config]
            [anvil.integration.bitbucket :as bitbucket]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Config helpers
;; ---------------------------------------------------------------------------

(defn- bitbucket-jobs-config []
  (:anvil.bitbucket/jobs (config/load-edn "anvil" {})))

(defn- job-config [job-name]
  (get (bitbucket-jobs-config) job-name))

(defn- checks-enabled? [job-name]
  (when-let [cfg (job-config job-name)]
    (not (false? (:checks-enabled? cfg)))))

(defn- workspace-for [job-name]
  (:workspace (job-config job-name)))

(defn- repo-slug-for [job-name]
  (:repo-slug (job-config job-name)))

;; ---------------------------------------------------------------------------
;; Event handlers
;; ---------------------------------------------------------------------------

(defn- on-build-started [event]
  (try
    (when (features/enabled? :bitbucket-pr)
      (let [{:keys [job-name build-number sha]} (:payload event)]
        (if-not sha
          (log/warn "anvil.bitbucket-subscriber: no :sha in :build-started payload for"
                    job-name "#" build-number "; no status posted")
          (when (checks-enabled? job-name)
            (let [ws   (workspace-for job-name)
                  repo (repo-slug-for job-name)]
              (if-not (and ws repo)
                (log/warn "anvil.bitbucket-subscriber: missing :workspace or :repo-slug for"
                          job-name "; add :anvil.bitbucket/jobs in anvil.edn")
                (do
                  (bitbucket/record-sha! job-name build-number sha)
                  (let [resp (bitbucket/post-build-status!
                              {:workspace   ws
                               :repo-slug   repo
                               :sha         sha
                               :state       "INPROGRESS"
                               :description (str "anvil build #" build-number " running")})]
                    (log/debug "anvil.bitbucket-subscriber: posted INPROGRESS for"
                               job-name "#" build-number
                               "-> HTTP" (:status resp))))))))))
    (catch Throwable t
      (log/warn t "anvil.bitbucket-subscriber/on-build-started"))))

(defn- on-build-done [event]
  (try
    (when (features/enabled? :bitbucket-pr)
      (let [{:keys [job-name build-number sha result]} (:payload event)
            resolved-sha (or sha (bitbucket/sha-for job-name build-number))]
        (if-not resolved-sha
          (log/warn "anvil.bitbucket-subscriber: no :sha for :build-done"
                    job-name "#" build-number "; no status posted")
          (when (checks-enabled? job-name)
            (let [ws    (workspace-for job-name)
                  repo  (repo-slug-for job-name)
                  state (bitbucket/build-result->state result)]
              (if-not (and ws repo)
                (log/warn "anvil.bitbucket-subscriber: missing :workspace or :repo-slug for"
                          job-name)
                (let [resp (bitbucket/post-build-status!
                            {:workspace   ws
                             :repo-slug   repo
                             :sha         resolved-sha
                             :state       state
                             :description (str "anvil build #" build-number
                                               " -> " (name result))})]
                  ;; Publish :evt-pr-checked so SSE streams can notify the UI.
                  (bus/publish! (topics/topic-job job-name)
                                {:type         topics/evt-pr-checked
                                 :job-name     job-name
                                 :build-number build-number
                                 :state        state
                                 :http-status  (:status resp)})
                  (log/debug "anvil.bitbucket-subscriber: posted" state "for"
                             job-name "#" build-number
                             "-> HTTP" (:status resp)))))))))
    (catch Throwable t
      (log/warn t "anvil.bitbucket-subscriber/on-build-done"))))

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
  (log/info "anvil.bitbucket-subscriber: started"))

(defn stop!
  "Unsubscribe all Bitbucket-subscriber bus tokens."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens [])
  (log/info "anvil.bitbucket-subscriber: stopped"))
