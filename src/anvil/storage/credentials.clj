(ns anvil.storage.credentials
  "TX11D — Credentials store.

   Jenkins's `withCredentials(...)` step needs a way to map a
   credentialsId string to a real secret value at dispatch time. This
   module is anvil's backing store for that mapping.

   Storage model:
     anvil_credentials(id, credential_type, description, value_enc,
                       masked_value, created_at, updated_at)
     value_enc is base64(IV ‖ AES-GCM(value, master-key))

   Master-key resolution at first read/write of an encrypted value:
     1. `ANVIL_SECRET_KEY` env var (base64 of 32 raw bytes)
     2. `~/.config/anvil/master.key` (created 0600 with a fresh
        cryptographically-random key on first use)

   Credential types (v1):
     :string             — opaque secret string
     :username-password  — `username:password` stored as a single string;
                            withCredentials splits into USR_VAR/PSW_VAR

   Public API:
     (add! {:id … :type … :value … :description …})
     (lookup id)        → {:id … :type … :value … :description …}, or nil
     (list-all)         → [{:id … :type … :masked …}…] (NO value)
     (delete! id)       → nil

   The list/admin paths never return the decrypted value; only `lookup`
   does, and that's expected to be called only by the dispatcher's
   `h-with-credentials` handler with the value scoped to a single
   build's env."
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [anvil.storage.db :as db])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Master-key resolution
;; ---------------------------------------------------------------------------

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)
(def ^:private key-bytes 32)

(defn- env-key []
  (when-let [b64 (System/getenv "ANVIL_SECRET_KEY")]
    (try
      (let [decoded (.decode (Base64/getDecoder) ^String b64)]
        (when (= key-bytes (count decoded))
          decoded))
      (catch IllegalArgumentException _ nil))))

(defn- key-file-path []
  (io/file (str (System/getProperty "user.home") "/.config/anvil/master.key")))

(defn- generate-and-persist-key! []
  (let [^java.io.File f (key-file-path)
        bytes (byte-array key-bytes)
        _ (.nextBytes (SecureRandom.) bytes)
        b64 (.encodeToString (Base64/getEncoder) bytes)]
    (.mkdirs (.getParentFile f))
    (spit f (str b64 "\n"))
    (try
      (Files/setPosixFilePermissions (.toPath f)
                                     (PosixFilePermissions/fromString "rw-------"))
      (catch UnsupportedOperationException _
        ;; Non-POSIX filesystem; trust the default umask.
        nil))
    bytes))

(defn- file-key []
  (let [^java.io.File f (key-file-path)]
    (when (.exists f)
      (let [b64 (clojure.string/trim (slurp f))]
        (try
          (.decode (Base64/getDecoder) b64)
          (catch IllegalArgumentException _ nil))))))

(defn- resolve-master-key
  "Return the 32-byte master key (raw bytes). Resolution order:
   ANVIL_SECRET_KEY env > ~/.config/anvil/master.key > generate + persist."
  []
  (or (env-key)
      (file-key)
      (generate-and-persist-key!)))

(defonce ^:private master-key (delay (resolve-master-key)))

(defn reset-master-key-cache!
  "Re-resolve the master key on next encryption/decryption. Used by tests
   that bind ANVIL_SECRET_KEY via System/setProperty equivalents or
   manipulate the key file."
  []
  (alter-var-root #'master-key (constantly (delay (resolve-master-key)))))

;; ---------------------------------------------------------------------------
;; AES-GCM helpers
;; ---------------------------------------------------------------------------

(defn- cipher-for [mode iv]
  (let [c (Cipher/getInstance "AES/GCM/NoPadding")
        spec (SecretKeySpec. @master-key "AES")
        gcm  (GCMParameterSpec. gcm-tag-bits iv)]
    (.init c mode spec gcm)
    c))

(defn- encrypt-string ^String [^String plaintext]
  (let [iv (byte-array iv-bytes)
        _ (.nextBytes (SecureRandom.) iv)
        c (cipher-for Cipher/ENCRYPT_MODE iv)
        ct (.doFinal c (.getBytes plaintext "UTF-8"))
        combined (byte-array (+ iv-bytes (count ct)))]
    (System/arraycopy iv 0 combined 0 iv-bytes)
    (System/arraycopy ct 0 combined iv-bytes (count ct))
    (.encodeToString (Base64/getEncoder) combined)))

(defn- decrypt-string ^String [^String b64-combined]
  (let [combined (.decode (Base64/getDecoder) b64-combined)
        iv (byte-array iv-bytes)
        ct-len (- (count combined) iv-bytes)
        ct (byte-array ct-len)
        _ (System/arraycopy combined 0 iv 0 iv-bytes)
        _ (System/arraycopy combined iv-bytes ct 0 ct-len)
        c (cipher-for Cipher/DECRYPT_MODE iv)
        pt (.doFinal c ct)]
    (String. pt "UTF-8")))

;; ---------------------------------------------------------------------------
;; Masking
;; ---------------------------------------------------------------------------

(defn- mask-string [^String value]
  (cond
    (nil? value)         "***"
    (< (count value) 6)  "***"
    :else                (str "****" (subs value (- (count value) 4)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- ds [] (db/datasource))

(defn add!
  "Insert or replace a credential. `value` is the raw secret; gets
   encrypted on the way in."
  [{:keys [id type value description]
    :or {type :string}}]
  (when-not (and id value)
    (throw (ex-info "credentials/add! requires :id and :value"
                    {:id id :type type})))
  (let [encrypted (encrypt-string (str value))
        masked (mask-string (str value))]
    (jdbc/execute-one!
     (ds)
     ["INSERT INTO anvil_credentials
         (id, credential_type, description, value_enc, masked_value, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, datetime('now'), datetime('now'))
       ON CONFLICT(id) DO UPDATE SET
         credential_type = excluded.credential_type,
         description     = excluded.description,
         value_enc       = excluded.value_enc,
         masked_value    = excluded.masked_value,
         updated_at      = datetime('now')"
      (str id) (name type) (or description "") encrypted masked])
    {:id (str id) :type type :masked masked :description description}))

(defn lookup
  "Return the decrypted credential or nil. NEVER expose the result
   anywhere log-visible — the caller (h-with-credentials) is expected
   to scope it to a build's env and mask it in console output."
  [id]
  (when-let [row (jdbc/execute-one!
                  (ds)
                  ["SELECT id, credential_type, description, value_enc, masked_value
                    FROM anvil_credentials WHERE id = ?" (str id)])]
    {:id (:anvil_credentials/id row)
     :type (keyword (:anvil_credentials/credential_type row))
     :description (:anvil_credentials/description row)
     :masked (:anvil_credentials/masked_value row)
     :value (decrypt-string (:anvil_credentials/value_enc row))}))

(defn list-all
  "Listing of credentials — NEVER returns :value, only :masked."
  []
  (->> (jdbc/execute!
        (ds)
        ["SELECT id, credential_type, description, masked_value, created_at, updated_at
          FROM anvil_credentials ORDER BY id ASC"])
       (mapv (fn [r]
               {:id (:anvil_credentials/id r)
                :type (keyword (:anvil_credentials/credential_type r))
                :description (:anvil_credentials/description r)
                :masked (:anvil_credentials/masked_value r)
                :created-at (:anvil_credentials/created_at r)
                :updated-at (:anvil_credentials/updated_at r)}))))

(defn delete!
  "Remove a credential by id. No-op if it doesn't exist."
  [id]
  (jdbc/execute-one!
   (ds)
   ["DELETE FROM anvil_credentials WHERE id = ?" (str id)])
  nil)

(defn count-all []
  (let [r (jdbc/execute-one!
           (ds) ["SELECT COUNT(*) AS n FROM anvil_credentials"])]
    (or (:n r) 0)))
