(ns anvil.integration.github-subscriber
  "Wire the build lifecycle bus events to the GitHub Checks API
   (T3.4 of the v0.3 board).

   Subscribes to :build-started + :build-done on :global. For each
   event:

     - Look up the job's GitHub config (:repo + :checks-enabled?).
     - If GitHub integration is enabled for that job + a SHA is
       known, call create-check-run! or update-check-run!.
     - Publish :checks-updated on the [:job <name>] topic so the
       UI pill can update.

   The job's :head-sha is sourced from the build's parameters (the
   webhook receiver populates this when it triggers from a
   pull_request event). For manually-triggered builds without a
   :head-sha, the GitHub call is silently skipped — there's no PR
   to post against."
  (:require [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.integration.github :as gh]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

(defn- job-github-config
  "Look up GitHub integration config for `job-name` in anvil.edn:

       {:anvil.github/jobs {\"demo\" {:repo \"foo/bar\" :checks-enabled? true}}}

   Returns the inner map or nil."
  [job-name]
  (get-in (config/load-edn "anvil" {})
          [:anvil.github/jobs job-name]))

(defn- details-url-for [job-name build-number]
  ;; Hardcode localhost for v0.3.0; T3.x adds a public-URL config.
  (str "http://localhost:8765/jobs/" job-name "/" build-number))

(defn- on-build-started [event]
  (try
    (let [{:keys [job-name build-number parameters]} (:payload event)
          cfg (job-github-config job-name)]
      (when (and cfg (:checks-enabled? cfg) (:repo cfg))
        (let [sha (or (get parameters "GIT_COMMIT")
                      (get parameters "head_sha")
                      (:head-sha event))]
          (when sha
            (let [resp (gh/create-check-run!
                        {:repo (:repo cfg)
                         :name "anvil"
                         :head-sha sha
                         :status :in-progress
                         :details-url (details-url-for job-name build-number)})]
              (when-let [id (-> resp :parsed :id)]
                (gh/record-check-run! job-name build-number id (:repo cfg))
                (bus/publish! (topics/topic-job job-name)
                              {:type topics/evt-checks-updated
                               :job-name job-name
                               :build-number build-number
                               :sha sha
                               :status :in-progress
                               :check-run-url (str "https://github.com/"
                                                   (:repo cfg) "/runs/" id)})))))))
    (catch Throwable t
      (log/warn t "anvil.github-subscriber/on-build-started"))))

(defn- on-build-done [event]
  (try
    (let [{:keys [job-name build-number result]} (:payload event)
          info (gh/check-run-for job-name build-number)]
      (when info
        (let [conclusion (gh/build-result->conclusion result)
              resp (gh/update-check-run!
                    {:repo (:repo info)
                     :check-run-id (:check-run-id info)
                     :status :completed
                     :conclusion conclusion
                     :details-url (details-url-for job-name build-number)})]
          (when (:status resp)
            (bus/publish! (topics/topic-job job-name)
                          {:type topics/evt-checks-updated
                           :job-name job-name
                           :build-number build-number
                           :status :completed
                           :conclusion conclusion})))))
    (catch Throwable t
      (log/warn t "anvil.github-subscriber/on-build-done"))))

(defonce ^:private subscription-tokens (atom []))

(defn start!
  "Register the bus subscribers. Idempotent — calling twice rotates
   the subscriptions cleanly."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens
          [(bus/subscribe! topics/topic-global
                           (fn [ev]
                             (case (:type ev)
                               :build-started (on-build-started ev)
                               :build-done    (on-build-done ev)
                               nil)))]))

(defn stop! []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens []))
