(ns anvil.compat.jenkins.retry-fixtures-test
  "v0.4 T1.6 — four retry-shape fixtures that drive the dispatcher's
   real `h-retry` loop end-to-end through the IR translator.

   Scope: these fixtures complement `scope_wrappers_test.clj`'s three
   retry tests (first-success / fail-then-pass / exhaust).  The new
   shapes pinned here are the ones the v0.4 T1.5 + T1.6 work surfaced
   as load-bearing for downstream flake detection:

     1. Nested retry — outer `:current-retry-attempt` context value
        must be restored after the inner retry's :leave; otherwise the
        outer body's effects (which carry `:attempt` from ctx) would
        report the inner counter.
     2. Retry around multiple sh — when one body step in the middle
        fails, the *whole* body re-runs from the top, not just the
        failing step.  Jenkins semantics; the contract h-junit relies
        on for per-attempt row scanning to be honest.
     3. Attempt index threaded into every `:sh` effect — the v0.4
        T1.5 promise that lets `record-build-results!` partition rows
        by attempt without the dispatcher passing extra args.
     4. `retry(1)` boundary — emits the enter/attempt/leave envelope
        but never re-runs.  Guards against an off-by-one that would
        either re-run a healthy body or skip the envelope entirely."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]))

;; ---------------------------------------------------------------------------
;; Harness — same shape as scope_wrappers_test so the two files read
;; alike.  Kept inline (no shared helper ns) because each test file
;; wants to own its disposition tweaks.
;; ---------------------------------------------------------------------------

(defn- run-jenkinsfile [src & {:keys [canned fail-attempts]}]
  (let [ir (t/parse src)
        stage-block (for [s (:stages ir)]
                      {:name (:name s)
                       :steps (concat (:steps s)
                                      (get-in s [:post :always] []))})
        flat {:stages (vec stage-block)}
        d (ad/make (cond-> {:sh-canned (atom (or canned {}))}
                     fail-attempts (assoc :sh-fail-attempts (atom fail-attempts))))
        result (d/run-pipeline flat d {:cwd "/workspace"})]
    {:result result :effects @(:effects d)}))

;; ---------------------------------------------------------------------------
;; Shape 1 — Nested retry: outer attempt counter restored after inner
;; ---------------------------------------------------------------------------

(deftest nested-retry-restores-outer-current-attempt
  (testing "nested retry — inner :leave restores outer's :current-retry-attempt"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(2) {
                sh 'outer'
                retry(2) {
                  sh 'inner'
                }
                sh 'after-inner'
              }
            } } } }"
           ;; Inner sh fails once → inner retry recovers → outer body
           ;; passes on its first attempt.  No outer re-run needed.
           :fail-attempts {"inner" 1})]
      (let [shes (filter #(= :sh (first %)) effects)
            outer-shes (filter #(= "outer" (:cmd (second %))) shes)
            after-inner-shes (filter #(= "after-inner" (:cmd (second %))) shes)
            enters (filter #(= :retry/enter (first %)) effects)
            leaves (filter #(= :retry/leave (first %)) effects)]
        (is (= 2 (count enters))
            "two retry/enter — one outer, one inner")
        (is (= 2 (count leaves))
            "two retry/leave — one outer, one inner")
        ;; The outer body's `sh 'outer'` and `sh 'after-inner'` both
        ;; ran with ctx.:current-retry-attempt = 1 (the outer
        ;; attempt), NOT 2 (which would be the inner's final attempt
        ;; index leaking out).
        (is (= [1] (mapv #(:attempt (second %)) outer-shes))
            "`sh 'outer'` saw outer attempt index 1")
        (is (= [1] (mapv #(:attempt (second %)) after-inner-shes))
            "`sh 'after-inner'` — outer counter restored after inner :leave")
        ;; Inner's :leave fires first (nested → inner completes
        ;; before outer body finishes), reports recovery (2 attempts, :ok).
        ;; Outer's :leave fires second with attempts=1 (no outer retry needed).
        (let [inner-leave (first leaves)
              outer-leave (second leaves)]
          (is (= 2 (:attempts (second inner-leave)))
              "inner saw 2 attempts (recovered on the second)")
          (is (= :ok (:outcome (second inner-leave))))
          (is (= 1 (:attempts (second outer-leave)))
              "outer needed only 1 attempt — inner's recovery let outer's body finish cleanly")
          (is (= :ok (:outcome (second outer-leave)))))))))

;; ---------------------------------------------------------------------------
;; Shape 2 — Retry around multiple sh: whole body re-runs from the top
;; ---------------------------------------------------------------------------

(deftest retry-around-multiple-sh-re-runs-whole-body
  (testing "retry body with multiple sh — failure mid-body re-runs ALL steps from the top"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(3) {
                sh 'setup'
                sh 'flaky-middle'
                sh 'cleanup'
              }
            } } } }"
           ;; Middle step fails the first attempt only.
           :fail-attempts {"flaky-middle" 1})]
      (let [shes (filter #(= :sh (first %)) effects)
            by-cmd (group-by #(:cmd (second %)) shes)]
        ;; setup ran twice (re-ran from the top on attempt 2).
        (is (= 2 (count (get by-cmd "setup")))
            "setup re-ran on attempt 2 — body is re-entered from the top")
        ;; flaky-middle ran twice (once failed, once succeeded).
        (is (= 2 (count (get by-cmd "flaky-middle"))))
        ;; cleanup ran only ONCE — on attempt 1 the failure short-circuited
        ;; before reaching cleanup.  On attempt 2 it ran after the recovery.
        (is (= 1 (count (get by-cmd "cleanup")))
            "cleanup never ran on attempt 1 — flaky-middle's failure short-circuited the body")
        ;; Effect ordering pins the re-entry shape:
        ;; enter, setup#1, flaky-middle#1 (fail), attempt#1, setup#2,
        ;; flaky-middle#2 (ok), cleanup, attempt#2, leave.
        (let [types (mapv first effects)]
          (is (= [:retry/enter
                  :sh :sh :retry/attempt
                  :sh :sh :sh :retry/attempt
                  :retry/leave]
                 types)
              "the canonical re-run-from-top ordering"))))))

;; ---------------------------------------------------------------------------
;; Shape 3 — Attempt index threaded into every :sh effect
;; ---------------------------------------------------------------------------

(deftest retry-threads-attempt-index-into-every-sh-effect
  (testing "every :sh effect inside a retry body carries the current 1-based attempt index"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(4) {
                sh 'a'
                sh 'b'
              }
            } } } }"
           ;; Body fails twice, then succeeds: attempts 1+2 fail at 'a',
           ;; attempt 3 succeeds entirely.
           :fail-attempts {"a" 2})]
      (let [shes (filter #(= :sh (first %)) effects)
            attempt-indices (mapv (fn [e] [(:cmd (second e))
                                           (:attempt (second e))])
                                  shes)]
        ;; Expected sequence:
        ;;   attempt 1: a (fail) → short-circuit  → ["a" 1]
        ;;   attempt 2: a (fail) → short-circuit  → ["a" 2]
        ;;   attempt 3: a (ok), b (ok)            → ["a" 3] ["b" 3]
        (is (= [["a" 1] ["a" 2] ["a" 3] ["b" 3]] attempt-indices)
            "each :sh's :attempt key reflects the retry attempt active when it ran")
        ;; Sanity: this is the contract h-junit consumes — at scan
        ;; time it reads `(:current-retry-attempt ctx)` and threads
        ;; THAT into record-build-results!'s :attempt-number opt.
        ;; If ctx weren't being mutated per attempt, all four :sh
        ;; effects would carry the same :attempt and per-attempt rows
        ;; would collapse, breaking T1.1 flake detection.
        ))))

;; ---------------------------------------------------------------------------
;; Shape 4 — retry(1) boundary: envelope present, never re-runs
;; ---------------------------------------------------------------------------

(deftest retry-count-one-emits-envelope-but-never-re-runs
  (testing "retry(1) — degenerate but legal; emits enter/attempt/leave, body runs exactly once even on failure"
    (let [{:keys [effects result]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(1) {
                sh 'no-retry-here'
              }
            } } } }"
           ;; Body fails — without the envelope we'd never know retry(1)
           ;; was even configured.
           :fail-attempts {"no-retry-here" 999})]
      (let [shes (filter #(= :sh (first %)) effects)
            attempts (filter #(= :retry/attempt (first %)) effects)
            enter (->> effects (filter #(= :retry/enter (first %))) first)
            leave (->> effects (filter #(= :retry/leave (first %))) first)]
        (is (= 1 (count shes))
            "body ran exactly once — retry(1) does not loop")
        (is (= 1 (count attempts))
            "one :retry/attempt logged")
        (is (= 1 (-> enter second :count))
            "configured count = 1 recorded on :enter")
        (is (= 1 (-> leave second :attempts)))
        (is (= :failed (-> leave second :outcome))
            "outcome :failed since the single body invocation failed")
        (is (= :failed (:status result))
            "stage propagates the failure — no silent recovery")))))
