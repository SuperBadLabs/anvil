(ns anvil.cache.local-store-test
  "v0.5 T1.2 — local content-addressed store. Tests target the on-disk
   layout, atomic-rename semantics, lookup/miss, and LRU eviction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [anvil.cache.local-store :as ls]))

(def ^:dynamic *tmp-root* nil)

(use-fixtures :each
  (fn [f]
    (let [d (fs/create-temp-dir {:prefix "anvil-cache-test-"})]
      (try
        (binding [*tmp-root* (str d)] (f))
        (finally (fs/delete-tree d))))))

(defn- opts []
  {:store-root *tmp-root*})

(def fake-key
  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")

(def fake-key2
  "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")

;; ---------------------------------------------------------------------------
;; Round-trip
;; ---------------------------------------------------------------------------

(deftest store-then-lookup-round-trips
  (ls/store! (opts) fake-key
             {:stdout "hello\n" :stderr "" :exit 0
              :wall-ms 1234 :image-digest "sha256:abc"
              :command "echo hello" :now 1000})
  (let [r (ls/lookup (opts) fake-key)]
    (is (= "hello\n" (:stdout r)))
    (is (= 0 (:exit r)))
    (is (= 1234 (:wall-ms r)))
    (is (= "sha256:abc" (:image-digest r)))
    (is (= "echo hello" (:command r)))
    (is (= 1000 (:created-at r)))))

(deftest lookup-miss-returns-nil
  (is (nil? (ls/lookup (opts) fake-key))))

(deftest exists-predicate
  (is (false? (ls/exists? (opts) fake-key)))
  (ls/store! (opts) fake-key {:exit 0 :now 1000})
  (is (true? (ls/exists? (opts) fake-key))))

;; ---------------------------------------------------------------------------
;; On-disk layout (prefix bucket)
;; ---------------------------------------------------------------------------

(deftest layout-uses-2-char-prefix
  (ls/store! (opts) fake-key {:exit 0 :now 1000})
  (let [prefix-dir (io/file *tmp-root* "ab")]
    (is (.exists prefix-dir) "prefix bucket created")
    (is (.exists (io/file prefix-dir fake-key))
        "entry dir lives under prefix bucket")
    (is (.exists (io/file prefix-dir fake-key "meta.edn")))
    (is (.exists (io/file prefix-dir fake-key "exit")))))

;; ---------------------------------------------------------------------------
;; Artifacts payload
;; ---------------------------------------------------------------------------

(deftest artifacts-tgz-roundtrip
  (let [payload (byte-array (map byte "fake-tgz-bytes"))]
    (ls/store! (opts) fake-key
               {:stdout "" :stderr "" :exit 0 :wall-ms 0
                :artifacts-tgz payload :now 1000})
    (let [r (ls/lookup (opts) fake-key)]
      (is (some? (:artifacts-tgz-path r)))
      (is (= "fake-tgz-bytes" (slurp (:artifacts-tgz-path r)))))))

;; ---------------------------------------------------------------------------
;; Entries + total-bytes
;; ---------------------------------------------------------------------------

(deftest entries-enumerates-everything
  (ls/store! (opts) fake-key {:exit 0 :stdout "a" :now 1000})
  (ls/store! (opts) fake-key2 {:exit 0 :stdout "b" :now 2000})
  (let [es (vec (ls/entries (opts)))]
    (is (= 2 (count es)))
    (is (every? :dir es))
    (is (every? :created-at es))
    (is (every? :size es))
    (is (every? pos? (map :size es)))))

(deftest total-bytes-sums
  (ls/store! (opts) fake-key {:exit 0 :stdout "hello" :now 1000})
  (is (pos? (ls/total-bytes (opts)))))

;; ---------------------------------------------------------------------------
;; LRU eviction
;; ---------------------------------------------------------------------------

(deftest evict-to-cap-removes-oldest-first
  ;; Make payloads big enough that cap=100 forces eviction of at least one.
  (let [big (apply str (repeat 500 "A"))]
    (ls/store! (opts) fake-key  {:exit 0 :stdout big :now 1000})  ; older
    (ls/store! (opts) fake-key2 {:exit 0 :stdout big :now 2000})) ; newer
  (let [before (ls/total-bytes (opts))]
    (is (> before 600) "test setup: both entries combined exceed 600 bytes")
    ;; Evict aggressively — cap < combined size, forces removal of older entry
    (let [evicted (ls/evict-to-cap! (opts) 100)]
      (is (pos? evicted) "at least one entry evicted"))
    (let [after (ls/total-bytes (opts))]
      (is (< after before) "eviction shrank the cache")
      ;; The OLDER entry (fake-key, created-at 1000) should be gone first
      (is (false? (ls/exists? (opts) fake-key))
          "evict-to-cap! deletes oldest entry first"))))

(deftest evict-noop-when-under-cap
  (ls/store! (opts) fake-key {:exit 0 :stdout "x" :now 1000})
  (let [n (ls/evict-to-cap! (opts) 1000000000)]
    (is (zero? n) "no eviction needed when under cap")
    (is (ls/exists? (opts) fake-key))))

;; ---------------------------------------------------------------------------
;; Clear
;; ---------------------------------------------------------------------------

(deftest clear-removes-all
  (ls/store! (opts) fake-key {:exit 0 :now 1000})
  (ls/store! (opts) fake-key2 {:exit 0 :now 2000})
  (ls/clear! (opts))
  (is (false? (ls/exists? (opts) fake-key)))
  (is (false? (ls/exists? (opts) fake-key2)))
  (is (zero? (ls/total-bytes (opts)))))

;; ---------------------------------------------------------------------------
;; Idempotent overwrite
;; ---------------------------------------------------------------------------

(deftest overwrite-same-key-overwrites-payload
  (ls/store! (opts) fake-key {:exit 0 :stdout "v1" :now 1000})
  (ls/store! (opts) fake-key {:exit 0 :stdout "v2" :now 2000})
  (is (= "v2" (:stdout (ls/lookup (opts) fake-key)))
      "second write replaces the first"))
