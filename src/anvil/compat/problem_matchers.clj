(ns anvil.compat.problem-matchers
  "Problem-matcher framework (T2.1 of the v0.3 board).

   Per AV3-3, anvil adopts GitHub Actions' problem-matcher schema
   verbatim. Two parsers ship at v0.3.0:

   1. Workflow-command parser (T2.5) — the *modern* path. Tools that
      emit GHA-style commands like

         ::warning file=src/foo.rs,line=42,col=7::expected ; found }

      get parsed natively, no regex needed. Modern compilers, LSPs,
      and CI helpers all emit this; it's stable, structured, and
      unambiguous.

   2. YAML regex matcher (T2.1) — the *legacy* path. Tools that
      print `<file>:<line>:<col>: warning: <message>` (gcc, rustc,
      mypy, eslint, javac, msbuild and friends) get routed through
      data-only YAML rules. New tools ship by adding a YAML file,
      never code (per R2 of the v0.3 risk register).

   Both paths emit the same problem IR:

     {:source     <str>     ; matcher identity (\"gcc\", \"::workflow\")
      :file       <str>
      :line       <int>
      :column     <int-or-nil>
      :severity   #{:error :warning :note :info}
      :message    <str>}

   ## Rule file format (mirrors GHA's `tsconfig.matcher.json`)

     # resources/problem-matchers/gcc.yml
     owner: gcc
     pattern:
       - regexp: '^(.+):(\\d+):(\\d+):\\s+(warning|error|note):\\s+(.+)$'
         file: 1
         line: 2
         column: 3
         severity: 4
         message: 5

   Each numeric value points at a regex capture group. Missing keys
   default to nil — gcc emits :column, javac does not, both work.

   ## Hot path

   `match-line` runs the workflow-command parser first (cheap), then
   walks the rules. Each rule's `Pattern` is pre-compiled at load
   time so per-line cost is one regex `.matches()` per rule until
   one hits. Per-line budget: < 5 µs on a 6-rule ship — well under
   the log-tail's 50 ms poll period."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Workflow-command parser (T2.5)
;; ---------------------------------------------------------------------------

;; ::<severity> <kv,kv,kv>::<message>
;; <severity>  one of error|warning|notice|debug
;; <kv>        key=value pairs (file, line, col, endLine, endColumn, title)
(def ^:private workflow-command-re
  #"^::(error|warning|notice|debug)(?:\s+([^:]+))?::(.+)$")

(defn- parse-kv-list
  "\"file=foo.c,line=12,col=3\" → {\"file\" \"foo.c\" \"line\" \"12\" ...}.
   Tolerates absent kv list (returns {})."
  [s]
  (if (str/blank? s)
    {}
    (into {}
          (for [part (str/split s #",")
                :let [eq (.indexOf part "=")]
                :when (pos? eq)]
            [(subs part 0 eq) (subs part (inc eq))]))))

(defn- sev-keyword [s]
  (case s
    "error"   :error
    "warning" :warning
    "notice"  :note
    "debug"   :info
    :info))

(defn- parse-int-or-nil [s]
  (when s
    (try (Integer/parseInt s) (catch NumberFormatException _ nil))))

(defn workflow-command-match
  "If `line` is a GHA workflow command, return the problem IR. Else nil."
  [^String line]
  (when-let [m (re-find workflow-command-re line)]
    (let [[_ sev kv-str msg] m
          kv (parse-kv-list kv-str)]
      {:source   "::workflow"
       :file     (get kv "file")
       :line     (parse-int-or-nil (get kv "line"))
       :column   (parse-int-or-nil (or (get kv "col") (get kv "column")))
       :severity (sev-keyword sev)
       :message  msg})))

;; ---------------------------------------------------------------------------
;; YAML regex rule loader (T2.1)
;; ---------------------------------------------------------------------------

(defrecord CompiledPattern [^java.util.regex.Pattern regex
                            file-group line-group column-group
                            severity-group message-group])

(defrecord Rule [^String owner patterns])

(defn- compile-pattern [raw]
  (let [{:keys [regexp file line column severity message]} raw]
    (->CompiledPattern (java.util.regex.Pattern/compile regexp)
                       file line column severity message)))

(defn- compile-rule [parsed]
  (->Rule (str (:owner parsed))
          (mapv compile-pattern (:pattern parsed))))

(defn- normalize-severity [s]
  (case (some-> s str/lower-case str/trim)
    "error"   :error
    "fatal"   :error
    ("warn" "warning") :warning
    ("note" "info" "info:") :note
    :warning))

(defn- match-rule [^CompiledPattern p ^String line owner]
  (let [m (re-matcher (:regex p) line)]
    (when (.matches m)
      (let [g (fn [idx] (when idx (try (.group m (int idx))
                                       (catch IndexOutOfBoundsException _ nil))))]
        {:source   owner
         :file     (g (:file-group p))
         :line     (parse-int-or-nil (g (:line-group p)))
         :column   (parse-int-or-nil (g (:column-group p)))
         :severity (normalize-severity (g (:severity-group p)))
         :message  (g (:message-group p))}))))

(defn match-rules
  "Walk `rules` looking for the first one whose any-pattern matches.
   Returns a problem IR map or nil. Stable order — rules earlier in
   the vector win."
  [^String line rules]
  (loop [[r & rest] rules]
    (when r
      (let [hit (some #(match-rule % line (:owner r)) (:patterns r))]
        (if hit hit (recur rest))))))

(declare bundled-rules)

(defn match-line
  "Top-level matcher. Tries the workflow-command path first (cheap,
   structured), falls back to the regex-rule walk. Returns one
   problem IR or nil — at most one match per line, even if multiple
   rules would hit (first-rule-wins keeps the data layer clean)."
  ([^String line]
   (match-line line (bundled-rules)))
  ([^String line rules]
   (or (workflow-command-match line)
       (match-rules line rules))))

;; ---------------------------------------------------------------------------
;; YAML loading
;; ---------------------------------------------------------------------------

(defn load-rule-yaml
  "Parse a single YAML rule file. `src` is a path, File, or Reader-able."
  [src]
  (try
    (let [parsed (with-open [rdr (io/reader src)]
                   (yaml/parse-string (slurp rdr)))]
      (compile-rule parsed))
    (catch Throwable t
      (log/warn t "anvil.compat.problem-matchers: failed to load rule" (pr-str src))
      nil)))

(defn load-rules-from-classpath
  "Load every `.yml` file under resources/problem-matchers/. Returns
   a vector of compiled `Rule` records, stable ordering by filename
   (so first-rule-wins is deterministic)."
  []
  (let [names ["gcc.yml" "rustc.yml" "javac.yml" "mypy.yml"
               "eslint.yml" "msbuild.yml"]
        rules (->> names
                   (map (fn [n] [n (io/resource (str "problem-matchers/" n))]))
                   (filter second)
                   (sort-by first)
                   (keep (fn [[_ r]] (load-rule-yaml r))))]
    (vec rules)))

(defonce ^:private bundled-rules-ref (delay (load-rules-from-classpath)))

(defn bundled-rules
  "The compiled, in-order rule vector. Lazy-loaded once."
  []
  @bundled-rules-ref)
