(ns anvil.bench.dispatch
  "Bench end-to-end pipeline dispatch through the AnvilJenkinsDispatcher.

   The pipeline goes:
     - parse via translator
     - flatten stages + post hooks
     - run through chengis.engine.dispatcher's reference orchestrator
     - the AnvilJenkinsDispatcher records each step as a side effect

   IMPORTANT — see README:
     anvil currently RECORDS effects rather than subprocess-executing
     them. A fair Jenkins comparison for BUILD EXECUTION waits for TX9.
     What this benchmark DOES measure is anvil's pipeline orchestration
     throughput — the work it does between 'IR in' and 'effects out',
     which is what you'd see on top of (TX9's) real subprocess
     execution."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.bench.measure :as m]))

(def ^:private corpus-dir "../test/resources/jenkins-corpus")

(defn- corpus-files []
  (->> (file-seq (io/file corpus-dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".Jenkinsfile"))
       (sort-by #(.length ^java.io.File %))))

(defn- run-once [pre-parsed-ir]
  (let [flat {:stages (mapv (fn [s]
                              {:name (:name s)
                               :steps (concat (:steps s)
                                              (get-in s [:post :always] []))})
                            (:stages pre-parsed-ir))}
        dispatcher (ad/make)]
    (d/run-pipeline flat dispatcher {:cwd "/workspace"})
    (count @(:effects dispatcher))))

(defn- bench-one [^java.io.File f opts]
  (let [source (slurp f)
        ir (t/parse source (.getName f))
        e2e-summary (m/measure opts #(run-once ir))
        effects-per-run (run-once ir)]
    {:file (.getName f)
     :stages (count (:stages ir))
     :effects effects-per-run
     :e2e e2e-summary
     :effects-per-second (long (/ (* effects-per-run 1000000000.0)
                                  (max 1 (:median-ns e2e-summary))))}))

(defn run
  "Run the dispatch benchmark over the corpus."
  ([] (run {}))
  ([{:keys [iterations warmup] :or {iterations 50 warmup 5} :as opts}]
   (let [files (corpus-files)
         per-file (mapv #(bench-one % opts) files)
         total-effects (reduce + (map :effects per-file))
         total-median-ns (reduce + (map (comp :median-ns :e2e) per-file))]
     {:bench :dispatch
      :iterations iterations
      :warmup warmup
      :corpus-count (count files)
      :corpus-total-effects total-effects
      :sum-of-medians-ns total-median-ns
      :throughput-effects-per-s
      (long (/ (* total-effects 1000000000.0)
               (max 1 total-median-ns)))
      :per-file per-file
      :caveat
      "Anvil's dispatcher v1 RECORDS each step as a side-effect tuple
       rather than subprocess-executing it. A Jenkins comparison for
       BUILD EXECUTION waits for TX9. This benchmark measures anvil's
       pipeline ORCHESTRATION overhead — what gets ADDED on top of
       (TX9's) real subprocess execution."})))

(defn print-report [{:keys [per-file corpus-count corpus-total-effects
                            sum-of-medians-ns throughput-effects-per-s
                            caveat]}]
  (println)
  (println "  ── Dispatch benchmark ────────────────────────────────────────────────────")
  (println (format "    files=%d  total-effects=%d  sum-of-medians=%.1f ms  throughput=%,d effects/s"
                   corpus-count
                   corpus-total-effects
                   (m/ns->ms sum-of-medians-ns)
                   throughput-effects-per-s))
  (println)
  (println "    Per-file (sorted by size):")
  (doseq [{:keys [file stages effects e2e effects-per-second]} per-file]
    (println (format "      %-65s stages=%-2d effects=%-3d median=%6.2f ms  %,d eff/s"
                     (if (> (count file) 65) (str (subs file 0 62) "...") file)
                     stages effects
                     (m/ns->ms (:median-ns e2e))
                     effects-per-second)))
  (println)
  (println "  Caveat:")
  (doseq [line (str/split-lines caveat)]
    (println (str "    " (str/triml line)))))
