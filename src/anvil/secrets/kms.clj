(ns anvil.secrets.kms
  "T2.3 — Cloud-KMS adapter for the SecretBackend protocol.

   ## Model

   Unlike Vault — where the secret VALUE lives in Vault — the KMS
   adapter stores a base64'd **encrypted blob** in `anvil.edn` (which
   can live in Git). The blob is decrypted at resolve time via the
   cloud KMS Decrypt API. Plaintext never lives at rest.

   ## anvil.edn shape

       {:anvil.features/cloud-kms-backend true
        :anvil.kms/provider :aws            ; :aws | :gcp | :azure
        :anvil.kms/region   \"us-east-1\"    ; AWS-only
        :anvil.kms/blobs    {\"gh-token\"
                              {:ciphertext-b64 \"AQICAH...\"
                               :type :string}
                             \"docker-creds\"
                              {:ciphertext-b64 \"AQICAH...\"
                               :type :username-password}}}

   For AWS KMS specifically, the ciphertext-b64 is whatever
   `aws kms encrypt --plaintext file://secret --key-id <arn>` emits
   (the `CiphertextBlob` field, base64'd as the CLI prints).

   ## v0.6 provider matrix

     :aws    — first-class. Decrypt via the AWS SDK if it's on the
               classpath, else error at install! time with a clear
               'add com.cognitect.aws/{api,kms} to project.clj'
               message. Per AV6-3, anvil ships the protocol +
               reference impl; the operator picks their SDK.
     :gcp    — stub. install! emits a WARN; resolve! returns nil for
               every id. v0.6.x lands the real impl.
     :azure  — stub. Same posture as :gcp.

   ## Lazy SDK loading (AV6-3 anti-goal)

   `requiring-resolve` for `cognitect.aws.client.api/client` happens at
   adapter construction. An operator who runs Vault-only never pays
   the AWS-SDK jar weight because this ns is only loaded when
   `:cloud-kms-backend` is on.

   ## Secret-leak invariant

   Same as the Vault adapter: the decrypted plaintext lives only in
   the {:value … :type …} return map of resolve!. No log call carries
   the value. The :secret-resolved event published by
   `anvil.secrets/resolve-with-emit!` includes only credential-id +
   backend kind + latency-ms."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [anvil.secrets :as secrets])
  (:import (java.nio ByteBuffer)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; AWS KMS decrypt — lazy SDK lookup
;; ---------------------------------------------------------------------------

(defn ^:no-doc aws-kms-client
  "Construct an AWS KMS client via cognitect.aws-api if available.
   Returns the client object or throws ex-info with operator-actionable
   guidance.  The SDK is NOT in anvil's runtime deps — operators who
   opt into KMS add it themselves.  Per AV6-3, anvil stays thin."
  [region]
  (let [client-fn (try (requiring-resolve 'cognitect.aws.client.api/client)
                       (catch Throwable _ nil))]
    (when-not client-fn
      (throw (ex-info
              (str "anvil.secrets.kms: AWS SDK not on classpath. "
                   "Add [com.cognitect.aws/api \"0.8.711\"] and "
                   "[com.cognitect.aws/kms \"857.2.1908.0\"] to project.clj "
                   "(or your preferred AWS SDK; the adapter only needs "
                   "the `:Decrypt` operation).")
              {:fix-add-deps true})))
    (client-fn {:api :kms
                :region region})))

(defn ^:no-doc aws-decrypt
  "Call KMS Decrypt with the given base64 ciphertext blob. Returns the
   plaintext string. Throws on KMS error — the caller (resolve!)
   catches and returns nil for backend-fallback semantics."
  [client ciphertext-b64]
  (let [invoke (requiring-resolve 'cognitect.aws.client.api/invoke)
        ct (.decode (Base64/getDecoder) ^String ciphertext-b64)
        resp (invoke client {:op :Decrypt
                             :request {:CiphertextBlob ct}})]
    (when (:cognitect.anomalies/category resp)
      (throw (ex-info "KMS Decrypt anomaly"
                      {:category (:cognitect.anomalies/category resp)
                       :message (:cognitect.anomalies/message resp)})))
    (let [pt (:Plaintext resp)]
      (cond
        (string? pt) pt
        (bytes? pt)  (String. ^bytes pt "UTF-8")
        (instance? ByteBuffer pt)
        (let [^ByteBuffer bb pt
              arr (byte-array (.remaining bb))]
          (.get bb arr)
          (String. arr "UTF-8"))
        :else (throw (ex-info "KMS Decrypt returned unexpected :Plaintext type"
                              {:type (some-> pt class .getName)}))))))

;; ---------------------------------------------------------------------------
;; Backend record
;; ---------------------------------------------------------------------------

(defrecord KmsBackend [config client]
  secrets/SecretBackend
  (resolve! [_ id]
    (let [entry (get-in config [:blobs id])
          ct (:ciphertext-b64 entry)
          provider (:provider config :aws)]
      (cond
        (nil? entry) nil
        (str/blank? ct) nil

        (= :aws provider)
        (try
          (let [pt (aws-decrypt client ct)]
            {:value pt
             :type  (keyword (or (:type entry) "string"))})
          (catch Throwable t
            (log/warn t "anvil.secrets.kms: AWS decrypt failed for" id)
            nil))

        :else
        (do (log/warn "anvil.secrets.kms: provider" provider
                      "is a v0.6.x stub — returning nil for" id)
            nil))))

  (list-ids [_]
    ;; KMS-backed credentials are declared in anvil.edn, so the id list
    ;; is exactly the keys of :blobs. No network call required.
    (vec (keys (:blobs config {})))))

(defn make-backend
  "Construct a KmsBackend. For :aws, the AWS SDK client is constructed
   eagerly at install! time so an SDK-missing misconfiguration fails
   loud at startup rather than at first credential resolve.

   Required keys:
     :provider     #{:aws :gcp :azure}
     :region       (required for :aws)
     :blobs        {credentialId → {:ciphertext-b64 STR :type KW?}}"
  [{:keys [provider region] :as config}]
  (when-not provider
    (throw (ex-info "anvil.secrets.kms/make-backend requires :provider"
                    {:got-keys (vec (keys config))})))
  (let [client (case provider
                 :aws (do
                        (when (str/blank? region)
                          (throw (ex-info "anvil.secrets.kms: :region required for :aws"
                                          {:config-keys (vec (keys config))})))
                        (aws-kms-client region))
                 (:gcp :azure)
                 (do (log/warn "anvil.secrets.kms: provider" provider
                               "is a v0.6.x stub — resolve! will return nil")
                     nil)
                 (throw (ex-info "anvil.secrets.kms: unsupported :provider"
                                 {:provider provider
                                  :supported #{:aws :gcp :azure}})))]
    (with-meta (->KmsBackend config client)
      {:anvil.secrets/kind :kms})))

(defn install!
  "Construct a KmsBackend from anvil.edn config and register it.
   Called at startup when :cloud-kms-backend is on.

   anvil.edn keys read:
     :anvil.kms/provider   default :aws
     :anvil.kms/region     required for :aws
     :anvil.kms/blobs      map of credentialId → {:ciphertext-b64 :type?}"
  []
  (let [load-edn (requiring-resolve 'anvil.config/load-edn)
        edn (load-edn "anvil" {})
        cfg {:provider (get edn :anvil.kms/provider :aws)
             :region   (get edn :anvil.kms/region)
             :blobs    (get edn :anvil.kms/blobs {})}]
    (try
      (secrets/register-backend! (make-backend cfg))
      (log/info "anvil.secrets.kms: backend installed —"
                "provider=" (:provider cfg)
                "region=" (:region cfg)
                "blob-count=" (count (:blobs cfg)))
      (catch Throwable t
        (log/warn t "anvil.secrets.kms: install failed — leaving previous backend active")))))
