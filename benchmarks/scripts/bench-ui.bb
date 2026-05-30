#!/usr/bin/env bb

;; bench-ui.bb — UI page TTFB harness for anvil (TU0.7).
;;
;; Companion to bench-vs-jenkins.bb. Where bench-vs-jenkins.bb compares
;; anvil's REST + build wall-clock against Jenkins LTS, this harness
;; focuses on the *UI surface*: each admin page's time-to-first-byte
;; on a representative dataset.
;;
;; Per AU11, every page must p50 < 50 ms on the bench dataset. This
;; script prints the numbers; the CI gate that enforces them is TU6.4.
;;
;; Assumes anvil is already running. Defaults to http://localhost:8765
;; (the bench port used by bench-vs-jenkins.bb), so the two harnesses
;; can run back-to-back against the same server.
;;
;; Usage:
;;   ./bench-ui.bb                         # 200 iters per page
;;   ./bench-ui.bb --iters 1000
;;   ./bench-ui.bb --anvil-url http://localhost:8080
;;   ./bench-ui.bb --emit-edn results/ui-2026-05-30.edn

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli]
         '[clojure.pprint :as pp])

(def cli-options
  [[nil "--iters N"     "GET iterations per page" :default 200 :parse-fn #(Integer/parseInt %)]
   [nil "--anvil-url URL" "Base URL of running anvil" :default "http://localhost:8765"]
   [nil "--emit-edn PATH" "If set, write the full result map to PATH as EDN"]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; Pages under test
;;
;; Every URL the admin UI exposes. The :name is also used as the EDN
;; key and CI gate identifier — KEEP STABLE across runs so the gate
;; can diff yesterday's run against today's.
;; ---------------------------------------------------------------------------

(def pages
  [{:name :dashboard       :path "/"}
   {:name :jobs            :path "/jobs"}
   {:name :queue           :path "/queue"}
   {:name :coverage        :path "/coverage"}
   {:name :api-status      :path "/api/status"}
   {:name :api-health      :path "/api/health"}
   {:name :vendor-htmx     :path "/public/vendor/htmx.min.js"}])

;; ---------------------------------------------------------------------------
;; Timing
;; ---------------------------------------------------------------------------

(defn- now-ns [] (System/nanoTime))
(defn- ms [ns] (/ (double ns) 1e6))

(defn- pct [sorted p]
  (let [n (count sorted)]
    (if (zero? n) 0.0
        (nth sorted (max 0 (min (dec n) (int (Math/floor (* p (dec n))))))))))

(defn- summarize [ns-lats]
  (let [sorted (sort ns-lats)
        n (count sorted)
        total (reduce + 0 sorted)]
    {:n n
     :min  (ms (first sorted))
     :p50  (ms (pct sorted 0.50))
     :p95  (ms (pct sorted 0.95))
     :p99  (ms (pct sorted 0.99))
     :max  (ms (last sorted))
     :mean (ms (if (pos? n) (/ total n) 0))}))

(defn- time-page
  "GET `url` `n` times after a 5-req warmup. Returns latency summary in
   ms. Treats anything outside 200..399 as a hard error so we don't
   silently average 404s in."
  [url n]
  (dotimes [_ 5] (http/get url {:throw false}))
  (let [bad (atom 0)
        lats (for [_ (range n)
                   :let [t0 (now-ns)
                         r  (http/get url {:throw false})
                         dt (- (now-ns) t0)]]
               (do
                 (when-not (and (>= (:status r) 200) (< (:status r) 400))
                   (swap! bad inc))
                 dt))
        s (summarize lats)]
    (assoc s :error-count @bad)))

(defn- fmt-row [page dist]
  (format "  %-18s  n=%-4d  min=%6.3f  p50=%6.3f  p95=%6.3f  p99=%6.3f  mean=%6.3f%s"
          (name page) (:n dist) (:min dist) (:p50 dist) (:p95 dist) (:p99 dist) (:mean dist)
          (if (zero? (:error-count dist 0))
            ""
            (format "  ⚠ %d non-2xx" (:error-count dist)))))

;; ---------------------------------------------------------------------------
;; Budget check (AU11)
;; ---------------------------------------------------------------------------

(def ^:private budget-ms 50.0)

(defn- check-budget [results]
  (let [over (for [[p dist] results
                   :when (> (:p50 dist) budget-ms)]
               [p (:p50 dist)])]
    (when (seq over)
      (println "\n  ⚠ UI budget exceeded — pages with p50 > " budget-ms "ms:")
      (doseq [[p p50] over]
        (println (format "      %s  p50=%.3f ms" (name p) p50))))
    {:budget-ms budget-ms :over (mapv first over)}))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)
        {:keys [iters anvil-url emit-edn help]} options]
    (when help (println "see header comment") (System/exit 0))

    (println "\n══════════════════════════════════════════════════════════════════════")
    (println "  anvil UI — page TTFB bench (TU0.7)")
    (println (str "  Server:  " anvil-url))
    (println (str "  Iters:   " iters " per page, after 5-req warmup"))
    (println (str "  Budget:  p50 < " budget-ms " ms per page (AU11)"))
    (println "══════════════════════════════════════════════════════════════════════\n")

    (let [results (into {}
                        (for [{:keys [name path]} pages]
                          (let [dist (time-page (str anvil-url path) iters)]
                            (println (fmt-row name dist))
                            [name dist])))
          budget (check-budget results)]
      (println "\n══════════════════════════════════════════════════════════════════════")
      (when emit-edn
        (fs/create-dirs (fs/parent emit-edn))
        (spit emit-edn
              (with-out-str
                (pp/pprint
                 {:bench :anvil-ui-ttfb
                  :anvil-url anvil-url
                  :iters iters
                  :budget-ms budget-ms
                  :results results
                  :over-budget (:over budget)})))
        (println (str "  ⤷ wrote EDN to " emit-edn))))))

(apply -main *command-line-args*)
