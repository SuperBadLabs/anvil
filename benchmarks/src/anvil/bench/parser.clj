(ns anvil.bench.parser
  "Bench the Jenkinsfile parser + IR translator.

   Measures, per corpus file:
     - parse-time:        Jenkinsfile source → Groovy AST → :cdata view
     - translate-time:    Walking the :cdata view to produce Jenkins IR
     - end-to-end:        Both combined (what `t/parse` does)
     - bytes-per-second:  Source size / end-to-end seconds

   These are anvil's intrinsic parser performance. They DON'T compare to
   Jenkins; Jenkins's Pipeline parser doesn't expose a comparable
   benchmark surface. See README for the comparison methodology."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.compat.jenkins.translator :as t]
            [anvil.bench.measure :as m]))

(def ^:private corpus-dir "../test/resources/jenkins-corpus")

(defn- corpus-files []
  (->> (file-seq (io/file corpus-dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".Jenkinsfile"))
       (sort-by #(.length ^java.io.File %))))

(defn- bench-one [^java.io.File f opts]
  (let [source (slurp f)
        size (count source)
        e2e-summary (m/measure opts #(t/parse source (.getName f)))]
    {:file (.getName f)
     :bytes size
     :e2e e2e-summary
     :bytes-per-sec (long (/ (* size 1000000000.0)
                             (max 1 (:median-ns e2e-summary))))}))

(defn run
  "Run the parser benchmark over the full corpus."
  ([] (run {}))
  ([{:keys [iterations warmup] :or {iterations 30 warmup 3} :as opts}]
   (let [files (corpus-files)
         per-file (mapv #(bench-one % opts) files)
         total-bytes (reduce + (map :bytes per-file))
         all-median-ns (reduce + (map (comp :median-ns :e2e) per-file))]
     {:bench :parser
      :iterations iterations
      :warmup warmup
      :corpus-count (count files)
      :corpus-bytes total-bytes
      :sum-of-medians-ns all-median-ns
      :corpus-throughput-mb-per-s
      (/ (* total-bytes 1000.0)
         (max 1 (m/ns->ms all-median-ns)))
      :per-file per-file})))

(defn print-report [{:keys [per-file corpus-count corpus-bytes
                            sum-of-medians-ns corpus-throughput-mb-per-s]}]
  (println)
  (println "  ── Parser benchmark ──────────────────────────────────────────────────────")
  (println (format "    files=%d  total-bytes=%,d  sum-of-medians=%.1f ms  throughput=%.1f MB/s"
                   corpus-count
                   corpus-bytes
                   (m/ns->ms sum-of-medians-ns)
                   corpus-throughput-mb-per-s))
  (println)
  (println "    Per-file (sorted by size):")
  (doseq [{:keys [file bytes e2e bytes-per-sec]} per-file]
    (println (format "      %-65s  %4d B  median=%6.2f ms  thr=%6.0f KB/s"
                     (if (> (count file) 65) (str (subs file 0 62) "...") file)
                     bytes
                     (m/ns->ms (:median-ns e2e))
                     (/ bytes-per-sec 1024.0)))))
