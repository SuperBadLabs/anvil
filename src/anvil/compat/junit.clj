(ns anvil.compat.junit
  "Surefire-format JUnit XML parser (T1.1 of the v0.3 board).

   ## Why this exists

   Every Java/Kotlin/Scala build emits surefire-format XML at
   `target/surefire-reports/TEST-*.xml`. Maven, Gradle, sbt, and pretty
   much every JS / Python / Rust / .NET test runner also offers a
   surefire-compatible reporter (`mocha-junit-reporter`,
   `pytest --junitxml`, `cargo nextest --junit`, `vstest`).

   For anvil v0.3 to surface test results in its UI, we need to ingest
   this format. **AV3-2** of the v0.3 board fixes surefire as the
   canonical format at v0.3.0; xunit / cargo-json / jest-json parsers
   defer to v0.3.1.

   ## What surefire XML looks like (common subset)

   Two top-level shapes:

     1. One `<testsuite>` per file (Maven Surefire 2.x/3.x default,
        JUnit 4, TestNG):
          <testsuite name=\"foo.Bar\" tests=\"3\" failures=\"1\" errors=\"0\"
                     skipped=\"0\" time=\"0.234\">
            <testcase classname=\"foo.Bar\" name=\"testA\" time=\"0.1\"/>
            <testcase classname=\"foo.Bar\" name=\"testB\" time=\"0.1\">
              <failure message=\"expected x got y\" type=\"AssertionError\">
                stack trace text
              </failure>
            </testcase>
            <testcase classname=\"foo.Bar\" name=\"testC\" time=\"0.0\">
              <skipped/>
            </testcase>
          </testsuite>

     2. Multiple suites wrapped in `<testsuites>` (JUnit 5 vintage
        engine when aggregating, Gradle aggregate output):
          <testsuites name=\"all\" tests=\"...\" ...>
            <testsuite ...> ... </testsuite>
            <testsuite ...> ... </testsuite>
          </testsuites>

   We accept both.

   ## What we extract (the IR)

   Per the v0.3 board T1.1 spec, each test result is:

     {:test-id       \"foo.Bar#testA\"   ; classname \"#\" name
      :name          \"testA\"
      :class         \"foo.Bar\"
      :status        :passed | :failed | :errored | :skipped
      :duration-ms   100                ; seconds * 1000, rounded
      :failure-msg   \"expected x got y\" or nil
      :failure-type  \"AssertionError\" or nil
      :failure-trace \"...stack trace...\" or nil}

   Suite-level aggregate is also extracted (counts, total time, name)
   so the UI can render a suite header without re-summing cases.

   ## What dialect quirks we tolerate

   - **TestNG**: adds extra attrs (`groups`, `disabled`, `hostname`).
     We ignore them.
   - **JUnit 4**: `<error>` separate from `<failure>` (we map error →
     `:errored`, failure → `:failed`).
   - **JUnit 5**: may wrap in `<testsuites>`; may emit `<skipped>` with
     a `message` attr.
   - **Surefire 3.x**: `<properties>` block at suite top; `<system-out>`
     and `<system-err>` siblings of testcases. We ignore properties and
     system-* (they're useful for debugging but not for the dashboard).
   - Empty/missing `time` attr → 0ms (some runners omit it on
     synthetic suites).

   ## Anti-goals

   - We do NOT parse JTReport, NUnit, or xunit-net formats. AV3-2 punts
     those to v0.3.1.
   - We do NOT parse `<system-out>` / `<system-err>` into the IR. They
     can be ~MB-sized; if needed, a future T1.x adds an opt-in flag."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.nio.file Files FileSystems Path Paths LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- time-attr->ms
  "Convert a surefire `time=\"0.234\"` attribute (seconds, decimal)
   to integer milliseconds. Missing / blank → 0."
  [s]
  (if (or (nil? s) (str/blank? s))
    0
    (try
      (long (Math/round (* 1000.0 (Double/parseDouble s))))
      (catch NumberFormatException _ 0))))

(defn- int-attr
  "Parse an integer attr, tolerating nil/blank/garbage → default 0."
  [s]
  (if (or (nil? s) (str/blank? s))
    0
    (try (Long/parseLong s)
         (catch NumberFormatException _ 0))))

(defn- text-content
  "Concatenate the string content of an XML node, ignoring nested
   elements (which is fine for `<failure>` / `<error>` / `<skipped>`
   whose body is a stack trace as PCDATA)."
  [node]
  (when node
    (->> (:content node)
         (filter string?)
         (str/join)
         (str/trim))))

(defn- find-child
  "Return the first child of `node` whose tag matches `tag`. Nil if
   none."
  [node tag]
  (first (filter #(and (map? %) (= tag (:tag %))) (:content node))))

(defn- children
  "Sequence of element children of `node` matching `tag`."
  [node tag]
  (filter #(and (map? %) (= tag (:tag %))) (:content node)))

;; ---------------------------------------------------------------------------
;; Case-level parsing
;; ---------------------------------------------------------------------------

(defn- parse-failure-or-error
  "Pull message/type/trace out of a `<failure>` or `<error>` node."
  [node]
  (let [attrs (:attrs node)]
    {:msg   (:message attrs)
     :type  (:type attrs)
     :trace (text-content node)}))

(defn- case->result
  "Convert a single `<testcase>` xml node to the IR map. Defaults to
   :passed unless a `<failure>`/`<error>`/`<skipped>` child overrides."
  [tc]
  (let [attrs (:attrs tc)
        cls (or (:classname attrs) (:class attrs) "")
        nm  (or (:name attrs) "")
        failure (find-child tc :failure)
        error   (find-child tc :error)
        skipped (find-child tc :skipped)
        [status fail-msg fail-type fail-trace]
        (cond
          ;; A case may carry BOTH failure and error attrs in pathological
          ;; emitters; failure wins per JUnit convention.
          failure (let [{:keys [msg type trace]} (parse-failure-or-error failure)]
                    [:failed msg type trace])
          error   (let [{:keys [msg type trace]} (parse-failure-or-error error)]
                    [:errored msg type trace])
          skipped [:skipped (:message (:attrs skipped)) nil nil]
          :else   [:passed nil nil nil])]
    {:test-id      (str cls "#" nm)
     :name         nm
     :class        cls
     :status       status
     :duration-ms  (time-attr->ms (:time attrs))
     :failure-msg  fail-msg
     :failure-type fail-type
     :failure-trace fail-trace}))

;; ---------------------------------------------------------------------------
;; Suite-level parsing
;; ---------------------------------------------------------------------------

(defn- suite->result
  "Convert a single `<testsuite>` xml node to the IR map.
   Re-derives counts from the actual `<testcase>` children rather than
   trusting suite-level attrs — some emitters disagree with themselves
   (TestNG has been observed to under-count `errors`)."
  [ts]
  (let [attrs (:attrs ts)
        cases (mapv case->result (children ts :testcase))
        by-status (group-by :status cases)]
    {:name        (or (:name attrs) "")
     :hostname    (:hostname attrs)
     :timestamp   (:timestamp attrs)
     :tests       (count cases)
     :passed      (count (:passed by-status))
     :failed      (count (:failed by-status))
     :errored     (count (:errored by-status))
     :skipped     (count (:skipped by-status))
     :duration-ms (or (time-attr->ms (:time attrs))
                      (reduce + 0 (map :duration-ms cases)))
     ;; The two upstream-declared counts kept around for sanity-check
     ;; alerts in the UI (\"reporter says 12 failures, we counted 11\").
     :reporter-tests    (int-attr (:tests attrs))
     :reporter-failures (int-attr (:failures attrs))
     :reporter-errors   (int-attr (:errors attrs))
     :reporter-skipped  (int-attr (:skipped attrs))
     :cases       cases}))

;; ---------------------------------------------------------------------------
;; Top-level entry points
;; ---------------------------------------------------------------------------

(defn parse-surefire-xml
  "Parse a single surefire XML source — `in` may be a path string, a
   `java.io.File`, an `InputStream`, or a `Reader`.

   Returns:
     {:suites  [<suite-ir> ...]    ; one entry for bare <testsuite>,
                                   ; many for wrapping <testsuites>
      :totals  {:tests <n> :passed <n> :failed <n> :errored <n>
                :skipped <n> :duration-ms <n>}}

   On malformed XML, logs a WARN and returns:
     {:suites [] :totals {...all zero...}
      :parse-error {:message <str> :exception <class>}}.
   Returning a value (rather than throwing) is intentional — surefire
   produces one file per test class, and one corrupt file should not
   abort the whole post-build scan."
  [in]
  (try
    (let [tree (with-open [rdr (io/reader in)]
                 (xml/parse rdr))
          suites (case (:tag tree)
                   :testsuite  [(suite->result tree)]
                   :testsuites (mapv suite->result (children tree :testsuite))
                   ;; Unknown root tag — return empty rather than throw.
                   (do
                     (log/warn (str "anvil.compat.junit: unexpected root tag "
                                    (pr-str (:tag tree))))
                     []))
          totals (reduce
                  (fn [acc s]
                    (-> acc
                        (update :tests       + (:tests s))
                        (update :passed      + (:passed s))
                        (update :failed      + (:failed s))
                        (update :errored     + (:errored s))
                        (update :skipped     + (:skipped s))
                        (update :duration-ms + (:duration-ms s))))
                  {:tests 0 :passed 0 :failed 0 :errored 0 :skipped 0
                   :duration-ms 0}
                  suites)]
      {:suites suites
       :totals totals})
    (catch Throwable t
      (log/warn t "anvil.compat.junit: parse failure on " (pr-str in))
      {:suites []
       :totals {:tests 0 :passed 0 :failed 0 :errored 0 :skipped 0 :duration-ms 0}
       :parse-error {:message (.getMessage t)
                     :exception (.getName (class t))}})))

(defn parse-surefire-tree
  "Parse a collection of surefire XML sources (typically the result of
   globbing `target/surefire-reports/TEST-*.xml`). Returns:

     {:suites [<suite-ir> ...]      ; concatenated across files
      :totals {:tests <n> :passed <n> ...}
      :parse-errors [{:source <str> :message <str>} ...]}

   A per-file parse failure does NOT abort the rest — it's recorded
   in `:parse-errors` and the UI can surface a 'N of M reports
   parsed' diagnostic."
  [sources]
  (let [results (mapv (fn [src] [src (parse-surefire-xml src)]) sources)
        all-suites (vec (mapcat (comp :suites second) results))
        parse-errors (vec
                      (for [[src result] results
                            :when (:parse-error result)]
                        (assoc (:parse-error result) :source (str src))))
        totals (reduce
                (fn [acc s]
                  (-> acc
                      (update :tests       + (:tests s))
                      (update :passed      + (:passed s))
                      (update :failed      + (:failed s))
                      (update :errored     + (:errored s))
                      (update :skipped     + (:skipped s))
                      (update :duration-ms + (:duration-ms s))))
                {:tests 0 :passed 0 :failed 0 :errored 0 :skipped 0
                 :duration-ms 0}
                all-suites)]
    {:suites all-suites
     :totals totals
     :parse-errors parse-errors}))

;; ---------------------------------------------------------------------------
;; Workspace scan — T1.2
;; ---------------------------------------------------------------------------

(def default-globs
  "Surefire XML locations we look in when no caller-supplied glob is
   provided. Covers Maven, Gradle (both layouts), and the most common
   single-file emitters from npm/pytest/cargo."
  ["target/surefire-reports/*.xml"        ; Maven, the original
   "target/test-results/test/*.xml"       ; Gradle pre-7
   "build/test-results/test/*.xml"        ; Gradle 7+
   "build/test-results/**/*.xml"          ; Gradle multi-module
   "reports/junit.xml"                    ; pytest --junitxml=reports/junit.xml
   "junit.xml"                            ; cargo nextest, npm mocha-junit
   "test-results.xml"])                   ; misc shop convention

(defn- glob->matcher
  "Compile a single glob pattern into a java.nio.file.PathMatcher."
  [glob]
  (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob)))

(defn- walk-files
  "Vector of every regular file under `root`. We materialize so the
   underlying NIO Stream closes promptly — surefire trees in practice
   are < ~10k files even for a multi-module monorepo."
  [^Path root]
  (when (Files/exists root (into-array LinkOption []))
    (let [stream (Files/walk root (into-array java.nio.file.FileVisitOption []))]
      (try
        (vec (filter #(Files/isRegularFile % (into-array LinkOption []))
                     (iterator-seq (.iterator stream))))
        (finally (.close stream))))))

(defn find-surefire-xml
  "Walk `workspace-dir` and return absolute paths matching any of
   `globs` (defaults to `default-globs`). Sorted by path for
   deterministic test order."
  ([workspace-dir]
   (find-surefire-xml workspace-dir default-globs))
  ([workspace-dir globs]
   (let [root (.toAbsolutePath (Paths/get (str workspace-dir)
                                          (into-array String [])))
         matchers (mapv glob->matcher globs)
         files (walk-files root)]
     (->> files
          (filter (fn [^Path p]
                    (let [rel (.relativize root p)]
                      (some #(.matches ^java.nio.file.PathMatcher % rel)
                            matchers))))
          (map #(.toFile ^Path %))
          (sort-by #(.getPath ^java.io.File %))
          vec))))

(defn scan-build-artifacts
  "T1.2 — after a build completes, glob its workspace for surefire
   XML, parse every match, and return the aggregated tree.

   `opts` may include:
     :globs — vector of glob patterns to search; defaults to
              `default-globs`. Caller can pass a single project's
              custom layout (e.g. ['out/junit/*.xml']).

   Returns the same shape as `parse-surefire-tree`:
     {:suites [...] :totals {...} :parse-errors [...]
      :scanned-files <n> :scanned-from <abs-path>}.

   This function does NOT persist. The dispatcher hook from T1.6 is
   responsible for calling `anvil.storage.test-results/record-build-
   results!` + publishing the `:test-completed` bus event."
  ([workspace-dir]
   (scan-build-artifacts workspace-dir {}))
  ([workspace-dir {:keys [globs] :or {globs default-globs}}]
   (let [files (find-surefire-xml workspace-dir globs)
         tree (parse-surefire-tree files)]
     (log/info (format "anvil.compat.junit: scanned %s — %d files, %d cases (passed=%d failed=%d errored=%d skipped=%d)"
                       (str workspace-dir)
                       (count files)
                       (get-in tree [:totals :tests])
                       (get-in tree [:totals :passed])
                       (get-in tree [:totals :failed])
                       (get-in tree [:totals :errored])
                       (get-in tree [:totals :skipped])))
     (assoc tree
            :scanned-files (count files)
            :scanned-from (str workspace-dir)))))
