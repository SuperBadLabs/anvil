#!/usr/bin/env bb

;; perf-regression.bb — TX12 perf regression gate.
;;
;; Run the anvil bench suite, then compare the fresh results against a
;; frozen baseline EDN. Classify every metric by ratio:
;;
;;   ratio < 1.5x worse  →  OK
;;   1.5x ≤ ratio < 2.0x → WARN  (yellow, doesn't fail)
;;   ratio ≥ 2.0x         → FAIL  (red, exits 1)
;;
;; A 2x slowdown in any metric is a hard regression that needs to be
;; investigated or the baseline updated explicitly.
;;
;; Usage:
;;   ./perf-regression.bb                    # full pipeline: re-bench + compare
;;   ./perf-regression.bb --no-bench         # compare existing latest.edn
;;   ./perf-regression.bb --baseline TAG     # compare against baselines/TAG.edn
;;   ./perf-regression.bb --warn-only        # never exit 1, only print
;;   ./perf-regression.bb --bench-iters N    # iterations per metric (default 30)
;;
;; To bump the baseline after an intentional change:
;;   cp anvil/benchmarks/results/latest.edn anvil/benchmarks/baselines/<tag>.edn
;;   git add anvil/benchmarks/baselines/<tag>.edn
;;
;; Output: human-readable table + exit code. Wire into CI via
;; .github/workflows/perf-regression.yml.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli])

(def cli-options
  [[nil "--no-bench"      "Skip re-running the bench; use existing latest.edn"]
   [nil "--baseline TAG"  "Baseline tag under benchmarks/baselines/" :default "v0.1.0"]
   [nil "--warn-only"     "Never exit non-zero even on FAIL classification"]
   [nil "--bench-iters N" "Iterations per metric when re-running" :default 30
    :parse-fn #(Integer/parseInt %)]
   [nil "--warn-ratio R"  "WARN threshold (default 1.5)" :default 1.5
    :parse-fn #(Double/parseDouble %)]
   [nil "--fail-ratio R"  "FAIL threshold (default 2.0)" :default 2.0
    :parse-fn #(Double/parseDouble %)]
   ["-h" "--help"]])

(def ^:private bench-dir "anvil/benchmarks")
(def ^:private results-path (str bench-dir "/results/latest.edn"))

;; ---------------------------------------------------------------------------
;; Bench invocation
;; ---------------------------------------------------------------------------

(defn run-bench! [iters]
  (println (format "→ Running bench (%d iterations)..." iters))
  (let [r (p/shell {:dir "anvil" :continue true}
                   "lein" "with-profile" "+bench" "run"
                   "-m" "anvil.bench.runner" (str iters))]
    (when-not (zero? (:exit r))
      (println "bench failed; aborting comparison")
      (System/exit 2))))

(defn read-edn [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

;; ---------------------------------------------------------------------------
;; Comparison
;; ---------------------------------------------------------------------------

(defn- safe-ratio [new old]
  (when (and (number? new) (number? old) (pos? old))
    (double (/ new old))))

(defn- classify [ratio warn-ratio fail-ratio]
  (cond
    (nil? ratio) :unknown
    ;; Faster than baseline (ratio < 1) is always OK.
    (<= ratio (/ 1.0 fail-ratio)) :speedup-strong
    (<= ratio (/ 1.0 warn-ratio)) :speedup
    (< ratio warn-ratio)          :ok
    (< ratio fail-ratio)          :warn
    :else                         :fail))

(defn- glyph [classification]
  (case classification
    :ok              "✓"
    :speedup         "▲"
    :speedup-strong  "▲"
    :warn            "⚠"
    :fail            "✗"
    :unknown         "?"))

(defn- color [classification]
  (case classification
    :ok              "[32m"  ; green
    :speedup         "[36m"  ; cyan
    :speedup-strong  "[36m"
    :warn            "[33m"  ; yellow
    :fail            "[31m"  ; red
    :unknown         "[90m"  ; gray
    "[0m"))

(def reset "[0m")

;; ---------------------------------------------------------------------------
;; Parser & dispatch per-file comparison
;; ---------------------------------------------------------------------------

(defn- per-file-index [suite]
  (into {} (for [f (:per-file suite)]
             [(:file f) f])))

(defn- compare-per-file [suite-key new-data old-data {:keys [warn-ratio fail-ratio]}]
  (let [new-files (per-file-index new-data)
        old-files (per-file-index old-data)
        common (sort (filter old-files (keys new-files)))]
    (vec
     (for [f common
           :let [new-med (get-in new-files [f :e2e :median-ns])
                 old-med (get-in old-files [f :e2e :median-ns])
                 r (safe-ratio new-med old-med)
                 cls (classify r warn-ratio fail-ratio)]]
       {:suite suite-key
        :metric (str f)
        :baseline-ms (/ (double (or old-med 0)) 1e6)
        :new-ms      (/ (double (or new-med 0)) 1e6)
        :ratio r
        :class cls}))))

;; ---------------------------------------------------------------------------
;; API per-endpoint comparison
;; ---------------------------------------------------------------------------

(defn- compare-api [new-data old-data {:keys [warn-ratio fail-ratio]}]
  (let [new-idx (into {} (for [e (:per-endpoint new-data)] [(:label e) e]))
        old-idx (into {} (for [e (:per-endpoint old-data)] [(:label e) e]))]
    (vec
     (for [k (sort (keys new-idx))
           :when (contains? old-idx k)
           :let [new-med (get-in new-idx [k :summary :median-ns])
                 old-med (get-in old-idx [k :summary :median-ns])
                 r (safe-ratio new-med old-med)
                 cls (classify r warn-ratio fail-ratio)]]
       {:suite :api
        :metric (str k)
        :baseline-ms (/ (double (or old-med 0)) 1e6)
        :new-ms      (/ (double (or new-med 0)) 1e6)
        :ratio r
        :class cls}))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt-ms [ms]
  (cond
    (< ms 0.01) (format "%.3f µs" (* ms 1000.0))
    (< ms 1)    (format "%.2f µs" (* ms 1000.0))
    (< ms 10)   (format "%.3f ms" ms)
    :else       (format "%.2f ms" ms)))

(defn- fmt-ratio [r]
  (cond
    (nil? r) "—"
    (>= r 1) (format "%.2fx slower" r)
    :else    (format "%.2fx faster" (/ 1.0 r))))

(defn- pad-right [s n]
  (let [s (str s)]
    (if (>= (count s) n) s (str s (apply str (repeat (- n (count s)) \space))))))

(defn- pad-left [s n]
  (let [s (str s)]
    (if (>= (count s) n) s (str (apply str (repeat (- n (count s)) \space)) s))))

(defn- trim-to [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (- n 3)) "..."))))

(defn- print-rows [rows]
  (let [w-suite  (apply max 8 (map #(count (name (:suite %))) rows))
        w-metric (min 70 (apply max 10 (map #(count (:metric %)) rows)))]
    (println)
    (println (str "    "
                  (pad-right "suite" w-suite) "  "
                  (pad-right "metric" w-metric) "  "
                  (pad-left "baseline" 12) "  "
                  (pad-left "new" 12) "  "
                  (pad-left "ratio" 16)))
    (println (str "    " (apply str (repeat (+ w-suite w-metric 50) "─"))))
    (doseq [{:keys [suite metric baseline-ms new-ms ratio class]} rows]
      (println (str "  " (color class) (glyph class) " " reset
                    (pad-right (name suite) w-suite) "  "
                    (pad-right (trim-to metric w-metric) w-metric) "  "
                    (pad-left (fmt-ms baseline-ms) 12) "  "
                    (pad-left (fmt-ms new-ms) 12) "  "
                    (pad-left (fmt-ratio ratio) 16))))))

(defn- summary [rows]
  (let [by-class (group-by :class rows)]
    {:total (count rows)
     :fail  (count (:fail by-class))
     :warn  (count (:warn by-class))
     :ok    (count (:ok by-class))
     :speedup (+ (count (:speedup by-class))
                 (count (:speedup-strong by-class)))
     :unknown (count (:unknown by-class))}))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [options errors]} (cli/parse-opts args cli-options)
        {:keys [no-bench baseline warn-only bench-iters help warn-ratio fail-ratio]}
        options
        baseline-path (str bench-dir "/baselines/" baseline ".edn")
        thresholds {:warn-ratio warn-ratio :fail-ratio fail-ratio}]
    (when help
      (println "Usage: perf-regression.bb [opts]")
      (println "Options:")
      (doseq [[s l d] [["--no-bench"      "skip the re-bench"]
                       ["--baseline TAG"  "compare vs baselines/TAG.edn"]
                       ["--warn-only"     "never exit non-zero"]
                       ["--bench-iters N" "iterations (default 30)"]
                       ["--warn-ratio R"  "WARN threshold (default 1.5)"]
                       ["--fail-ratio R"  "FAIL threshold (default 2.0)"]]]
        (println (format "  %-22s %s" s d)))
      (System/exit 0))
    (when (seq errors)
      (doseq [e errors] (println "ERROR:" e))
      (System/exit 2))

    (let [baseline-ir (or (read-edn baseline-path)
                          (do (println (str "No baseline at " baseline-path
                                            "; commit a baseline first."))
                              (System/exit 2)))
          _ (when-not no-bench (run-bench! bench-iters))
          latest-ir (or (read-edn results-path)
                        (do (println (str "Expected " results-path
                                           " — run without --no-bench first."))
                            (System/exit 2)))
          parser-rows   (compare-per-file :parser
                                          (:parser latest-ir)
                                          (:parser baseline-ir)
                                          thresholds)
          dispatch-rows (compare-per-file :dispatch
                                          (:dispatch latest-ir)
                                          (:dispatch baseline-ir)
                                          thresholds)
          api-rows      (compare-api (:api latest-ir)
                                     (:api baseline-ir)
                                     thresholds)
          all-rows (vec (concat parser-rows dispatch-rows api-rows))
          stats (summary all-rows)]

      (println (format "\n  Baseline: %s   (run at %s)"
                       baseline-path
                       (str (:run-at baseline-ir))))
      (println (format "  Latest:   %s (run at %s)"
                       results-path
                       (str (:run-at latest-ir))))

      (print-rows all-rows)

      (println)
      (println (format "  Summary: %d total · %s%d FAIL%s · %s%d WARN%s · %s%d OK%s · %d speedup · %d unknown"
                       (:total stats)
                       (color :fail)  (:fail stats)  reset
                       (color :warn)  (:warn stats)  reset
                       (color :ok)    (:ok stats)    reset
                       (:speedup stats)
                       (:unknown stats)))
      (println)

      (cond
        warn-only
        (System/exit 0)

        (pos? (:fail stats))
        (do (println (str "FAIL: " (:fail stats)
                          " metric(s) regressed by ≥" fail-ratio "x"))
            (System/exit 1))

        :else
        (do (println "OK: no FAIL-level regressions")
            (System/exit 0))))))

(apply -main *command-line-args*)
