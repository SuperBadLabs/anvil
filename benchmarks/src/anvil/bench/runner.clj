(ns anvil.bench.runner
  "Main entry for anvil benchmarks. Runs each suite, prints a human
   report, and writes machine-readable EDN + JSON.

     lein with-profile +bench run -m anvil.bench.runner

   Output:
     benchmarks/results/<timestamp>.edn
     benchmarks/results/latest.edn      (symlink-style overwrite)

   Suites:
     parser    — Jenkinsfile source → IR throughput
     dispatch  — IR → recorded effects throughput
     api       — REST shim response-time distribution

   See benchmarks/README.md for the methodology + honest discussion of
   what is and isn't fairly compared to real Jenkins today."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [anvil.bench.parser :as p]
            [anvil.bench.dispatch :as disp]
            [anvil.bench.api :as api]))

(defn- ensure-results-dir! []
  (.mkdirs (io/file "benchmarks/results")))

(defn- write-results! [results]
  (ensure-results-dir!)
  (let [out (io/file "benchmarks/results" "latest.edn")]
    (with-open [w (io/writer out)]
      (binding [*out* w
                pp/*print-right-margin* 120]
        (pp/pprint results)))
    (println (str "\nResults written to " out))))

(defn -main [& args]
  (let [iterations (try (Integer/parseInt (or (first args) "30")) (catch Exception _ 30))
        opts {:iterations iterations :warmup (max 3 (int (/ iterations 10)))}]
    (println "===========================================================================")
    (println "  anvil benchmarks — iterations=%d warmup=%d" iterations (:warmup opts))
    (println "  See benchmarks/README.md for methodology.")
    (println "===========================================================================")

    (let [parser-result   (p/run opts)
          dispatch-result (disp/run opts)
          api-result      (api/run (merge opts {:iterations (* 5 iterations)
                                                :warmup (* 5 (:warmup opts))}))
          all {:run-at (str (java.time.Instant/now))
               :opts opts
               :parser   parser-result
               :dispatch dispatch-result
               :api      api-result}]
      (p/print-report parser-result)
      (disp/print-report dispatch-result)
      (api/print-report api-result)
      (println)
      (println "===========================================================================")
      (write-results! all)
      (shutdown-agents))))
