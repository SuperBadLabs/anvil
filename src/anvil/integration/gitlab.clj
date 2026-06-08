(ns anvil.integration.gitlab
  "GitLab MR Commit Status API (v0.5 T3.1).

   Posts build start/end status to GitLab merge request commits. Uses
   GitLab's Commit Status API (POST /projects/:id/statuses/:sha) which
   GitLab MRs automatically surface as a check under 'Pipelines'.

   ## Auth

   Token sourced from `ANVIL_GITLAB_TOKEN` env var or `:anvil.gitlab/token`
   in anvil.edn. The token needs `api` scope (read+write) or at minimum
   `write_repository` + `read_api`.

   ## Config

   Per-job config in anvil.edn:

       {:anvil.gitlab/jobs
        {\"demo\" {:project-id   \"42\"
                  :checks-enabled? true}}}

   Or use the project's path: `project-id` accepts both numeric IDs
   and URL-encoded paths (`\"group%2Frepo\"`).

   ## API docs

   https://docs.gitlab.com/ee/api/commits.html#post-the-build-status-to-a-commit

   Every HTTP call accepts `:http-fn` injection for tests (same pattern
   as anvil.integration.github)."
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Auth
;; ---------------------------------------------------------------------------

(defn token
  "Return the configured GitLab token, or nil.
   Lookup: ANVIL_GITLAB_TOKEN env → :anvil.gitlab/token in anvil.edn."
  []
  (or (System/getenv "ANVIL_GITLAB_TOKEN")
      (:anvil.gitlab/token (config/load-edn "anvil" {}))))

(defn base-url
  "GitLab base URL. Defaults to gitlab.com; override with ANVIL_GITLAB_URL
   or :anvil.gitlab/base-url in anvil.edn for self-hosted instances."
  []
  (or (System/getenv "ANVIL_GITLAB_URL")
      (:anvil.gitlab/base-url (config/load-edn "anvil" {}))
      "https://gitlab.com"))

;; ---------------------------------------------------------------------------
;; HTTP client
;; ---------------------------------------------------------------------------

(defn default-http-fn
  "Synchronous http-kit wrapper — same shape as anvil.integration.github."
  [opts]
  (let [resp @(http/request opts)]
    {:status (:status resp)
     :body   (cond
               (string? (:body resp))  (:body resp)
               (some? (:body resp))    (try (slurp (:body resp)) (catch Throwable _ nil))
               :else nil)
     :error (:error resp)}))

(defn- api-headers []
  {"PRIVATE-TOKEN"  (str (token))
   "Content-Type"   "application/json"
   "User-Agent"     "anvil-ci"})

;; ---------------------------------------------------------------------------
;; Commit Status API
;; ---------------------------------------------------------------------------

;; GitLab commit status states:
;;   pending, running, success, failed, canceled
;; Reference: https://docs.gitlab.com/ee/api/commits.html#post-the-build-status-to-a-commit

(defn build-result->state
  "Map an anvil build :result keyword to a GitLab commit status state string."
  [result]
  (case result
    :success  "success"
    :failure  "failed"
    :unstable "failed"
    :aborted  "canceled"
    :neutral  "success"   ; neutral = ran but no opinion → success-ish
    "failed"))            ; unknown → failed (honest)

(defn post-commit-status!
  "POST /projects/:id/statuses/:sha — create or update a commit status.

   Params:
     :project-id   — GitLab numeric project ID or URL-encoded path
     :sha          — full commit SHA
     :state        — 'pending' | 'running' | 'success' | 'failed' | 'canceled'
     :name         — (optional) check name; defaults to 'anvil'
     :description  — (optional) short description
     :target-url   — (optional) link to the anvil build page
     :http-fn      — (injectable) defaults to default-http-fn

   Returns the raw response map {:status INT :body STR :error ...} plus
   :parsed (JSON-decoded body)."
  [{:keys [project-id sha state name description target-url http-fn]
    :or {http-fn default-http-fn
         name "anvil"}}]
  (let [url  (str (base-url) "/api/v4/projects/"
                  (java.net.URLEncoder/encode (str project-id) "UTF-8")
                  "/statuses/" sha)
        body (cond-> {:state state :name name}
               description (assoc :description description)
               target-url  (assoc :target_url target-url))
        resp (http-fn {:method  :post
                       :url     url
                       :headers (api-headers)
                       :body    (json/write-str body)})]
    (when (and (:status resp) (>= (:status resp) 400))
      (log/warn "anvil.gitlab: post-commit-status!" (:status resp) (:body resp)
                "project-id=" project-id "sha=" sha))
    (cond-> resp
      (:body resp) (assoc :parsed (try (json/read-str (:body resp) :key-fn keyword)
                                       (catch Throwable _ nil))))))

;; ---------------------------------------------------------------------------
;; In-memory state: build → commit SHA (for on-build-done lookup)
;; ---------------------------------------------------------------------------

(defonce ^:private build->sha (atom {}))

(defn record-sha! [job-name build-number sha]
  (swap! build->sha assoc [job-name build-number] sha))

(defn sha-for [job-name build-number]
  (get @build->sha [job-name build-number]))

(defn clear-shas! []
  (reset! build->sha {}))
