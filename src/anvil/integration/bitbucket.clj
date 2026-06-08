(ns anvil.integration.bitbucket
  "Bitbucket Cloud Build Status API (v0.5 T3.2).

   Posts build start/end status to Bitbucket commit status endpoints.
   Bitbucket Cloud uses the Commit Status API under:
     POST /2.0/repositories/:workspace/:repo_slug/commit/:commit/statuses/build

   ## Auth

   Token sourced from ANVIL_BITBUCKET_TOKEN env var or :anvil.bitbucket/token
   in anvil.edn. Bitbucket expects an OAuth2 Bearer token or an App Password
   encoded as Base64 'user:password'. For simplicity we support:
     - Bearer: pass a repository/workspace-scoped OAuth2 access token
     - Basic:  set ANVIL_BITBUCKET_USER + ANVIL_BITBUCKET_APP_PASSWORD env vars

   ## Config

   Per-job config in anvil.edn:

       {:anvil.bitbucket/jobs
        {\"demo\" {:workspace  \"my-workspace\"
                  :repo-slug  \"my-repo\"
                  :checks-enabled? true}}}

   ## API docs

   https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/

   Every HTTP call accepts :http-fn injection for tests (same pattern as
   anvil.integration.github and anvil.integration.gitlab)."
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Auth helpers
;; ---------------------------------------------------------------------------

(defn- bearer-token []
  (or (System/getenv "ANVIL_BITBUCKET_TOKEN")
      (:anvil.bitbucket/token (config/load-edn "anvil" {}))))

(defn- basic-user []
  (System/getenv "ANVIL_BITBUCKET_USER"))

(defn- basic-password []
  (System/getenv "ANVIL_BITBUCKET_APP_PASSWORD"))

(defn- auth-header
  "Build the Authorization header value. Prefers Bearer; falls back to Basic."
  []
  (let [token (bearer-token)
        user  (basic-user)
        pass  (basic-password)]
    (cond
      (seq token) (str "Bearer " token)
      (and (seq user) (seq pass))
      (let [encoded (.encodeToString
                     (java.util.Base64/getEncoder)
                     (.getBytes (str user ":" pass) "UTF-8"))]
        (str "Basic " encoded))
      :else nil)))

(defn base-url
  "Bitbucket base URL. Defaults to the public cloud API. Override with
   ANVIL_BITBUCKET_URL or :anvil.bitbucket/base-url in anvil.edn for
   Bitbucket Data Center / self-hosted instances."
  []
  (or (System/getenv "ANVIL_BITBUCKET_URL")
      (:anvil.bitbucket/base-url (config/load-edn "anvil" {}))
      "https://api.bitbucket.org"))

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
  (let [auth (auth-header)]
    (cond-> {"Content-Type" "application/json"
             "User-Agent"   "anvil-ci"}
      auth (assoc "Authorization" auth))))

;; ---------------------------------------------------------------------------
;; Build Status API
;; ---------------------------------------------------------------------------

;; Bitbucket build status states: INPROGRESS, SUCCESSFUL, FAILED, STOPPED
;; Reference: https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/

(defn build-result->state
  "Map an anvil build :result keyword to a Bitbucket commit status state string."
  [result]
  (case result
    :success  "SUCCESSFUL"
    :failure  "FAILED"
    :unstable "FAILED"
    :aborted  "STOPPED"
    :neutral  "SUCCESSFUL"  ; neutral = ran but no opinion → success-ish
    "FAILED"))              ; unknown → FAILED (honest)

(defn post-build-status!
  "POST /2.0/repositories/:workspace/:repo_slug/commit/:commit/statuses/build

   Params:
     :workspace   — Bitbucket workspace slug
     :repo-slug   — repository slug
     :sha         — full commit SHA
     :state       — 'INPROGRESS' | 'SUCCESSFUL' | 'FAILED' | 'STOPPED'
     :key         — (optional) unique check key; defaults to 'anvil'
     :name        — (optional) display name; defaults to 'anvil CI'
     :description — (optional) short description
     :url         — (optional) link to the anvil build page
     :http-fn     — (injectable) defaults to default-http-fn

   Returns {:status INT :body STR :error ...} plus :parsed (JSON-decoded body)."
  [{:keys [workspace repo-slug sha state key name description url http-fn]
    :or {http-fn default-http-fn
         key     "anvil"
         name    "anvil CI"}}]
  (let [endpoint (str (base-url) "/2.0/repositories/"
                      workspace "/" repo-slug "/commit/" sha "/statuses/build")
        body (cond-> {:key   key
                      :state state
                      :name  name
                      :url   (or url (str (base-url) "/"))}
               description (assoc :description description))
        resp (http-fn {:method  :post
                       :url     endpoint
                       :headers (api-headers)
                       :body    (json/write-str body)})]
    (when (and (:status resp) (>= (:status resp) 400))
      (log/warn "anvil.bitbucket: post-build-status!" (:status resp) (:body resp)
                "workspace=" workspace "repo=" repo-slug "sha=" sha))
    (cond-> resp
      (:body resp) (assoc :parsed (try (json/read-str (:body resp) :key-fn keyword)
                                       (catch Throwable _ nil))))))

;; ---------------------------------------------------------------------------
;; In-memory state: build → commit SHA
;; ---------------------------------------------------------------------------

(defonce ^:private build->sha (atom {}))

(defn record-sha! [job-name build-number sha]
  (swap! build->sha assoc [job-name build-number] sha))

(defn sha-for [job-name build-number]
  (get @build->sha [job-name build-number]))

(defn clear-shas! []
  (reset! build->sha {}))
