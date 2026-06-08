(ns anvil.cache.key
  "v0.5 T1.1 — content-addressed cache key derivation.

   Per AV5-2, the cache is keyed at the STEP level (not the build
   level). A cache key uniquely identifies the input set to a single
   docker-backed step:

     key = sha256(image-digest ‖ command ‖ sorted-env ‖ input-tree-merkle)

   Invariants (R1):
     - Reorder of env vars does NOT change the key (sorted before hash)
     - Removing an env var DOES change the key (different sorted list)
     - Trailing whitespace in the command DOES change the key (commands
       are not normalized — operators see exactly what they wrote)
     - Input-tree merkle is the SHA-256 of the sorted (path, content-sha)
       pairs; an empty tree hashes to a stable zero-byte digest
     - A nil image-digest is rejected — caching against \"some maven image
       version\" without pinning the digest is a footgun (R1)

   The key is hex-string-encoded. SHA-256 → 64 hex chars. Prefix slicing
   is a presentation concern, not a key-derivation concern; callers do
   their own (subs k 0 N) when bucketing on disk.

   This namespace is pure. No IO. No globals. Test it like a math fn."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn- bytes->hex
  "Convert a byte-array to a lower-case hex string."
  ^String [^bytes ba]
  (let [sb (StringBuilder. (* 2 (alength ba)))]
    (doseq [^byte b ba]
      (let [v (bit-and b 0xff)]
        (.append sb (format "%02x" v))))
    (.toString sb)))

(defn- sha256
  "Hash one or more UTF-8 strings (concatenated with a NUL separator) and
   return the hex digest. NUL is rejected as a separator byte inside the
   inputs because it would let an attacker collide
   `[\"a\\0b\" \"c\"]` with `[\"a\" \"b\\0c\"]`; if a real input contains
   NUL we throw rather than silently merge boundaries."
  ^String [& parts]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [^String p parts]
      (when (some #(= 0 (int %)) p)
        (throw (ex-info "cache key part contains NUL — refuse to hash"
                        {:part p})))
      (.update md (.getBytes p StandardCharsets/UTF_8))
      (.update md (byte-array [(byte 0)])))
    (bytes->hex (.digest md))))

(defn- sorted-env-string
  "Canonicalize an env map to a deterministic string. nil → empty.
   Keys are coerced to strings via `name` (handles keywords) or `str`."
  [env]
  (->> (or env {})
       (map (fn [[k v]]
              [(if (keyword? k) (name k) (str k))
               (str v)]))
       (sort-by first)
       (mapcat (fn [[k v]] [k "=" v ";"]))
       (apply str)))

(defn- tree-merkle-string
  "Canonicalize an input tree to a deterministic string.

   Input shape: `[[path content-sha256-hex] ...]` — a vector of
   pairs. The hash incorporates each pair sorted by path, with each
   pair separated by a record separator. Empty tree → empty string.

   This is intentionally a SHA-OF-TUPLES, not a sha-of-files: callers
   compute the per-file content hash; this namespace only commits to
   their ordering. That keeps cache-key derivation IO-free."
  [tree]
  (->> (or tree [])
       (sort-by first)
       (mapcat (fn [[path sha]] [path "" sha ""]))
       (apply str)))

(defn derive
  "Compute the cache key for a single step.

   Inputs:
     :image-digest  — the docker image's content digest (e.g.
                      `sha256:abc123…`). REQUIRED, non-nil. Bare image
                      tags (`maven:3.9-eclipse-temurin-21`) are NOT
                      sufficient — they're mutable; resolve to a digest
                      first.
     :command       — the literal shell command. Whitespace-sensitive.
     :env           — a map (or nil) of env vars set for this step
                      (excluding anvil-internal ones).
     :input-tree    — a vector of `[path content-sha256-hex]` pairs
                      representing the relevant inputs from the
                      workspace. Caller decides what's relevant; the
                      cache only commits to the set.

   Returns: 64-char lowercase hex SHA-256.

   Throws: ex-info with `:reason :missing-image-digest` if image-digest
   is nil or blank — refusing the cache footgun (R1)."
  [{:keys [image-digest command env input-tree]}]
  (when (or (nil? image-digest) (str/blank? image-digest))
    (throw (ex-info "cache key requires a non-blank :image-digest"
                    {:reason :missing-image-digest
                     :got image-digest})))
  (sha256 image-digest
          (or command "")
          (sorted-env-string env)
          (tree-merkle-string input-tree)))

(defn parts-equal?
  "Diagnostic helper: are two key-input maps equivalent for hashing
   purposes?  Useful in tests that want to assert two seemingly-
   different shapes hash to the same key (env reorder, etc.)."
  [a b]
  (= (derive a) (derive b)))
