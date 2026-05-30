(ns anvil.compat.jenkins.steps.stash
  "TX11C — `stash` / `unstash` primitives.

   Jenkins's distributed-build protocol uses stash/unstash to ship
   files between agents:

     stash name: 'tests', includes: 'target/surefire-reports/**'
     ;; ... runs on another agent ...
     unstash 'tests'

   On a multi-agent fleet this round-trips through a controller-side
   artifact store. On a single-host setup (anvil v1) the agents share a
   workspace, so the protocol can be implemented as plain directory
   copies into a per-build stash store keyed by stash name.

   Layout under `<build-dir>/stashes/`:
     <build-dir>/stashes/<stash-name>/   ← files matching `:includes`
                                            preserved at their original
                                            relative paths inside the
                                            workspace.

   This module is intentionally framework-agnostic — it exposes pure
   filesystem operations. The dispatcher's `h-stash` / `h-unstash`
   handlers (TX11A → TX11C wiring) call these with the per-build
   stash root path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.nio.file FileSystems Files LinkOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Glob → File matching
;; ---------------------------------------------------------------------------

(defn- ant-glob->regex
  "Translate a Jenkins/Ant-style `includes` pattern into a regex.

   Ant globs: `**` matches any number of dirs (incl. zero), `*` matches
   any sequence within a single dir name (no `/`), `?` matches one char.

   Comma-separated patterns split into alternatives. Trailing `/` means
   directory; we leave that to the caller's intent.

   Order matters here: substitute the user's `?` FIRST. Our wildcard
   replacements below introduce `?` characters (as regex optional-group
   markers, e.g. `(?:.+/)?`) — if we did the user-`?` substitution last,
   those structural `?`s would get clobbered."
  [glob]
  (let [glob (str/trim glob)
        ;; Escape regex metas first, then re-introduce wildcards.
        esc (str/escape glob {\. "\\." \( "\\(" \) "\\)" \+ "\\+"
                              \^ "\\^" \$ "\\$" \| "\\|" \{ "\\{" \} "\\}"})
        re  (-> esc
                (str/replace #"\?"    "[^/]")        ;; user `?` — done first!
                (str/replace #"\*\*/" "(?:.+/)?")    ;; **/ — non-capturing optional
                (str/replace #"/\*\*" "(?:/.+)?")    ;; /**
                (str/replace #"\*\*"  ".*")          ;; **
                (str/replace #"\*"    "[^/]*"))]     ;; *
    (re-pattern (str "^" re "$"))))

(defn- pattern->predicate [pattern-string]
  (let [patterns (->> (str/split (or pattern-string "**/*") #",")
                      (map str/trim)
                      (remove str/blank?)
                      (mapv ant-glob->regex))]
    (fn [^String rel-path]
      (boolean (some #(re-matches % rel-path) patterns)))))

;; ---------------------------------------------------------------------------
;; File enumeration relative to a root
;; ---------------------------------------------------------------------------

(defn- relative-files
  "Walk `root`, yielding [^File file, ^String rel-path] pairs for every
   regular file underneath. Directories themselves aren't yielded; the
   caller stash/unstash logic recreates them as needed."
  [^java.io.File root]
  (when (.isDirectory root)
    (let [root-path (.toPath root)]
      (for [^java.io.File f (file-seq root)
            :when (.isFile f)
            :let [rel (str (.relativize root-path (.toPath f)))]]
        [f rel]))))

;; ---------------------------------------------------------------------------
;; Stash + unstash
;; ---------------------------------------------------------------------------

(defn stash!
  "Copy files from `workspace` matching `includes` (Ant glob, comma-sep)
   into `(io/file stash-root name)`. Returns a summary map:
     {:name STRING
      :stash-dir File
      :files-stashed N
      :bytes-stashed N
      :skipped-by-excludes N}

   Idempotent: re-stashing the same name overwrites the prior contents."
  [{:keys [name workspace stash-root includes excludes]
    :or {includes "**/*"}}]
  (let [ws (io/file workspace)
        out (io/file stash-root (str name))
        include? (pattern->predicate includes)
        exclude? (pattern->predicate (or excludes ""))
        excludes-blank? (str/blank? (or excludes ""))]
    (when-not (.isDirectory ws)
      (throw (ex-info (str "stash: workspace " (.getPath ws) " is not a directory")
                      {:workspace (.getPath ws)})))
    ;; Clear out any prior stash with this name
    (when (.exists out)
      (doseq [^java.io.File f (reverse (file-seq out))]
        (.delete f)))
    (.mkdirs out)
    (let [matched (filter (fn [[_ rel]] (and (include? rel)
                                             (or excludes-blank?
                                                 (not (exclude? rel)))))
                          (relative-files ws))
          skipped (- (count (relative-files ws)) (count matched))
          total-bytes (volatile! 0)]
      (doseq [[^java.io.File f rel] matched]
        (let [dst (io/file out rel)]
          (.mkdirs (.getParentFile dst))
          (io/copy f dst)
          (vswap! total-bytes + (.length f))))
      (let [summary {:name (str name)
                     :stash-dir out
                     :files-stashed (count matched)
                     :bytes-stashed @total-bytes
                     :skipped-by-excludes skipped}]
        (log/info (str "anvil stash '" name "': "
                       (:files-stashed summary) " files, "
                       (:bytes-stashed summary) " bytes"))
        summary))))

(defn unstash!
  "Copy files from `(io/file stash-root name)` into `workspace`, restoring
   relative paths. Returns:
     {:name STRING
      :files-restored N
      :bytes-restored N
      :missing? bool   ;; true if no stash with this name was found
      }
   Honours Jenkins's semantics: unstashing a missing name is an error,
   not a no-op. Returns {:missing? true} so the dispatcher can fail
   the build."
  [{:keys [name workspace stash-root]}]
  (let [src (io/file stash-root (str name))
        ws  (io/file workspace)]
    (cond
      (not (.isDirectory src))
      {:name (str name) :missing? true
       :error (str "no stash named " (pr-str name) " (expected " (.getPath src) ")")}

      :else
      (do (.mkdirs ws)
          (let [files (relative-files src)
                bytes (volatile! 0)]
            (doseq [[^java.io.File f rel] files]
              (let [dst (io/file ws rel)]
                (.mkdirs (.getParentFile dst))
                (io/copy f dst)
                (vswap! bytes + (.length f))))
            (log/info (str "anvil unstash '" name "': "
                           (count files) " files, " @bytes " bytes"))
            {:name (str name)
             :files-restored (count files)
             :bytes-restored @bytes
             :missing? false})))))

(defn build-stash-root
  "Conventional stash root for a build: <build-dir>/stashes/. The
   dispatcher passes this; tests can use anything writeable."
  [build-dir]
  (io/file build-dir "stashes"))
