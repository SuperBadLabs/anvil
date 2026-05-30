#!/usr/bin/env bb

;; ui-perf-gate.bb — TU6.4 UI perf CI gate.
;;
;; Reads anvil/benchmarks/results/ui-baseline-2026-05-30.edn as the
;; reference, runs bench-ui.bb live, compares per-page p50, and
;; fails (exit 1) if EITHER:
;;     - any page p50 exceeds the AU11 50ms absolute budget
;;     - any page p50 regresses > THRESHOLD% vs the committed baseline
;;
;; Designed to mirror the sibling perf-regression.bb shape so the
;; warning/comment infra in .github/workflows/perf-regression.yml
;; works the same way.
;;
;; Usage:
;;   ./ui-perf-gate.bb                                       # run live, compare
;;   ./ui-perf-gate.bb --threshold-pct 20 --budget-ms 50
;;   ./ui-perf-gate.bb --baseline path/to/edn --warn-only

(require '[babashka.process :as bp]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli])

(def cli-options
  [[nil "--baseline PATH" "EDN baseline written by bench-ui.bb --emit-edn"
    :default "anvil/benchmarks/results/ui-baseline-2026-05-30.edn"]
   [nil "--anvil-url URL" "Where anvil is running" :default "http://localhost:8765"]
   [nil "--iters N"      "Bench iterations per page" :default 100 :parse-fn #(Integer/parseInt %)]
   [nil "--threshold-pct N" "% p50 regression that fails the gate"
    :default 20 :parse-fn #(Integer/parseInt %)]
   [nil "--budget-ms N"  "Absolute p50 ceiling per page (AU11)"
    :default 50 :parse-fn #(Integer/parseInt %)]
   [nil "--warn-only"    "Exit 0 even if checks fail (CI soft mode)"]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; Live measurement — shell out to bench-ui.bb and parse its EDN dump
;; ---------------------------------------------------------------------------

(defn- run-bench! [{:keys [anvil-url iters]}]
  (let [tmp (str "/tmp/ui-perf-gate-" (System/currentTimeMillis) ".edn")
        bench-script "anvil/benchmarks/scripts/bench-ui.bb"]
    (println (str "  ⮕ ./" bench-script " --iters " iters " --anvil-url " anvil-url))
    (let [{:keys [exit]}
          (bp/shell {:continue true}
                    bench-script
                    "--iters" (str iters)
                    "--anvil-url" anvil-url
                    "--emit-edn" tmp)]
      (when-not (zero? exit)
        (println "  ⚠ bench-ui.bb exited" exit)
        (System/exit 2))
      (edn/read-string (slurp tmp)))))

;; ---------------------------------------------------------------------------
;; Compare
;; ---------------------------------------------------------------------------

(defn- pct-delta [old new]
  (* 100.0 (/ (- new old) (max 0.001 old))))

(defn- compare-row [page baseline-p50 live-p50]
  (let [delta (when (and baseline-p50 (pos? live-p50))
                (pct-delta baseline-p50 live-p50))]
    {:page page
     :baseline-p50 baseline-p50
     :live-p50 live-p50
     :pct-delta delta}))

(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)
        {:keys [baseline iters threshold-pct budget-ms warn-only help]} options]
    (when help (println "see header comment") (System/exit 0))

    (println "\n══════════════════════════════════════════════════════════════════════")
    (println "  anvil UI perf gate (TU6.4)")
    (println (str "  Baseline:        " baseline))
    (println (str "  Iters/page:      " iters))
    (println (str "  Budget (abs):    p50 < " budget-ms " ms"))
    (println (str "  Threshold (rel): p50 within +" threshold-pct "% of baseline"))
    (when warn-only (println "  Mode:            SOFT (warn-only)"))
    (println "══════════════════════════════════════════════════════════════════════\n")

    (let [base (edn/read-string (slurp baseline))
          live (run-bench! options)
          base-results (:results base)
          live-results (:results live)
          rows (for [[k v] live-results]
                 (compare-row k
                              (get-in base-results [k :p50])
                              (:p50 v)))
          budget-violations (filter #(> (:live-p50 %) budget-ms) rows)
          regression-violations
          (filter (fn [{:keys [pct-delta]}]
                    (and pct-delta (> pct-delta threshold-pct)))
                  rows)]

      (println (format "%-18s %12s %12s %12s   %s"
                       "page" "baseline ms" "live ms" "Δ%" "verdict"))
      (println (apply str (repeat 80 "─")))
      (doseq [{:keys [page baseline-p50 live-p50 pct-delta]} rows]
        (let [over-budget? (> live-p50 budget-ms)
              regressed?   (and pct-delta (> pct-delta threshold-pct))
              verdict (cond
                        over-budget? "BUDGET ✗"
                        regressed?   "REGRESS ✗"
                        :else        "ok")]
          (println (format "%-18s %12.3f %12.3f %+12.1f   %s"
                           (name page)
                           (double (or baseline-p50 0.0))
                           (double live-p50)
                           (double (or pct-delta 0.0))
                           verdict))))
      (println)
      (when (seq budget-violations)
        (println (str "  ⚠ " (count budget-violations) " page(s) exceed the "
                      budget-ms "ms p50 budget:"))
        (doseq [v budget-violations]
          (println (format "      %s = %.3f ms" (name (:page v)) (:live-p50 v)))))
      (when (seq regression-violations)
        (println (str "  ⚠ " (count regression-violations) " page(s) regressed > "
                      threshold-pct "% vs baseline:"))
        (doseq [v regression-violations]
          (println (format "      %s = %+.1f%% (baseline %.3f → live %.3f)"
                           (name (:page v)) (:pct-delta v)
                           (:baseline-p50 v) (:live-p50 v)))))
      (let [bad? (or (seq budget-violations) (seq regression-violations))]
        (cond
          (and bad? warn-only)
          (do (println "\n  (warn-only) UI perf gate has findings but exiting 0.")
              (System/exit 0))

          bad?
          (do (println "\n  UI perf gate FAILED.")
              (System/exit 1))

          :else
          (println "\n  ✓ UI perf gate PASSED."))))))

(apply -main *command-line-args*)
