(ns anvil.integration.github
  "GitHub Checks API integration (T3 of the v0.3 board).

   ## v0.3.0 scope

   - **Auth: PAT-only**. Token sourced from `ANVIL_GITHUB_TOKEN`
     env-var or `:anvil.github/token` in anvil.edn. App auth (JWT
     signing → installation token) defers to v0.3.x — the protocol
     is documented in docs/pr-checks/github-app-setup.md so
     operators can pre-plan.
   - **Checks API**: POST/PATCH /repos/{repo}/check-runs.
   - **Webhook**: POST /anvil/webhooks/github receives pull_request
     and push events; HMAC-SHA256 signature verification when a
     secret is configured.
   - **Bus subscriber**: lifecycle hook on :build-started /
     :build-done converts build state into checks-api calls and
     publishes :checks-updated (T0.4 reserved event).

   ## Design

   Every HTTP-touching function takes an optional `:http-fn`
   parameter (defaults to `default-http-fn`) so tests can inject
   a recording mock. This keeps the namespace hermetic-by-default
   while making real HTTP one config step away.

   GitHub Checks API docs:
   https://docs.github.com/en/rest/checks/runs"
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [anvil.config :as config]
            [taoensso.timbre :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Auth (T3.1)
;; ---------------------------------------------------------------------------

(defn token
  "Return the configured GitHub PAT, or nil. Lookup order:
     1. ANVIL_GITHUB_TOKEN env var
     2. :anvil.github/token from anvil.edn"
  []
  (or (System/getenv "ANVIL_GITHUB_TOKEN")
      (:anvil.github/token (config/load-edn "anvil" {}))))

(defn webhook-secret
  "HMAC-SHA256 secret for verifying github webhooks."
  []
  (or (System/getenv "ANVIL_GITHUB_WEBHOOK_SECRET")
      (:anvil.github/webhook-secret (config/load-edn "anvil" {}))))

;; ---------------------------------------------------------------------------
;; Webhook signature verification (T3.3)
;; ---------------------------------------------------------------------------

(defn- hex [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [^byte byte b]
      (.append sb (format "%02x" (bit-and byte 0xff))))
    (.toString sb)))

(defn hmac-sha256
  "Compute HMAC-SHA256(secret, msg) → hex string."
  [^String secret ^String msg]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (hex (.doFinal mac (.getBytes msg "UTF-8")))))

(defn- constant-time-eq?
  "Constant-time string equality. Length-aware first."
  [^String a ^String b]
  (and (= (count a) (count b))
       (zero? (reduce bit-or 0
                      (map (fn [ca cb] (bit-xor (int ca) (int cb))) a b)))))

(defn verify-webhook-signature
  "Returns true if `X-Hub-Signature-256` matches HMAC-SHA256(secret, body).
   When no webhook secret is configured, returns true with a WARN log
   (signature-not-required mode for dev)."
  [^String body ^String sig-header]
  (let [secret (webhook-secret)]
    (cond
      (nil? secret)
      (do (log/warn "anvil.github: webhook received without ANVIL_GITHUB_WEBHOOK_SECRET set; accepting unverified")
          true)

      (or (nil? sig-header) (not (str/starts-with? sig-header "sha256=")))
      false

      :else
      (let [provided (subs sig-header (count "sha256="))
            expected (hmac-sha256 secret body)]
        (constant-time-eq? expected provided)))))

;; ---------------------------------------------------------------------------
;; Checks API client (T3.2)
;; ---------------------------------------------------------------------------

(defn default-http-fn
  "Synchronous http-kit/client call wrapper."
  [opts]
  (let [resp @(http/request opts)]
    {:status (:status resp)
     :body (cond
             (string? (:body resp)) (:body resp)
             (some? (:body resp)) (try (slurp (:body resp)) (catch Throwable _ nil))
             :else nil)
     :error (:error resp)}))

(defn- api-headers []
  {"Authorization" (str "Bearer " (token))
   "Accept" "application/vnd.github+json"
   "X-GitHub-Api-Version" "2022-11-28"
   "User-Agent" "anvil-ci"})

(defn- norm-status [s]
  (some-> s name (str/replace "-" "_")))

(defn create-check-run!
  "POST /repos/{owner/repo}/check-runs."
  [{:keys [repo http-fn] :or {http-fn default-http-fn} :as params}]
  (let [body {:name        (:name params "anvil")
              :head_sha    (:head-sha params)
              :status      (norm-status (:status params))
              :conclusion  (norm-status (:conclusion params))
              :details_url (:details-url params)
              :output      (:output params)}
        body-json (json/write-str (into {} (remove (fn [[_ v]] (nil? v)) body)))
        resp (http-fn {:method :post
                       :url (str "https://api.github.com/repos/" repo "/check-runs")
                       :headers (api-headers)
                       :body body-json})]
    (when (and (:status resp) (>= (:status resp) 400))
      (log/warn "anvil.github: create-check-run!" (:status resp) (:body resp)))
    (cond-> resp
      (:body resp) (assoc :parsed (try (json/read-str (:body resp) :key-fn keyword)
                                       (catch Throwable _ nil))))))

(defn update-check-run!
  "PATCH /repos/{owner/repo}/check-runs/{check-run-id}."
  [{:keys [repo check-run-id http-fn] :or {http-fn default-http-fn} :as params}]
  (let [body {:status      (norm-status (:status params))
              :conclusion  (norm-status (:conclusion params))
              :details_url (:details-url params)
              :output      (:output params)}
        body-json (json/write-str (into {} (remove (fn [[_ v]] (nil? v)) body)))
        resp (http-fn {:method :patch
                       :url (str "https://api.github.com/repos/" repo
                                 "/check-runs/" check-run-id)
                       :headers (api-headers)
                       :body body-json})]
    (when (and (:status resp) (>= (:status resp) 400))
      (log/warn "anvil.github: update-check-run!" (:status resp) (:body resp)))
    (cond-> resp
      (:body resp) (assoc :parsed (try (json/read-str (:body resp) :key-fn keyword)
                                       (catch Throwable _ nil))))))

;; ---------------------------------------------------------------------------
;; Build lifecycle → check-run state machine (T3.4)
;; ---------------------------------------------------------------------------

(defonce ^:private build->check-run
  ;; Map of [job-name build-number] -> {:check-run-id N :repo STR}
  (atom {}))

(defn record-check-run! [job-name build-number check-run-id repo]
  (swap! build->check-run assoc [job-name build-number]
         {:check-run-id check-run-id :repo repo}))

(defn check-run-for [job-name build-number]
  (get @build->check-run [job-name build-number]))

(defn clear-check-runs! []
  (reset! build->check-run {}))

(defn build-result->conclusion
  "Maps an anvil build :result keyword to a GitHub Checks API
   :conclusion value."
  [r]
  (case r
    :success   :success
    :failure   :failure
    :unstable  :neutral
    :aborted   :cancelled
    :timed-out :timed-out
    :neutral))
