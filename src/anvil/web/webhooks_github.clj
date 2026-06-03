(ns anvil.web.webhooks-github
  "Webhook receiver for github push + pull_request events (T3.3).

   Mounted at POST /anvil/webhooks/github. Each request is verified
   against the configured webhook secret (HMAC-SHA256). For events
   we care about, the receiver maps the repo → job (per
   :anvil.github/jobs in anvil.edn) and triggers a build at the
   referenced SHA.

   Unmatched events return 200 — github retries on non-2xx, and
   we don't want to chunk our log on uninteresting pings."
  (:require [clojure.data.json :as json]
            [anvil.integration.github :as gh]
            [anvil.config :as config]
            [anvil.web.jenkins-api.jobs :as jobs]
            [taoensso.timbre :as log]))

(defn- read-body-string [req]
  (let [b (:body req)]
    (cond
      (string? b) b
      (nil? b) ""
      :else (slurp b))))

(defn- repo->job-name
  "Reverse-lookup the job mapped to a given github repo
   (\"owner/name\"). Returns nil if no job is configured."
  [repo]
  (let [jobs-cfg (get (config/load-edn "anvil" {}) :anvil.github/jobs)]
    (some (fn [[job-name cfg]]
            (when (= repo (:repo cfg)) job-name))
          jobs-cfg)))

(defn- handle-pull-request [payload]
  (let [action (get payload :action)
        repo   (get-in payload [:repository :full_name])
        head   (get-in payload [:pull_request :head :sha])
        ref    (get-in payload [:pull_request :head :ref])]
    (when (and (#{"opened" "synchronize" "reopened"} action)
               repo head)
      (let [job-name (repo->job-name repo)]
        (when job-name
          (log/info "anvil.github webhook: triggering" job-name
                    "for PR head" head)
          (try
            (jobs/record-build-start! job-name
                                      {:parameters {"GIT_COMMIT" head
                                                    "GIT_BRANCH" ref
                                                    "TRIGGER" "github-pull-request"}})
            (catch Throwable t
              (log/warn t "anvil.github webhook: build trigger failed"))))))))

(defn- handle-push [payload]
  (let [repo (get-in payload [:repository :full_name])
        head (get payload :after)
        ref  (get payload :ref)]
    (when (and repo head)
      (let [job-name (repo->job-name repo)]
        (when job-name
          (log/info "anvil.github webhook: triggering" job-name
                    "for push head" head)
          (try
            (jobs/record-build-start! job-name
                                      {:parameters {"GIT_COMMIT" head
                                                    "GIT_BRANCH" ref
                                                    "TRIGGER" "github-push"}})
            (catch Throwable t
              (log/warn t "anvil.github webhook: build trigger failed"))))))))

(defn- feature-on? []
  (try
    ((requiring-resolve 'anvil.features/enabled?) :pr-checks)
    (catch Throwable _ false)))

(defn handler
  "Ring handler for POST /anvil/webhooks/github.

   Returns 503 when :anvil.features/pr-checks is disabled — kept as a
   stable URL so github keeps retrying once the operator enables the
   feature and configures the secret."
  [req]
  (if-not (feature-on?)
    {:status 503
     :headers {"Content-Type" "text/plain"}
     :body "anvil: :pr-checks feature disabled (toggle :anvil.features/pr-checks in anvil.edn)"}
    (let [body (read-body-string req)
          sig  (get-in req [:headers "x-hub-signature-256"])
          event (get-in req [:headers "x-github-event"])]
      (if-not (gh/verify-webhook-signature body sig)
        {:status 401
         :headers {"Content-Type" "text/plain"}
         :body "webhook signature mismatch"}
        (let [payload (try (json/read-str body :key-fn keyword)
                           (catch Throwable _ {}))]
          (case event
            "pull_request" (handle-pull-request payload)
            "push"         (handle-push payload)
            nil)
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:received event})})))))
