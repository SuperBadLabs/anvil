(ns anvil.flaky
  "Passed-on-retry flaky-test detection — v0.4 board T1.1.

   ## What flaky means at v0.4.0

   Per **AV4-3** the only definition at v0.4.0 is **passed-on-retry**:
   within a single build, the same test was attempted multiple times
   (via `retry(N) { sh '<runs test>' }` in Jenkinsfile-Pipeline) and:

     - one or more earlier attempts ended in :failed or :errored
     - a later attempt ended in :passed

   No statistical models.  No time-series anomaly detection.  No
   cross-build flake-rate scoring.  Those layer on in v0.4.x if the
   simple definition leaves gaps.

   ## Substrate

   `anvil.storage.test-results/find-results-all-attempts` returns
   per-attempt rows for a build, ordered by class → name →
   attempt_number ASC.  Group by `:test-id`, walk each group's
   `:status` sequence, decide flaky.

   The substrate's per-attempt rows arrive there via T1.5 (real
   h-retry loop calling the JUnit scanner per attempt).  Until T1.5
   lands, the substrate carries single-attempt rows for every build
   — `detect-flaky-tests` on those returns an empty map (no
   retries, no flake-by-this-definition).  That's correct, not a
   gap: a one-attempt build has no flake signal by passed-on-retry.

   ## Wiring (T1.4 follow-up)

   Build-completion hook calls:

     (let [results (test-results/find-results-all-attempts j n)
           flaky   (flaky/detect-flaky-tests results)]
       (when (seq flaky)
         (test-results/write-flaky-flags! j n flaky)
         (publish! [:build j n]
                   {:type :flaky-flagged
                    :job-name j :build-number n
                    :flaky flaky})))

   The :flaky-flagged event topic is reserved in
   `anvil.events.topics` (T0.4); the producer is T1.4."
  (:require [clojure.set :as set]))

(defn- attempt-statuses
  "Walk a group's rows (already sorted by attempt_number ASC) and
   return the vector of status keywords.  Used by analyzers below."
  [group-rows]
  (mapv :status group-rows))

(defn- failure?
  "A status that counts as a 'failed attempt' for the passed-on-retry
   analysis.  :passed and :skipped do not.  :errored does (test ran
   but raised) and :failed does (assertion violated)."
  [status]
  (contains? #{:failed :errored} status))

(defn- passed-on-retry?
  "True iff the attempt status sequence carries at least one
   :failed/:errored followed (eventually) by :passed.  The
   board's exact definition."
  [statuses]
  (let [any-fail? (some failure? statuses)
        terminal-pass? (= :passed (peek statuses))
        ;; Defensive: a build that recorded only the final attempt
        ;; (no per-attempt scan) has a single-element vector and
        ;; will trivially fail this — that's the documented
        ;; substrate-not-yet-there case.
        multi-attempt? (< 1 (count statuses))]
    (and multi-attempt? any-fail? terminal-pass?)))

(defn- retry-count
  "Number of retry attempts the test made beyond the first.  For
   anvil_test_results this is `(attempt-number-of-the-pass) - 1`.

   The board's spec stores this denormalized so the dashboard widget
   doesn't recompute on every render."
  [statuses]
  (max 0 (dec (count statuses))))

(defn detect-flaky-tests
  "Analyze a collection of per-attempt rows from
   `anvil.storage.test-results/find-results-all-attempts`.

   Returns `{test-id → retry-count}` for tests that match
   passed-on-retry.

   Tests with a single attempt (no retry) never appear.  Tests
   whose final attempt was a failure also never appear — those are
   honest fails, not flakes.

   The result is suitable for passing straight to
   `test-results/write-flaky-flags!`."
  [per-attempt-rows]
  (->> per-attempt-rows
       (group-by :test-id)
       (keep (fn [[test-id rows]]
               (let [;; group-by is stable but the storage already
                     ;; ordered by attempt_number ASC; re-sort
                     ;; defensively so a future caller passing
                     ;; arbitrary order still gets the right answer.
                     ordered  (sort-by :attempt-number rows)
                     statuses (attempt-statuses ordered)]
                 (when (passed-on-retry? statuses)
                   [test-id (retry-count statuses)]))))
       (into {})))

(defn flaky-count
  "Number of flaky tests in a per-attempt-row collection.  Mainly for
   logging and the per-build summary header."
  [per-attempt-rows]
  (count (detect-flaky-tests per-attempt-rows)))

;; ---------------------------------------------------------------------------
;; Cross-build "most flaky" aggregation (T1.3 dashboard substrate)
;; ---------------------------------------------------------------------------

(defn rank-by-flake-rate
  "Given a sequence of `{:test-id … :flaky? bool :build-number n}`
   maps spanning recent builds (typically 30), rank test-ids by
   flake rate descending.

   Returns `[{:test-id … :flake-count N :build-count M :flake-rate
   (N/M)} …]` sorted by flake-rate then flake-count, both
   descending.  Ties broken by test-id ascending for stable
   pagination.

   :flake-count = builds in which the test was :flaky? true
   :build-count = builds in which the test appeared at all
   :flake-rate  = flake-count / build-count (rational; UI formats)

   Tests that never flaked across the window are excluded."
  [recent-rows]
  (let [by-test (group-by :test-id recent-rows)]
    (->> by-test
         (keep (fn [[test-id rows]]
                 (let [builds  (into #{} (map :build-number) rows)
                       flakes  (into #{} (comp (filter :flaky?) (map :build-number)) rows)
                       fc      (count flakes)
                       bc      (count builds)]
                   (when (pos? fc)
                     {:test-id     test-id
                      :flake-count fc
                      :build-count bc
                      :flake-rate  (/ fc bc)}))))
         (sort-by (fn [{:keys [flake-rate flake-count test-id]}]
                    ;; Rate and count want descending → negate the
                    ;; sort key so a larger value becomes a smaller
                    ;; key. test-id keeps its natural ascending
                    ;; order for a stable tie-break (pagination).
                    [(- (double flake-rate))
                     (- flake-count)
                     test-id]))
         vec)))
