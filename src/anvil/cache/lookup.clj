(ns anvil.cache.lookup
  "v0.5 T1.3 — thin facade over anvil.cache.local-store that exposes
   the cache hit/miss contract to the dispatcher layer.

   ## What this namespace does

   `hit?`    — is there a cached entry for this key?
   `fetch`   — return the cached payload map or nil
   `record!` — persist a completed step result into the store

   ## What it does NOT do

   - Key derivation (anvil.cache.key handles that)
   - Artifacts tar/untar (caller's responsibility — this ns returns the
     :artifacts-tgz-path from the store and lets the dispatcher unpack it)
   - Remote federation (T1.5 / :cache-remote flag — separate namespace)

   ## Config

   Reads anvil.edn once per daemon boot via anvil.config/load-edn.
   `(cache-opts)` returns a map of {:store-root :max-bytes} or {}.
   Tests inject opts directly via the two-arg arities."
  (:require [anvil.cache.local-store :as store]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(defn- cache-opts
  "Read the cache opts from anvil.edn.
   Keys:
     :anvil.cache/store-root — path string (default ~/.anvil/cache)
     :anvil.cache/max-bytes  — LRU cap in bytes (default nil = unbounded)"
  []
  (try
    (let [edn (config/load-edn "anvil" {})]
      (cond-> {}
        (:anvil.cache/store-root edn) (assoc :store-root (:anvil.cache/store-root edn))
        (:anvil.cache/max-bytes edn)  (assoc :max-bytes  (:anvil.cache/max-bytes edn))))
    (catch Throwable _ {})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn hit?
  "True if a cache entry exists for `key`. Does NOT validate payload
   completeness — use `fetch` for that."
  ([key] (hit? (cache-opts) key))
  ([opts key]
   (store/exists? opts key)))

(defn fetch
  "Return the cached payload map for `key`, or nil on miss.

   Returned map shape (same as `anvil.cache.local-store/lookup`):
     :stdout         <str>
     :stderr         <str>
     :exit           <int>
     :wall-ms        <long>
     :image-digest   <str>
     :command        <str>
     :created-at     <long>
     :artifacts-tgz-path  <str|nil>   — path to the .tgz on disk, if any"
  ([key] (fetch (cache-opts) key))
  ([opts key]
   (try
     (store/lookup opts key)
     (catch Throwable t
       (log/warn t (str "anvil.cache.lookup/fetch: error reading key " key "; treating as miss"))
       nil))))

(defn record!
  "Persist a completed step result into the local cache.

   `payload` shape (same as `anvil.cache.local-store/store!` accepts):
     :stdout         <str>
     :stderr         <str>
     :exit           <int>
     :wall-ms        <long>
     :image-digest   <str>      — for audit
     :command        <str>      — for audit
     :artifacts-tgz  <bytes>    — optional; caller produces from workspace
     :now            <long>     — timestamp (must be supplied by caller for
                                  testability — no System/currentTimeMillis inside)

   Returns the entry directory File on success. Errors are logged and
   swallowed — a failed write means the next run is a cache miss, never
   a corrupted result."
  ([key payload] (record! (cache-opts) key payload))
  ([opts key payload]
   (try
     (store/store! opts key payload)
     (catch Throwable t
       (log/warn t (str "anvil.cache.lookup/record!: error writing key " key "; skipping"))
       nil))))
