(ns anvil.cli.import-jenkinsfile-test
  "Integration test for `anvil import jenkinsfile <path>`. Drives the
   CLI entry point against a real corpus file and asserts on the
   produced output + report tier."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [anvil.cli.import-jenkinsfile :as cli]))

(def ^:private corpus-dir "test/resources/jenkins-corpus")
(def ^:private tmp-dir "target/test-import")

(defn- ensure-tmp-dir! []
  (.mkdirs (io/file tmp-dir)))

(defn- capture-stdout [thunk]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      [(thunk) (str sw)])))

(deftest help-flag-prints-usage-test
  (testing "--help prints usage and returns exit code 4"
    (let [[code out] (capture-stdout #(cli/run ["--help"]))]
      (is (= 4 code))
      (is (str/includes? out "Usage:"))
      (is (str/includes? out "--dry-run")))))

(deftest explain-known-step-test
  (testing "--explain script returns the migration recipe and exit code 4"
    (let [[code out] (capture-stdout #(cli/run ["--explain" "script"]))]
      (is (= 4 code))
      (is (str/includes? out "Scripted-pipeline block")))))

(deftest explain-unknown-step-gives-generic-recipe-test
  (testing "--explain for an unrecognized name falls back to a generic recipe"
    (let [[code out] (capture-stdout #(cli/run ["--explain" "totallyMadeUpStep"]))]
      (is (= 4 code))
      (is (str/includes? out "No specific recipe")))))

(deftest missing-file-fails-cleanly-test
  (testing "non-existent path returns exit code 3 and a clear error"
    (let [[code out] (capture-stdout #(cli/run ["/tmp/does-not-exist-12345.Jenkinsfile"]))]
      (is (= 3 code))
      (is (str/includes? out "ERROR")))))

(deftest dry-run-imports-and-reports-test
  (testing "--dry-run parses, prints report, but writes no file"
    (ensure-tmp-dir!)
    (let [out-path (str tmp-dir "/should-not-exist.edn")
          input (str corpus-dir "/apache__phoenix__master__Jenkinsfile.yetus.Jenkinsfile")
          [code out] (capture-stdout #(cli/run ["--dry-run" "--output" out-path input]))]
      (is (#{0 1} code) "phoenix-yetus should be green or yellow")
      (is (str/includes? out "Coverage:"))
      (is (str/includes? out "Dry run"))
      (is (not (.exists (io/file out-path)))))))

(deftest write-output-produces-readable-edn-test
  (testing "no --dry-run writes a parseable Chengisfile EDN to the output path"
    (ensure-tmp-dir!)
    (let [out-path (str tmp-dir "/phoenix-yetus.edn")
          input (str corpus-dir "/apache__phoenix__master__Jenkinsfile.yetus.Jenkinsfile")
          [code _out] (capture-stdout #(cli/run ["--output" out-path input]))]
      (is (#{0 1 2} code))
      (is (.exists (io/file out-path)))
      (let [content (slurp out-path)
            ;; Strip the leading comment block for the edn read
            edn-content (->> (str/split-lines content)
                             (drop-while #(or (str/starts-with? (str/triml %) ";;")
                                              (str/blank? %)))
                             (str/join "\n"))
            parsed (edn/read-string edn-content)]
        (is (map? parsed))
        (is (vector? (:stages parsed)))
        (is (pos? (count (:stages parsed))))
        ;; Yetus's stage is "Yetus".
        (is (= "Yetus" (-> parsed :stages first :name)))))))

(deftest full-corpus-import-tiers-test
  (testing "Each corpus file imports cleanly and gets a tier-appropriate exit code."
    (ensure-tmp-dir!)
    (let [files (->> (file-seq (io/file corpus-dir))
                     (filter #(.isFile ^java.io.File %))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".Jenkinsfile"))
                     sort)
          results (for [f files]
                    (let [out-path (str tmp-dir "/" (.getName f) ".edn")
                          [code _] (capture-stdout #(cli/run ["--output" out-path (.getPath f)]))]
                      {:file (.getName f) :code code}))
          codes (frequencies (map :code results))]
      ;; Every file imports — none should hit exit code 3 (input error).
      (is (zero? (get codes 3 0))
          (str "input errors: "
               (vec (map :file (filter #(= 3 (:code %)) results)))))
      ;; Distribution sanity check: print to stdout for visibility.
      (println)
      (println "  Corpus import tier distribution:")
      (println "    code 0 (≥90% green):  " (get codes 0 0))
      (println "    code 1 (50-90% yellow):" (get codes 1 0))
      (println "    code 2 (<50% red):    " (get codes 2 0)))))
