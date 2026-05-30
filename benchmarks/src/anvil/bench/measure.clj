(ns anvil.bench.measure
  "Tiny measurement helper. Wraps criterium for the common case + adds a
   per-corpus-file timing harness with warmup and per-percentile output.

   We deliberately don't try to be a full microbench framework — for
   anvil's purposes the goal is a reproducible 'this many ms per
   Jenkinsfile' number that the README can publish.")

;; Plain System/nanoTime path — what most of our benchmarks use, since
;; criterium overhead is meaningful for our >1 ms operations.

(defn ^:no-doc nanos-since [^long t0]
  (- (System/nanoTime) t0))

(defn time-once
  "Run thunk once, return [result elapsed-ns]."
  [thunk]
  (let [t0 (System/nanoTime)
        r (thunk)]
    [r (nanos-since t0)]))

(defn ^:no-doc percentile
  "p in [0,1]; returns the p-th percentile of a sorted numeric coll."
  [sorted p]
  (let [n (count sorted)
        idx (min (dec n) (int (Math/floor (* p (dec n)))))]
    (nth sorted (max 0 idx))))

(defn summarize-ns
  "Given a seq of nanosecond timings, return {:n :min-ns :max-ns :mean-ns
   :median-ns :p95-ns :p99-ns :total-ns}."
  [timings]
  (let [v (vec timings)
        sorted (sort v)
        n (count v)
        total (reduce + 0 v)]
    {:n         n
     :min-ns    (first sorted)
     :max-ns    (last sorted)
     :mean-ns   (long (/ total n))
     :median-ns (percentile sorted 0.50)
     :p95-ns    (percentile sorted 0.95)
     :p99-ns    (percentile sorted 0.99)
     :total-ns  total}))

(defn ns->ms [ns] (/ (double ns) 1000000.0))
(defn ns->us [ns] (/ (double ns) 1000.0))

(defn format-summary
  "Pretty-print a summary in human ms."
  [{:keys [n min-ns max-ns mean-ns median-ns p95-ns p99-ns]}]
  (format "  n=%-5d  min=%6.2f ms  median=%6.2f ms  mean=%6.2f ms  p95=%7.2f ms  p99=%7.2f ms  max=%7.2f ms"
          n
          (ns->ms min-ns) (ns->ms median-ns) (ns->ms mean-ns)
          (ns->ms p95-ns) (ns->ms p99-ns) (ns->ms max-ns)))

(defn measure
  "Run `thunk` `iterations` times after `warmup` iterations. Returns a
   summary map."
  [{:keys [iterations warmup] :or {iterations 50 warmup 5}} thunk]
  (dotimes [_ warmup] (thunk))
  (let [timings (vec (repeatedly iterations #(second (time-once thunk))))]
    (summarize-ns timings)))
