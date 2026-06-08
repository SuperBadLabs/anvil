(ns anvil.secrets.vault
  "T2.2 — Hashicorp Vault adapter for the SecretBackend protocol.

   Mode: **KV v2**. Vault's most common engine. Operators who run
   KV v1 can override `:anvil.vault/kv-version` to `1`; the path
   shape is different (no /data/ segment) but the response parsing
   stays.

   Configuration (`anvil.edn`):

       {:anvil.features/vault-backend  true
        :anvil.vault/url               \"https://vault.example.com\"
        :anvil.vault/token-path        \"/var/run/secrets/vault-token\"
        :anvil.vault/kv-mount          \"secret\"      ; default
        :anvil.vault/kv-path-prefix    \"anvil/\"      ; default \"\"
        :anvil.vault/kv-version        2               ; default 2
        :anvil.vault/timeout-s         5}              ; default 5

   ## Token bootstrap

   The Vault token is read from `:anvil.vault/token-path` at adapter
   construction AND on every resolve! call (re-read so a sidecar
   that rotates the file is honoured without a restart). The token-
   file itself is populated by Vault's standard auth flow — typically
   AppRole-with-secret-id or Kubernetes service-account token — which
   anvil does NOT implement. AV6-3 keeps the adapter minimal: token
   bootstrap is operator-shaped, not anvil-shaped.

   See `docs/secrets/vault-kms-backends.md` for sidecar-pattern
   examples (AppRole, k8s serviceaccount, dev-mode root token).

   ## Read path

   For credentialId `gh-token` with prefix `anvil/` and mount `secret`,
   the KV v2 read URL is:

       GET <url>/v1/secret/data/anvil/gh-token

   Expected response (KV v2 envelope):

       {\"data\": {\"data\": {\"value\": \"ghp_...\",
                              \"type\":  \"string\"}}}

   - The inner `value` field is the secret string.
   - The optional `type` field maps onto SecretBackend's :type
     (:string / :username-password / :file). Defaults to :string.

   ## Errors

   - 404                → resolve! returns nil (let the dispatcher
                           emit :credential-unresolved as today).
   - Non-2xx other      → resolve! returns nil + logs at WARN. We
                           don't throw because a Vault outage
                           shouldn't crash the dispatcher; the
                           credential just appears unresolved.
   - I/O / parse error  → same as non-2xx.

   ## Secret-leak invariant

   resolve!'s return map is the ONLY place the value lives in this
   ns. No log/info or log/debug call carries the value. The
   :secret-resolved audit event is emitted by `anvil.secrets/resolve-
   with-emit!` and includes only credential-id + backend + latency."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [anvil.secrets :as secrets])
  (:import (java.net URI)
           (java.net.http HttpClient
                          HttpRequest
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

;; ---------------------------------------------------------------------------
;; HTTP client (process-lifetime singleton)
;; ---------------------------------------------------------------------------

(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- read-token
  "Read the token from `token-path`. Returns the trimmed string or
   nil if the file is missing / empty / unreadable. NEVER logs the
   token value — only its presence / absence."
  [token-path]
  (try
    (let [f (java.io.File. (str token-path))]
      (when (.exists f)
        (let [t (str/trim (slurp f))]
          (when-not (str/blank? t)
            t))))
    (catch Throwable t
      (log/warn t "anvil.secrets.vault: token-file read failed at" token-path)
      nil)))

(defn- read-url
  "Compute the KV v2 read URL for `id` under the configured mount +
   prefix. KV v1 omits the `/data/` segment."
  [{:keys [url kv-mount kv-path-prefix kv-version]} id]
  (let [seg (if (= 1 kv-version) "" "data/")
        prefix (or kv-path-prefix "")
        pth (str (str/replace url #"/+$" "")
                 "/v1/" kv-mount "/" seg prefix id)]
    pth))

(defn- parse-kv2-response
  "Walk the KV v2 envelope and return {:value <str> :type <kw>} or nil.

   v2 shape: {\"data\": {\"data\": {<fields>}, \"metadata\": {...}}}
   v1 shape: {\"data\": {<fields>}}

   We accept either by trying v2 first then falling back to v1 — the
   outer caller could also choose, but this is forgiving and the
   misconfiguration cost is just a one-time lookup miss."
  [body kv-version]
  (try
    (let [parsed (json/read-str body :key-fn keyword)
          fields (or (when (= 1 kv-version) (:data parsed))
                     (get-in parsed [:data :data])
                     (:data parsed))]
      (when (map? fields)
        (when-let [v (:value fields)]
          {:value (str v)
           :type  (keyword (or (:type fields) "string"))})))
    (catch Throwable t
      (log/warn t "anvil.secrets.vault: response parse failed")
      nil)))

(defn ^:no-doc send-get
  "Single GET call to Vault. Returns the java.net.http.HttpResponse or
   nil on transport failure. The token is sent in the standard
   X-Vault-Token header per Vault's API contract."
  [config token id]
  (try
    (let [uri (URI/create (read-url config id))
          req (-> (HttpRequest/newBuilder)
                  (.uri uri)
                  (.timeout (Duration/ofSeconds (long (or (:timeout-s config) 5))))
                  (.header "X-Vault-Token" ^String token)
                  (.header "Accept" "application/json")
                  (.GET)
                  (.build))]
      (.send ^HttpClient http-client req
             (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))
    (catch Throwable t
      (log/warn t "anvil.secrets.vault: GET failed for id" id)
      nil)))

;; ---------------------------------------------------------------------------
;; Backend record
;; ---------------------------------------------------------------------------

(defrecord VaultBackend [config]
  secrets/SecretBackend
  (resolve! [_ id]
    (let [token-path (:token-path config)
          token (when token-path (read-token token-path))]
      (cond
        (nil? token)
        (do (log/warn "anvil.secrets.vault: no token at" token-path
                      "— treating" id "as unresolved")
            nil)

        :else
        (let [^java.net.http.HttpResponse resp (send-get config token id)
              status (some-> resp .statusCode)]
          (cond
            (nil? resp) nil
            (= 404 status) nil
            (and (number? status) (<= 200 status 299))
            (parse-kv2-response (.body resp) (or (:kv-version config) 2))

            :else
            (do (log/warn "anvil.secrets.vault: non-2xx for" id "—" status)
                nil))))))

  (list-ids [_]
    ;; Vault's KV v2 LIST endpoint returns the IDs under a path.
    ;; Surfaced through `list-ids` so the admin UI can enumerate
    ;; without ever fetching values. This is best-effort — on
    ;; failure return [] rather than throwing.
    (try
      (let [token (some-> (:token-path config) read-token)]
        (if-not token
          []
          (let [base (read-url (assoc config :kv-version 2) "")
                ;; KV v2 LIST uses /metadata/ in place of /data/
                list-url (-> base
                             (str/replace #"/data/" "/metadata/")
                             (str/replace #"/data$" "/metadata"))
                uri (URI/create list-url)
                req (-> (HttpRequest/newBuilder)
                        (.uri uri)
                        (.timeout (Duration/ofSeconds
                                   (long (or (:timeout-s config) 5))))
                        (.header "X-Vault-Token" ^String token)
                        (.method "LIST"
                                 (java.net.http.HttpRequest$BodyPublishers/noBody))
                        (.build))
                resp (.send ^HttpClient http-client req
                            (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
                status (.statusCode resp)]
            (if (and (number? status) (<= 200 status 299))
              (let [parsed (json/read-str (.body resp) :key-fn keyword)
                    keys' (or (get-in parsed [:data :keys]) [])]
                (vec keys'))
              []))))
      (catch Throwable t
        (log/debug t "anvil.secrets.vault: list-ids failed (returning [])")
        []))))

(defn make-backend
  "Construct a VaultBackend from operator config (a map drawn from
   anvil.edn). Defaults populate kv-mount, kv-version, timeout. The
   backend is tagged with :anvil.secrets/kind :vault for audit events.

   Required keys:
     :url         — base Vault URL (no trailing slash needed)
     :token-path  — filesystem path to a file containing the token

   Optional keys:
     :kv-mount         (default \"secret\")
     :kv-path-prefix   (default \"\")
     :kv-version       (default 2)
     :timeout-s        (default 5)"
  [{:keys [url token-path] :as config}]
  (when-not (and url token-path)
    (throw (ex-info "anvil.secrets.vault/make-backend requires :url + :token-path"
                    {:got-keys (vec (keys config))})))
  (let [normalised (merge {:kv-mount "secret"
                           :kv-path-prefix ""
                           :kv-version 2
                           :timeout-s 5}
                          config)]
    (with-meta (->VaultBackend normalised)
      {:anvil.secrets/kind :vault})))

(defn install!
  "Construct a VaultBackend from anvil.edn config and register it as
   the active backend. Called at startup when `:vault-backend` is on.

   Looks up keys under the `anvil.vault` ns:
     :anvil.vault/url
     :anvil.vault/token-path
     :anvil.vault/kv-mount
     :anvil.vault/kv-path-prefix
     :anvil.vault/kv-version
     :anvil.vault/timeout-s"
  []
  (let [load-edn (requiring-resolve 'anvil.config/load-edn)
        edn (load-edn "anvil" {})
        cfg {:url            (get edn :anvil.vault/url)
             :token-path     (get edn :anvil.vault/token-path)
             :kv-mount       (get edn :anvil.vault/kv-mount "secret")
             :kv-path-prefix (get edn :anvil.vault/kv-path-prefix "")
             :kv-version     (get edn :anvil.vault/kv-version 2)
             :timeout-s      (get edn :anvil.vault/timeout-s 5)}]
    (if (and (:url cfg) (:token-path cfg))
      (do (secrets/register-backend! (make-backend cfg))
          (log/info "anvil.secrets.vault: backend installed —"
                    "url=" (:url cfg)
                    "kv-mount=" (:kv-mount cfg)
                    "kv-prefix=" (:kv-path-prefix cfg)
                    "kv-version=" (:kv-version cfg)))
      (log/warn "anvil.secrets.vault: :vault-backend feature flag is on but"
                ":anvil.vault/url and/or :anvil.vault/token-path are missing"
                "— leaving the local-disk backend active"))))
