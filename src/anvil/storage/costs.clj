(ns anvil.storage.costs
  "v0.5 T2.1 — Cost persistence for anvil_build_costs.

   Stores and retrieves the monetary cost computed for each completed
   build. The cost recorder (anvil.cost.recorder) writes rows here
   on :build-done events; the /cost dashboard and per-build pill read
   from here.

   Public API:
     (record! {:job-name :build-number :host :duration-ms :rate-per-min
               :currency :total-cents :cache-saved-cents})
     (for-build job-name build-number)  → cost-map or nil
     (top-by-cost opts)                → [{:job-name :build-number :total-cents …}]
     (totals-30d)                       → {:total-cents :cache-saved-cents :build-count}"
  (:require [next.jdbc :as jdbc]
            [anvil.storage.db :as db]))

(defn- ds [] (db/datasource))

;; next.jdbc returns qualified keyword maps by default:
;;   :anvil_build_costs/job_name, :anvil_build_costs/total_cents, …
;; The aggregate query (totals-30d) uses aliases in a sub-select
;; without a table name, so it falls back to the alias itself as
;; the namespace: we use :next.jdbc/update-count style names there.

(defn- row->map
  "Convert a next.jdbc qualified-keyword result row for anvil_build_costs
   into a Clojure map with idiomatic kebab-case keys."
  [row]
  (when row
    {:job-name          (:anvil_build_costs/job_name row)
     :build-number      (:anvil_build_costs/build_number row)
     :host              (:anvil_build_costs/host row)
     :duration-ms       (:anvil_build_costs/duration_ms row)
     :rate-per-min      (:anvil_build_costs/rate_per_min row)
     :currency          (keyword (:anvil_build_costs/currency row))
     :total-cents       (:anvil_build_costs/total_cents row)
     :cache-saved-cents (:anvil_build_costs/cache_saved_cents row)
     :recorded-at       (:anvil_build_costs/recorded_at row)}))

(defn record!
  "Insert or replace the cost record for a build. Idempotent — re-recording
   the same build updates the existing row (e.g. if the recorder fires
   twice due to a restart)."
  [{:keys [job-name build-number host duration-ms rate-per-min currency
           total-cents cache-saved-cents]
    :or {host "localhost" duration-ms 0 rate-per-min 0.0 currency "usd"
         total-cents 0 cache-saved-cents 0}}]
  (when-not (ds)
    (throw (ex-info "anvil.storage.costs/record!: DB not initialized"
                    {:job-name job-name :build-number build-number})))
  (jdbc/execute-one!
   (ds)
   ["INSERT INTO anvil_build_costs
       (job_name, build_number, host, duration_ms, rate_per_min, currency,
        total_cents, cache_saved_cents, recorded_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
     ON CONFLICT(job_name, build_number) DO UPDATE SET
       host              = excluded.host,
       duration_ms       = excluded.duration_ms,
       rate_per_min      = excluded.rate_per_min,
       currency          = excluded.currency,
       total_cents       = excluded.total_cents,
       cache_saved_cents = excluded.cache_saved_cents,
       recorded_at       = excluded.recorded_at"
    (str job-name) (int build-number) (str host) (long duration-ms)
    (double rate-per-min) (str (name currency))
    (long total-cents) (long cache-saved-cents)])
  nil)

(defn for-build
  "Return the cost record for a specific build, or nil if not recorded."
  [job-name build-number]
  (when (ds)
    (row->map
     (jdbc/execute-one!
      (ds)
      ["SELECT * FROM anvil_build_costs WHERE job_name = ? AND build_number = ?"
       (str job-name) (int build-number)]))))

(defn top-by-cost
  "Return the top-N most expensive builds in the given window.
   Options:
     :limit       — max rows to return (default 20)
     :days        — rolling window in calendar days (default 30)
     :min-cents   — filter out builds with total_cents < this (default 0)
   Returns a vector of cost-maps, most expensive first."
  ([] (top-by-cost {}))
  ([{:keys [limit days min-cents] :or {limit 20 days 30 min-cents 0}}]
   (when (ds)
     (->> (jdbc/execute!
           (ds)
           ["SELECT * FROM anvil_build_costs
             WHERE recorded_at >= datetime('now', ?)
               AND total_cents >= ?
             ORDER BY total_cents DESC
             LIMIT ?"
            (str "-" days " days") (long min-cents) (int limit)])
          (mapv row->map)))))

(defn totals-30d
  "Summary statistics for the 30-day window: total cost + cache savings."
  []
  (when (ds)
    ;; The aggregate query uses column aliases that don't carry
    ;; anvil_build_costs as their namespace — next.jdbc uses the
    ;; statement's computed-column label as the qualifier, which
    ;; for aliases collapses to the bare alias keyword.
    ;; We fall back to both forms to be robust across jdbc driver versions.
    (let [row (jdbc/execute-one!
               (ds)
               ["SELECT
                   COALESCE(SUM(total_cents), 0) AS total_cents,
                   COALESCE(SUM(cache_saved_cents), 0) AS cache_saved_cents,
                   COUNT(*) AS build_count
                 FROM anvil_build_costs
                 WHERE recorded_at >= datetime('now', '-30 days')"])]
      {:total-cents       (or (:total_cents row)
                              (:anvil_build_costs/total_cents row)
                              0)
       :cache-saved-cents (or (:cache_saved_cents row)
                              (:anvil_build_costs/cache_saved_cents row)
                              0)
       :build-count       (or (:build_count row)
                              (:anvil_build_costs/build_count row)
                              0)})))
