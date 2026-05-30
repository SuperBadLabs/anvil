(ns anvil.bench.corpus-survey
  "TX14 — wider Jenkinsfile coverage survey.

   Walks a directory tree of cloned OSS repos, finds every
   `Jenkinsfile*` file, runs anvil's translator + importer over each,
   and produces a coverage report:

     - parse success rate
     - non-empty-IR rate (declarative or scripted)
     - % at 100% known-step coverage
     - % at ≥80% known-step coverage
     - top 10 most-common unknown step names (the importer's TODO list)

   Usage:
     lein with-profile +bench run -m anvil.bench.corpus-survey \\
       --tree /tmp/jenkinsfile-survey \\
       --out  docs/jenkins-compat/coverage-survey.md

   Pair with anvil/test-integration/clone-jenkinsfile-corpus.bb, which
   clones the input tree from a curated list of repos."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]))

(def ^:private known-step-types
  "Step IR types that the dispatcher knows how to run end-to-end. A
   100%-known file is one where every leaf step has an IR type in
   this set. The set is derived from anvil's step-translators table
   in translator.clj and the dispatcher's dispatch table."
  #{:jenkins/sh :jenkins/bat :jenkins/echo :jenkins/junit
    :jenkins/archive-artifacts :jenkins/delete-dir :jenkins/stash
    :jenkins/unstash :jenkins/dir :jenkins/node :jenkins/script
    :jenkins/checkout :jenkins/mail :jenkins/emailext
    :jenkins/write-file :jenkins/read-file :jenkins/build
    :jenkins/error :jenkins/sleep :jenkins/with-env
    :jenkins/with-credentials :jenkins/timeout :jenkins/retry
    :jenkins/parallel :jenkins/properties :jenkins/with-checks
    :jenkins/with-maven
    ;; Plugin adapters (recorded effects, not real subprocess)
    :jenkins/record-issues :jenkins/slack-send :jenkins/milestone
    :jenkins/with-sonarqube-env :jenkins/publish-coverage
    :jenkins/publish-html :jenkins/lock :jenkins/ssh-agent
    :jenkins/ssh-publisher :jenkins/nexus-upload
    :jenkins/wait-quality-gate :jenkins/add-failed-stage
    :jenkins/send-notifications :jenkins/send-error-notification
    :jenkins/send-success-notification :jenkins/notify-slack
    :jenkins/discover-git-ref-build :jenkins/pipeline-helpers
    :jenkins/report-portal})

(defn- jenkinsfile-files
  "Recursively find every regular file whose name matches a
   Jenkinsfile glob: `Jenkinsfile`, `Jenkinsfile.*`, `*.Jenkinsfile`."
  [root]
  (let [name-matches?
        (fn [^java.io.File f]
          (let [n (.getName f)]
            (or (= "Jenkinsfile" n)
                (str/starts-with? n "Jenkinsfile.")
                (str/ends-with? n ".Jenkinsfile"))))]
    (filter (fn [^java.io.File f]
              (and (.isFile f) (name-matches? f)))
            (file-seq (io/file root)))))

(defn- walk-steps
  "Flatten an IR pipeline into a seq of all leaf-step IR nodes,
   recursing into :body of wrappers."
  [stages]
  (letfn [(walk [steps]
            (mapcat (fn [s]
                      (cons s
                            (when (sequential? (:body s))
                              (walk (:body s)))))
                    steps))]
    (walk (mapcat :steps stages))))

(defn- analyze-file
  "Translate one Jenkinsfile, apply matrix expansion, and produce a
   per-file summary map."
  [^java.io.File f root]
  (let [path (.getAbsolutePath f)
        rel (str/replace path (str root "/") "")
        source (try (slurp f) (catch Exception _ ""))]
    (try
      (let [bytes (count source)
            base (t/parse source rel)
            expanded (mx/expand-matrices base source)
            stages (or (:stages expanded) [])
            steps (vec (walk-steps stages))
            step-types (frequencies (map :type steps))
            known-count (->> steps
                             (filter #(contains? known-step-types (:type %)))
                             count)
            unknown-steps (->> steps
                               (filter #(= :jenkins/unknown (:type %)))
                               (map :name)
                               frequencies)
            total-steps (count steps)
            coverage (when (pos? total-steps)
                       (double (/ known-count total-steps)))
            scripted? (some :scripted-pipeline?
                            (or (:options expanded) []))]
        {:rel rel
         :bytes bytes
         :parsed? true
         :scripted? (boolean scripted?)
         :stages (count stages)
         :steps total-steps
         :known-steps known-count
         :unknown-steps unknown-steps
         :coverage coverage
         :step-types step-types})
      (catch Exception e
        {:rel rel
         :bytes (count source)
         :parsed? false
         :error (.getMessage e)
         :exception-class (.getName (class e))}))))

(defn- aggregate
  "Compute the summary stats over all per-file results."
  [results]
  (let [total (count results)
        parsed (filter :parsed? results)
        non-empty (filter #(pos? (or (:stages %) 0)) parsed)
        scripted (filter :scripted? parsed)
        full-coverage (filter #(and (:coverage %) (>= (:coverage %) 1.0)) parsed)
        good-coverage (filter #(and (:coverage %) (>= (:coverage %) 0.8)) parsed)
        all-unknown-steps (reduce (partial merge-with +)
                                  {}
                                  (map :unknown-steps parsed))]
    {:total-files total
     :total-bytes (reduce + (map :bytes results))
     :parsed-count (count parsed)
     :parsed-pct (if (zero? total) 0.0 (* 100.0 (/ (count parsed) total)))
     :non-empty-count (count non-empty)
     :non-empty-pct (if (zero? total) 0.0 (* 100.0 (/ (count non-empty) total)))
     :scripted-count (count scripted)
     :full-coverage-count (count full-coverage)
     :full-coverage-pct (if (zero? total) 0.0 (* 100.0 (/ (count full-coverage) total)))
     :good-coverage-count (count good-coverage)
     :good-coverage-pct (if (zero? total) 0.0 (* 100.0 (/ (count good-coverage) total)))
     :top-unknown-steps (->> all-unknown-steps
                             (sort-by val >)
                             (take 15)
                             vec)}))

(defn- markdown-report [agg results tree-path]
  (with-out-str
    (println "# Anvil Jenkinsfile coverage survey")
    (println)
    (println (format "_Survey run at %s against tree `%s` containing %d files (%.1f MB total)._"
                     (str (java.time.Instant/now))
                     tree-path
                     (:total-files agg)
                     (/ (:total-bytes agg) 1024.0 1024.0)))
    (println)
    (println "## Headline")
    (println)
    (println (format "- **%.1f%% (%d of %d)** parse without error"
                     (:parsed-pct agg)
                     (:parsed-count agg)
                     (:total-files agg)))
    (println (format "- **%.1f%% (%d of %d)** produce a non-empty IR (≥1 stage extracted)"
                     (:non-empty-pct agg)
                     (:non-empty-count agg)
                     (:total-files agg)))
    (println (format "- **%.1f%% (%d of %d)** at ≥80%% known-step coverage"
                     (:good-coverage-pct agg)
                     (:good-coverage-count agg)
                     (:total-files agg)))
    (println (format "- **%.1f%% (%d of %d)** at 100%% known-step coverage"
                     (:full-coverage-pct agg)
                     (:full-coverage-count agg)
                     (:total-files agg)))
    (println (format "- **%d** scripted Pipeline files detected (TX11A walker)"
                     (:scripted-count agg)))
    (println)
    (println "## Top unrecognized step names")
    (println)
    (println "These are the most-frequent step calls that anvil's translator")
    (println "doesn't yet have a dedicated handler for — they fall through to")
    (println "`:jenkins/unknown` and either route via the shared-libs registry")
    (println "(infra.*, etc.) or to the importer's TODO list.")
    (println)
    (println "| Step name | Occurrences |")
    (println "|---|---:|")
    (doseq [[name n] (:top-unknown-steps agg)]
      (println (format "| `%s` | %d |" name n)))
    (println)
    (println "## Per-file results")
    (println)
    (println "| File | Bytes | Parsed | Scripted | Stages | Steps | Known/Steps | Coverage |")
    (println "|---|---:|:-:|:-:|---:|---:|---|---:|")
    (doseq [{:keys [rel bytes parsed? scripted? stages steps known-steps coverage error]}
            (sort-by :rel results)]
      (if parsed?
        (println (format "| `%s` | %d | ✓ | %s | %d | %d | %d/%d | %s |"
                         rel
                         bytes
                         (if scripted? "✓" "")
                         (or stages 0)
                         (or steps 0)
                         (or known-steps 0)
                         (or steps 0)
                         (if coverage (format "%.0f%%" (* 100 coverage)) "—")))
        (println (format "| `%s` | %d | ✗ %s | | | | | |"
                         rel bytes (or error "")))))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn run [{:keys [tree out]}]
  (when-not (and tree out)
    (println "Usage: lein run -m anvil.bench.corpus-survey --tree DIR --out FILE")
    (System/exit 2))
  (let [root (io/file tree)
        _ (when-not (.isDirectory root)
            (println (str "ERROR: " tree " is not a directory"))
            (System/exit 2))
        _ (println (str "→ Scanning " (.getAbsolutePath root) " ..."))
        files (vec (jenkinsfile-files root))
        _ (println (format "  found %d Jenkinsfile* files" (count files)))
        results (vec (pmap #(analyze-file % (.getAbsolutePath root)) files))
        agg (aggregate results)
        report (markdown-report agg results (.getAbsolutePath root))]
    (.mkdirs (.getParentFile (io/file out)))
    (spit out report)
    (println)
    (println (format "RESULT: %.1f%% parsed, %.1f%% non-empty IR, %.1f%% ≥80%% coverage, %.1f%% full"
                     (:parsed-pct agg)
                     (:non-empty-pct agg)
                     (:good-coverage-pct agg)
                     (:full-coverage-pct agg)))
    (println (str "Report → " out))))

(defn -main [& args]
  (let [opts (apply hash-map (map #(cond
                                     (str/starts-with? % "--") (keyword (subs % 2))
                                     :else %)
                                  args))]
    (run opts)
    (System/exit 0)))
