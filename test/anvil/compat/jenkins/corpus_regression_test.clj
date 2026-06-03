(ns anvil.compat.jenkins.corpus-regression-test
  "Corpus regression — runs every Jenkinsfile in the curated corpus
   through `anvil.compat.jenkins.translator/parse`.

   The TX3 exit gate (per `docs/jenkins-compat/execution-board.md`):
     - All 23 corpus Jenkinsfiles produce a valid IR (don't crash;
       return ir/pipeline?-true map).
     - The summary report below tells the reader, per file, the
       coverage tier reached and which step types appeared.

   The corpus itself lives at `test/resources/jenkins-corpus/` at the
   repo root. From anvil/ the relative path is `test/resources/...`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]))

(def ^:private corpus-dir "test/resources/jenkins-corpus")

(defn- jenkinsfiles []
  (->> (file-seq (io/file corpus-dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".Jenkinsfile"))
       (sort-by #(.getName ^java.io.File %))))

(defn- parse-and-summarize [^java.io.File f]
  (let [source (slurp f)
        ir (t/parse source (.getName f))]
    {:file (.getName f)
     :ir ir
     :summary (ir/summarize ir)
     :valid? (ir/pipeline? ir)}))

(deftest corpus-fully-parses-test
  (testing "every corpus Jenkinsfile produces a valid IR (no crashes)"
    (let [files (jenkinsfiles)]
      (is (pos? (count files))
          "the corpus directory contains at least one Jenkinsfile")
      (let [results (mapv parse-and-summarize files)
            invalid (filter (complement :valid?) results)]
        (is (empty? invalid)
            (str "files that did not produce a valid IR: "
                 (vec (map :file invalid))))))))

(deftest corpus-coverage-summary
  (testing "REPORTING — prints the per-file IR-extraction summary"
    (let [results (mapv parse-and-summarize (jenkinsfiles))
          tier (fn [s] (cond
                         (zero? (:total-steps s 0))   :empty
                         (zero? (:unknown-steps s 0)) :clean
                         (>= (:coverage s 0) 50.0)    :partial
                         :else                         :sparse))
          tiered (group-by (comp tier :summary) results)
          all-unknowns (->> results
                            (mapcat #(get-in % [:summary :unknown-names]))
                            frequencies
                            (sort-by val >))]
      (println)
      (println "┌─ Corpus parse summary ──────────────────────────────────────")
      (println (format "│ Files parsed:    %d" (count results)))
      (doseq [t [:empty :sparse :partial :clean]]
        (when-let [rs (get tiered t)]
          (println (format "│   :%-8s   %2d files" (name t) (count rs)))))
      (println "│")
      (println "│ Per-file:")
      (doseq [{:keys [file summary]} results]
        (println (format "│   %-65s stages=%-2d steps=%-3d known=%-3d unknown=%-3d cov=%5.1f%%"
                         (if (> (count file) 65) (str (subs file 0 62) "...") file)
                         (:stage-count summary)
                         (:total-steps summary)
                         (:known-steps summary)
                         (:unknown-steps summary)
                         (:coverage summary))))
      (println "│")
      (println "│ Top unknown step names (drive future adapter priority):")
      (doseq [[name n] (take 15 all-unknowns)]
        (println (format "│   %-30s %3d" name n)))
      (println "└──────────────────────────────────────────────────────────────")
      (is true "(reporting test — always passes; reads the printed output)"))))

(deftest corpus-no-runaway-script-blocks-test
  (testing "script {} bodies are captured (non-blank) when present"
    (let [results (mapv parse-and-summarize (jenkinsfiles))
          script-files (->> results
                            (filter (fn [{:keys [ir]}]
                                      (pos? (or (get-in (ir/summarize ir) [:script-blocks]) 0)))))]
      (doseq [{:keys [file ir]} script-files]
        (doseq [step (ir/all-steps ir)
                :when (ir/script-step? step)]
          (is (not (str/blank? (:body-source step)))
              (str "script {} body in " file " has empty captured source")))))))
