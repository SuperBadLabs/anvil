(ns anvil.cli.import-jenkinsfile
  "`anvil import jenkinsfile <path>` — convert a Jenkinsfile into a
   Chengisfile next to it, print a coverage report.

   Flags:
     --output PATH   where to write the Chengisfile (default: <path>.chengisfile.edn)
     --dry-run       parse + report, do not write the output file
     --explain NAME  print the rationale + migration recipe for a specific
                     Jenkins step or scope wrapper, then exit

   Exit code:
     0   coverage ≥ 90% of known step types, output written (or dry-run done)
     1   coverage in [50, 90)% — review needed
     2   coverage < 50% — significant manual translation required
     3   input file unreadable / not a Jenkinsfile
     4   --explain or --help used (informational)"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]
            [anvil.cli.chengisfile-renderer :as renderer]))

;; ---------------------------------------------------------------------------
;; CLI option spec
;; ---------------------------------------------------------------------------

(def cli-options
  [["-o" "--output PATH" "Output Chengisfile path"]
   [nil  "--dry-run" "Don't write the output file"]
   ["-e" "--explain NAME" "Print the migration recipe for a Jenkins step name and exit"]
   ["-h" "--help" "Show this help"]])

;; ---------------------------------------------------------------------------
;; --explain dictionary — what to do for the long tail of unknown steps
;; ---------------------------------------------------------------------------

(def ^:private explanations
  {"script"            "Scripted-pipeline block. anvil parses it but runtime
                        evaluation has slightly lower performance + isolation than
                        declarative steps. If the block is <20 lines, hand-translate
                        each statement to a native step. If complex, leave it as
                        :jenkins-script-block."
   "withCredentials"   "Credential binding. anvil emits a :with-credentials wrapper;
                        you'll need to re-point each credential ID at anvil/Chengis's
                        credential store after import. Secret values are masked from
                        logs automatically."
   "recordIssues"      "Warnings NG plugin. Replace with anvil's `:publish-static-analysis`
                        step once it lands (TX5 plugin SDK), or keep as a tagged
                        plugin step and treat as advisory."
   "slackSend"         "Slack notification. anvil's `:notify` step covers Slack via
                        an integration; once configured, replace 1:1."
   "milestone"         "Pipeline ordering primitive. anvil's concurrency model uses
                        per-stage gates; usually safe to drop unless you depended
                        on Jenkins's specific 'cancel older builds at this milestone'
                        semantics."
   "publishCoverage"   "Code coverage publish. Maps to anvil's `:publish-coverage`
                        step with an `:adapter` key."
   "publishHTML"       "HTML report attachment. Replace with anvil's
                        `:archive-artifacts` for the HTML files."
   "lock"              "Lockable Resources plugin. Replace with anvil's
                        `:shared-resource` if you need cluster-wide locking."
   "withSonarQubeEnv"  "SonarQube env wrapper. Replace with anvil's `:with-sonarqube`
                        step (TX5) or unwrap manually."})

(defn- explain [name]
  (println)
  (if-let [text (get explanations name)]
    (do
      (println (str "  Step: " name))
      (println)
      (doseq [line (str/split-lines text)]
        (println (str "    " (str/triml line)))))
    (do
      (println (str "  No specific recipe for '" name "'."))
      (println)
      (println "    Most unrecognized steps come from Jenkins plugins. anvil's plugin")
      (println "    SDK (TX5) provides an extension point for community-contributed")
      (println "    adapters. In the meantime, the step is tagged :jenkins-unsupported")
      (println "    in the output Chengisfile and the runtime records it as a no-op")
      (println "    with a log entry.")))
  (println))

;; ---------------------------------------------------------------------------
;; Rendering the output file
;; ---------------------------------------------------------------------------

(defn- header-comment [source-path]
  (str ";; Imported from " source-path "\n"
       ";; by anvil import jenkinsfile.\n"
       ";;\n"
       ";; This file is now the source of truth. Switch your build to point at\n"
       ";; the Chengisfile.edn — anvil also supports running the original\n"
       ";; Jenkinsfile directly via `anvil build --jenkins-mode`.\n"
       ";;\n"
       ";; Any :FIXME entries below need a human pass before the build runs cleanly.\n"))

(defn- write-chengisfile [path chengisfile source-path]
  (with-open [w (io/writer path)]
    (.write w ^String (header-comment source-path))
    (binding [*out* w
              pp/*print-right-margin* 100]
      (pp/pprint chengisfile))))

;; ---------------------------------------------------------------------------
;; Coverage report (text)
;; ---------------------------------------------------------------------------

(defn- format-percent [n]
  (format "%.1f%%" (double n)))

(defn- coverage-tier [coverage]
  (cond
    (>= coverage 90.0) :green
    (>= coverage 50.0) :yellow
    :else              :red))

(defn- exit-code-for-tier [tier]
  (case tier
    :green 0
    :yellow 1
    :red 2))

(defn- ascii-bar [coverage]
  (let [width 40
        filled (int (Math/round (* width (/ coverage 100.0))))
        empty (- width filled)]
    (str "[" (apply str (repeat filled "█")) (apply str (repeat empty "░")) "]")))

(defn print-report
  "Print the per-import coverage report. Returns the exit code."
  [{:keys [source-path output-path summary fixmes unsupported wrote?]}]
  (let [tier (coverage-tier (:coverage summary))
        tier-mark (case tier :green "✓" :yellow "⚠" :red "✗")]
    (println)
    (println (str "anvil import jenkinsfile  " source-path))
    (println)
    (println (str "  Stages:        " (:stage-count summary)
                  "  ·  Steps: " (:total-steps summary)
                  "  ·  Script blocks: " (:script-blocks summary)))
    (println (str "  Coverage:      " (format-percent (:coverage summary))
                  "  " (ascii-bar (:coverage summary))
                  "  [" (name tier) "]"))
    (println (str "  Step types known:    " (:known-steps summary)))
    (println (str "  Step types unknown:  " (:unknown-steps summary)))
    (when (seq unsupported)
      (println)
      (println "  Unsupported step names (count):")
      (doseq [[name n] (sort-by val > unsupported)]
        (println (str "    " (format "%-32s" name) n))))
    (when (seq fixmes)
      (println)
      (println (str "  FIXME points (" (count fixmes) "):"))
      (doseq [{:keys [type line name reason]} (take 10 fixmes)]
        (println (str "    "
                      (when line (str "line " line ": "))
                      (or name (some-> type clojure.core/name))))
        (when reason
          (doseq [l (str/split-lines reason)]
            (println (str "        " (str/triml l))))))
      (when (> (count fixmes) 10)
        (println (str "    … " (- (count fixmes) 10) " more"))))
    (println)
    (case [tier (boolean wrote?)]
      [:green  true]  (println (str tier-mark " " output-path " written. You can run `anvil build` on it."))
      [:green  false] (println (str tier-mark " Dry run. " (format-percent (:coverage summary)) " coverage — ready to import."))
      [:yellow true]  (println (str tier-mark " " output-path " written, but a manual pass on the FIXME points is recommended before running."))
      [:yellow false] (println (str tier-mark " Dry run. " (format-percent (:coverage summary)) " coverage — review FIXMEs before importing."))
      [:red    true]  (println (str tier-mark " " output-path " written, but significant manual translation is needed."))
      [:red    false] (println (str tier-mark " Dry run. Low coverage (" (format-percent (:coverage summary)) ") — this Jenkinsfile uses many unsupported steps.")))
    (println)
    (exit-code-for-tier tier)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- print-usage [opt-spec]
  (println)
  (println "Usage: anvil import jenkinsfile <path> [options]")
  (println)
  (println "Options:")
  (println (:summary opt-spec))
  (println))

(defn- default-output-path [^String input]
  (cond
    (str/ends-with? input ".Jenkinsfile")
    (str input ".chengisfile.edn")

    (= "Jenkinsfile" (.getName (io/file input)))
    ;; When `input` is bare "Jenkinsfile" (no parent), `.getParent`
    ;; returns nil and the old code produced "/Chengisfile.edn"
    ;; (writes at the filesystem root, permission-denied). Use the
    ;; parent if present, else current dir (Codex P2, PR #164).
    (let [parent (.getParent (io/file input))]
      (if parent
        (str parent "/Chengisfile.edn")
        "Chengisfile.edn"))

    :else
    (str input ".chengisfile.edn")))

(defn run
  "Entry point invoked from the CLI router. argv excludes the leading
   subcommand (so the first element should be the Jenkinsfile path)."
  [argv]
  (let [opt-spec (cli/parse-opts argv cli-options)
        {:keys [options arguments errors summary]} opt-spec]
    (cond
      (:help options)
      (do (print-usage opt-spec) 4)

      (:explain options)
      (do (explain (:explain options)) 4)

      (seq errors)
      (do (doseq [e errors] (println "ERROR:" e))
          (print-usage opt-spec)
          3)

      (empty? arguments)
      (do (println "ERROR: missing Jenkinsfile path") (print-usage opt-spec) 3)

      :else
      (let [src-path (first arguments)
            in-file (io/file src-path)]
        (cond
          (not (.exists in-file))
          (do (println (str "ERROR: file not found: " src-path)) 3)

          (not (.isFile in-file))
          (do (println (str "ERROR: not a regular file: " src-path)) 3)

          :else
          (let [source (slurp in-file)
                pipeline-ir (t/parse source src-path)
                summary (ir/summarize pipeline-ir)
                chengisfile (renderer/render pipeline-ir)
                {:keys [fixmes unsupported]} (renderer/fixme-report chengisfile)
                out-path (or (:output options) (default-output-path src-path))
                wrote? (and (not (:dry-run options))
                            (do (write-chengisfile out-path chengisfile src-path)
                                true))]
            (print-report {:source-path src-path
                           :output-path out-path
                           :summary summary
                           :fixmes fixmes
                           :unsupported unsupported
                           :wrote? wrote?})))))))
