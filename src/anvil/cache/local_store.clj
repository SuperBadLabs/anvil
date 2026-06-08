(ns anvil.cache.local-store
  "v0.5 T1.2 — content-addressed filesystem store for the step-level
   cache.

   Layout (rooted at `store-root`, default `~/.anvil/cache`):

     <store-root>/<key-prefix-2>/<full-key>/
       stdout              ← step's captured stdout (utf-8)
       stderr              ← step's captured stderr (utf-8)
       exit                ← exit code, decimal ASCII
       meta.edn            ← {:created-at <ms>
                              :wall-ms <n>
                              :image-digest <str>
                              :command <str>}
       artifacts.tgz       ← optional: tar.gz of step's produced files
                             (caller decides what's relevant)

   The 2-char prefix subdir keeps any single directory from blowing up
   past ~10k entries on a sane filesystem.

   ## LRU eviction

   `:max-bytes` config caps the cumulative on-disk size. When over cap,
   the eviction sweep deletes the oldest entries (by `:created-at` in
   meta.edn) until the total is back under cap. Eviction is best-effort
   and runs synchronously on writes; readers never block on it.

   ## Thread-safety

   Writes use a temp dir + atomic rename. Two concurrent writers for the
   same key race to rename; whoever wins, both end up with the same
   payload (cache content is keyed by input identity). Readers see
   either the old payload, the new payload, or no payload — never a
   half-written one.

   ## What this namespace does NOT do

   - Image-digest resolution (callers pass it pre-resolved per
     `anvil.cache.key`'s contract)
   - Cache lookup wiring into the dispatcher (T1.3)
   - Remote object-store federation (T1.5)

   Pure-ish: all IO is parameterized by `store-root`; tests pass a tmp dir."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(declare evict-to-cap!)

(def ^:private default-root
  (str (System/getProperty "user.home") "/.anvil/cache"))

(defn- root-dir
  ^java.io.File [opts]
  (io/file (or (:store-root opts) default-root)))

(defn- entry-dir
  ^java.io.File [opts key]
  (let [prefix (subs key 0 2)]
    (io/file (root-dir opts) prefix key)))

(defn- atomic-write!
  "Write `content` to `dest` via a same-dir tmp file + rename. `content`
   may be a string or a byte-array."
  [^java.io.File dest content]
  (let [parent (.getParentFile dest)
        tmp (java.io.File/createTempFile ".anvil-cache-" ".tmp" parent)]
    (try
      (if (bytes? content)
        (io/copy content tmp)
        (spit tmp content))
      (.renameTo tmp dest)
      (finally
        (when (.exists tmp) (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn store!
  "Write a cache entry for `key` with the given payload.

   `payload` shape:
     {:stdout       <str>     — captured stdout
      :stderr       <str>     — captured stderr
      :exit         <int>     — process exit code
      :wall-ms      <long>    — wall-clock duration in ms
      :image-digest <str>     — for audit / debug
      :command      <str>     — for audit / debug
      :artifacts-tgz <bytes>  — optional tar.gz of produced files
      :now          <long>    — current time in ms (for :created-at;
                                 injected to keep this fn pure)}

   Idempotent on the file system (atomic-rename per file). Returns the
   entry dir. May trigger an LRU eviction pass per `:max-bytes` in opts."
  [opts key {:keys [stdout stderr exit wall-ms image-digest command
                    artifacts-tgz now]
             :or {stdout "" stderr "" exit 0 wall-ms 0}}]
  (assert (string? key) "key must be a string")
  (assert (some? now) "store! must be called with :now (the timestamp)")
  (let [dir (entry-dir opts key)]
    (fs/create-dirs dir)
    (atomic-write! (io/file dir "stdout") stdout)
    (atomic-write! (io/file dir "stderr") stderr)
    (atomic-write! (io/file dir "exit") (str exit))
    (atomic-write! (io/file dir "meta.edn")
                   (pr-str {:created-at now
                            :wall-ms wall-ms
                            :image-digest image-digest
                            :command command}))
    (when artifacts-tgz
      (atomic-write! (io/file dir "artifacts.tgz") artifacts-tgz))
    (when-let [max-bytes (:max-bytes opts)]
      (try (evict-to-cap! opts max-bytes)
           (catch Throwable t
             (log/warn t "anvil.cache: eviction sweep failed; continuing"))))
    dir))

(defn lookup
  "Look up the cache entry for `key`. Returns a payload map (same
   shape as `store!` accepts, minus `:now`) or nil on miss.

   Does NOT promote on read for LRU — we evict by created-at, not
   atime, to keep semantics simple and to avoid every read touching
   the filesystem just to update bookkeeping. If atime-based LRU is
   wanted later, the meta.edn gains an `:accessed-at` field."
  [opts key]
  (assert (string? key) "key must be a string")
  (let [dir (entry-dir opts key)]
    (when (and (.exists dir) (.isDirectory dir))
      (let [meta-file (io/file dir "meta.edn")
            exit-file (io/file dir "exit")
            stdout-file (io/file dir "stdout")
            stderr-file (io/file dir "stderr")
            artifacts-file (io/file dir "artifacts.tgz")]
        (when (and (.exists meta-file) (.exists exit-file))
          (let [meta-map (edn/read-string (slurp meta-file))]
            (cond-> {:stdout (when (.exists stdout-file) (slurp stdout-file))
                     :stderr (when (.exists stderr-file) (slurp stderr-file))
                     :exit (try (Integer/parseInt (str/trim (slurp exit-file)))
                                (catch NumberFormatException _ nil))
                     :wall-ms (:wall-ms meta-map)
                     :image-digest (:image-digest meta-map)
                     :command (:command meta-map)
                     :created-at (:created-at meta-map)}
              (.exists artifacts-file)
              (assoc :artifacts-tgz-path (str artifacts-file)))))))))

(defn exists?
  "Cheap predicate: does an entry exist for `key`?"
  [opts key]
  (let [dir (entry-dir opts key)]
    (and (.exists dir)
         (.exists (io/file dir "meta.edn"))
         (.exists (io/file dir "exit")))))

(defn- entry-info
  "Internal: gather size + created-at for one entry dir. Returns nil
   for malformed entries."
  [^java.io.File entry-dir]
  (try
    (let [meta-file (io/file entry-dir "meta.edn")]
      (when (.exists meta-file)
        (let [m (edn/read-string (slurp meta-file))
              size (->> (file-seq entry-dir)
                        (filter #(.isFile ^java.io.File %))
                        (map #(.length ^java.io.File %))
                        (apply + 0))]
          {:dir entry-dir
           :created-at (:created-at m 0)
           :size size})))
    (catch Throwable _ nil)))

(defn entries
  "Enumerate every entry under the store root. Returns a seq of maps
   with `:dir`, `:created-at`, `:size`. Malformed entries skipped."
  [opts]
  (let [root (root-dir opts)]
    (when (.exists root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))   ; prefix subdirs
           (mapcat #(.listFiles ^java.io.File %))     ; entry dirs
           (filter #(.isDirectory ^java.io.File %))
           (keep entry-info)))))

(defn total-bytes
  "Sum the on-disk size across all entries. Cheap-ish — does one stat
   per file. If you call this on a multi-GB cache, expect proportional
   IO."
  [opts]
  (apply + 0 (map :size (entries opts))))

(defn evict-to-cap!
  "Delete oldest entries until `total-bytes <= max-bytes`. Returns the
   number of entries deleted. Best-effort: a delete that fails (race,
   permissions) is logged and skipped.

   The sweep sorts by `:created-at` ascending — oldest first. If two
   entries share a timestamp, sort by directory name for determinism."
  [opts max-bytes]
  (let [all (vec (entries opts))
        current (apply + 0 (map :size all))]
    (if (<= current max-bytes)
      0
      (let [sorted (sort-by (juxt :created-at #(.getName ^java.io.File (:dir %))) all)]
        (loop [remaining current
               [{:keys [dir size]} & more] sorted
               deleted 0]
          (cond
            (nil? dir) deleted
            (<= remaining max-bytes) deleted
            :else
            (let [ok? (try (fs/delete-tree dir) true
                           (catch Throwable t
                             (log/warn t "anvil.cache: eviction skipped" (str dir))
                             false))]
              (recur (if ok? (- remaining size) remaining)
                     more
                     (if ok? (inc deleted) deleted)))))))))

(defn clear!
  "Delete every entry under `store-root`. For tests + operator-side
   cache nuke."
  [opts]
  (let [root (root-dir opts)]
    (when (.exists root)
      (fs/delete-tree root))
    (fs/create-dirs root)
    nil))
