(ns anvil.storage.test-results
  "SQLite persistence for per-build test results + per-build summary
   (T1.3 of the v0.3 board).

   Two tables (see migration 005-test-results):

     anvil_test_results
       — one row per <testcase> across all suites in a build
       — keyed on (job_name, build_number, test_id) but with an
         INTEGER autoincrement PK so duplicate test_ids in the same
         build (parameterized tests, retries) don't conflict
       — CASCADE delete on builds

     anvil_test_summaries
       — one row per (job_name, build_number)
       — denormalized counts so dashboard renders don't COUNT-with-
         CASE-WHEN per page load (TU0.7 budget allergy)

   The scanner (anvil.compat.junit/scan-build-artifacts) calls
   `record-build-results!` after parsing — one transactional bulk
   insert + summary upsert per build. Producers above the storage
   layer never see SQL."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [anvil.storage.db :as db]))

;; ---------------------------------------------------------------------------
;; Row ⇄ map
;; ---------------------------------------------------------------------------

(defn- result-row->map [row]
  (when row
    {:id             (:anvil_test_results/id row)
     :job-name       (:anvil_test_results/job_name row)
     :build-number   (:anvil_test_results/build_number row)
     :test-id        (:anvil_test_results/test_id row)
     :name           (:anvil_test_results/test_name row)
     :class          (:anvil_test_results/test_class row)
     :status         (keyword (:anvil_test_results/status row))
     :duration-ms    (:anvil_test_results/duration_ms row)
     :failure-msg    (:anvil_test_results/failure_msg row)
     :failure-type   (:anvil_test_results/failure_type row)
     :failure-trace  (:anvil_test_results/failure_trace row)
     ;; v0.4 T1.2 — flaky-test substrate. attempt-number is always
     ;; ≥ 1; flaky? is nil until anvil.flaky/detect runs over the
     ;; build, then false/true; retry-count denormalizes
     ;; (max(attempt) − 1) for the (build, test) group.
     :attempt-number (:anvil_test_results/attempt_number row)
     :flaky?         (when-let [v (:anvil_test_results/flaky_bool row)]
                       (= 1 v))
     :retry-count    (:anvil_test_results/retry_count row)}))

(defn- summary-row->map [row]
  (when row
    {:job-name     (:anvil_test_summaries/job_name row)
     :build-number (:anvil_test_summaries/build_number row)
     :tests        (:anvil_test_summaries/tests row)
     :passed       (:anvil_test_summaries/passed row)
     :failed       (:anvil_test_summaries/failed row)
     :errored      (:anvil_test_summaries/errored row)
     :skipped      (:anvil_test_summaries/skipped row)
     :duration-ms  (:anvil_test_summaries/duration_ms row)
     :parse-errors (:anvil_test_summaries/parse_errors row)}))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn- insert-case-row [job-name build-number attempt-number c]
  [job-name
   build-number
   (:test-id c)
   (:name c)
   (:class c)
   (name (:status c))
   (or (:duration-ms c) 0)
   (:failure-msg c)
   (:failure-type c)
   (:failure-trace c)
   attempt-number])

(defn record-build-results!
  "Persist a parsed surefire tree (the output of
   `anvil.compat.junit/parse-surefire-tree`) for `(job-name,
   build-number)`. Replaces any previous test rows for this build
   *for the same attempt-number* under a single transaction so
   partial inserts aren't observable.

   Optional arg `opts`:
     :attempt-number — 1-based attempt index within this build
                       (default 1). Multi-attempt rows are appended
                       so anvil.flaky/detect (T1.1) can spot
                       passed-on-retry. When called without :attempt-
                       number, behaves as before: single attempt,
                       prior rows wiped, single-attempt summary
                       overwritten.

   Returns the persisted summary map."
  ([job-name build-number tree]
   (record-build-results! job-name build-number tree {}))
  ([job-name build-number tree {:keys [attempt-number]
                                :or {attempt-number 1}}]
  (let [ds (db/datasource)
        cases (mapcat :cases (:suites tree))
        totals (:totals tree)
        parse-error-count (count (:parse-errors tree))]
    (when ds
      (jdbc/with-transaction [tx ds]
        ;; Idempotent within (build, attempt): scan-then-rescan
        ;; replaces prior rows FOR THIS ATTEMPT only.  Other attempts
        ;; in the same build (earlier retries) survive — that's the
        ;; substrate the flaky analyzer needs.
        (jdbc/execute-one!
         tx
         ["DELETE FROM anvil_test_results
           WHERE job_name = ? AND build_number = ? AND attempt_number = ?"
          job-name build-number attempt-number])
        (when (seq cases)
          (jdbc/execute-batch!
           tx
           "INSERT INTO anvil_test_results
             (job_name, build_number, test_id, test_name, test_class,
              status, duration_ms, failure_msg, failure_type, failure_trace,
              attempt_number)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
           (mapv #(insert-case-row job-name build-number attempt-number %) cases)
           {}))
        (jdbc/execute-one!
         tx
         ["INSERT INTO anvil_test_summaries
             (job_name, build_number, tests, passed, failed, errored,
              skipped, duration_ms, parse_errors)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(job_name, build_number) DO UPDATE SET
             tests        = excluded.tests,
             passed       = excluded.passed,
             failed       = excluded.failed,
             errored      = excluded.errored,
             skipped      = excluded.skipped,
             duration_ms  = excluded.duration_ms,
             parse_errors = excluded.parse_errors,
             scanned_at   = datetime('now')"
          job-name build-number
          (:tests totals) (:passed totals) (:failed totals)
          (:errored totals) (:skipped totals) (:duration-ms totals)
          parse-error-count])))
    {:job-name job-name
     :build-number build-number
     :attempt-number attempt-number
     :tests (:tests totals)
     :passed (:passed totals)
     :failed (:failed totals)
     :errored (:errored totals)
     :skipped (:skipped totals)
     :duration-ms (:duration-ms totals)
     :parse-errors parse-error-count})))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn find-summary
  "Lookup the per-build test summary. Returns nil if no scan landed."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (-> (jdbc/execute-one!
         ds
         ["SELECT * FROM anvil_test_summaries
           WHERE job_name = ? AND build_number = ?"
          job-name build-number])
        summary-row->map)))

(defn find-results
  "All persisted case rows for a build, ordered by class then name."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND build_number = ?
            ORDER BY test_class, test_name"
           job-name build-number])
         (mapv result-row->map))))

(defn find-failed-results
  "Just the failed/errored rows for a build — the dashboard's
   'Failures' card. Faster than fetching all + filtering when most
   tests pass."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND build_number = ?
              AND status IN ('failed','errored')
            ORDER BY test_class, test_name"
           job-name build-number])
         (mapv result-row->map))))

(defn find-test-history
  "Recent-N results for a single test (job + test_id), most-recent-
   first. Drives the per-test sparkline + the trend-by-status
   dashboard widget (T1.4)."
  [job-name test-id limit]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND test_id = ?
            ORDER BY build_number DESC LIMIT ?"
           job-name test-id limit])
         (mapv result-row->map))))

(defn recent-summaries
  "Recent-N per-build summaries for a job — drives the build-page
   sparkline of pass-rate from the v0.3 board T1.4 spec."
  [job-name limit]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_summaries
            WHERE job_name = ?
            ORDER BY build_number DESC LIMIT ?"
           job-name limit])
         (mapv summary-row->map))))

;; ---------------------------------------------------------------------------
;; v0.4 T1.2 — per-attempt / flaky support
;; ---------------------------------------------------------------------------

(defn find-results-all-attempts
  "All persisted case rows for a build *including every retry attempt*,
   ordered by class → name → attempt_number ASC.  The flaky analyzer
   consumes this to spot passed-on-retry sequences."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND build_number = ?
            ORDER BY test_class, test_name, attempt_number ASC"
           job-name build-number])
         (mapv result-row->map))))

(defn find-flaky-tests
  "Rows in this build that were flagged :flaky? true (passed-on-retry).
   The dashboard's per-build flaky widget hits this.

   Returns one row per flaky test_id (the latest attempt — the
   succeeding one); the row's :retry-count tells the UI how many
   prior attempts failed."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ;; Sub-select picks each flaky test's max(attempt_number)
          ;; — that's the attempt that finally passed.
          ["SELECT * FROM anvil_test_results r
            WHERE r.job_name = ? AND r.build_number = ?
              AND r.flaky_bool = 1
              AND r.attempt_number = (
                SELECT MAX(attempt_number) FROM anvil_test_results
                WHERE job_name = r.job_name
                  AND build_number = r.build_number
                  AND test_id = r.test_id
              )
            ORDER BY r.test_class, r.test_name"
           job-name build-number])
         (mapv result-row->map))))

(defn recent-flaky-window
  "Cross-build minimal projection driving the v0.4 /flaky dashboard
   (T1.3).  Returns one row per `(test_id, build_number)` pair across
   the most recent `limit` builds (instance-wide; or filtered to a
   job when `job-name` is non-nil), preserving only what
   `anvil.flaky/rank-by-flake-rate` needs:

     {:test-id <str> :build-number <n> :flaky? <bool>}

   Only the latest attempt of each (build, test) is returned — the
   sub-select picks max(attempt_number) so a flaky-then-passed test
   shows as :flaky? true (the latest attempt is the passing one, and
   write-flaky-flags! has stamped flaky_bool=1 across all attempts
   for that group)."
  [job-name limit]
  (when-let [ds (db/datasource)]
    (let [base-sql "SELECT r.test_id, r.build_number, r.flaky_bool
                    FROM anvil_test_results r
                    WHERE r.attempt_number = (
                      SELECT MAX(attempt_number) FROM anvil_test_results
                      WHERE job_name = r.job_name
                        AND build_number = r.build_number
                        AND test_id = r.test_id
                    )"
          where-job (when job-name "AND r.job_name = ?")
          tail "ORDER BY r.build_number DESC LIMIT ?"
          sql (str base-sql " " (or where-job "") " " tail)
          params (if job-name [job-name limit] [limit])]
      (->> (jdbc/execute! ds (into [sql] params))
           (mapv (fn [row]
                   {:test-id      (:anvil_test_results/test_id row)
                    :build-number (:anvil_test_results/build_number row)
                    :flaky?       (= 1 (:anvil_test_results/flaky_bool row))}))))))

(defn write-flaky-flags!
  "Write `{test-id → retry-count}` map back across all per-attempt rows
   for a build.  Every row with a test_id in the map gets
   `flaky_bool=1` + the named `retry_count`.  Rows for test_ids NOT
   in the map are flipped to `flaky_bool=0` so a re-detect after
   wiped retries clears stale flags.

   Returns the count of rows touched."
  [job-name build-number test-id->retry-count]
  (when-let [ds (db/datasource)]
    (jdbc/with-transaction [tx ds]
      ;; Clear prior flags for this build (idempotent re-detect).
      (jdbc/execute-one!
       tx
       ["UPDATE anvil_test_results
         SET flaky_bool = 0, retry_count = 0
         WHERE job_name = ? AND build_number = ?"
        job-name build-number])
      (reduce
       (fn [acc [test-id retry-count]]
         (let [n (-> (jdbc/execute-one!
                      tx
                      ["UPDATE anvil_test_results
                        SET flaky_bool = 1, retry_count = ?
                        WHERE job_name = ? AND build_number = ?
                          AND test_id = ?"
                       retry-count job-name build-number test-id])
                     :next.jdbc/update-count
                     (or 0))]
           (+ acc n)))
       0
       test-id->retry-count))))
