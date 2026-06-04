(ns anvil.compat.jenkins.classification-test
  "Acceptance tests for AN4-1: the EX2 classifier wired into anvil's
   runner. Locks down the seven wild-corpus silent-skip shapes that
   anvil v0.3 reported as :success and now reclassify honestly."
  (:require [anvil.compat.jenkins.classification :as c]
            [clojure.test :refer [deftest is testing]]))

(defn- sh-eff [exit]
  [:sh {:cmd "echo x" :cwd "/tmp" :exit exit
        :streamed? false :stdout-bytes 0 :stderr-bytes 0}])

;; ---------------------------------------------------------------------------
;; The headline regression: empty-walk → :neutral, not :success
;; ---------------------------------------------------------------------------

(deftest empty-walk-reclassifies-as-neutral
  (let [c1 (c/classify-build {:status :ok} [] {})]
    (is (= :neutral (:result c1))
        "an IR walk with zero effects must NOT classify as :success")
    (is (= :no-effects-recorded (:rule c1)))))

(deftest pipeline-with-only-stdout-lines-still-neutral
  ;; stdout/stderr informational effects do NOT count as work — they're
  ;; the lines emitted by an `echo` step that resolved to a no-op.
  (let [c1 (c/classify-build {:status :ok}
                             [[:stdout "hello"]
                              [:echo "world"]] {})]
    (is (= :neutral (:result c1)))))

;; ---------------------------------------------------------------------------
;; Agent-degraded → :unsupported
;; ---------------------------------------------------------------------------

(deftest docker-agent-degraded-classifies-unsupported
  (let [eff [:agent/degraded
             {:requested-agent {:type "docker" :image "x"}
              :active-agent :any}]
        c1 (c/classify-build {:status :ok} [eff] {})]
    (is (= :unsupported (:result c1)))
    (is (= :agent-unhonored (:rule c1)))))

(deftest kubernetes-agent-degraded-classifies-unsupported
  (let [eff [:agent/degraded
             {:requested-agent {:type "kubernetes"}
              :active-agent :any}]]
    (is (= :unsupported (:result (c/classify-build {:status :ok} [eff] {}))))))

(deftest dockerfile-agent-degraded-classifies-unsupported
  (let [eff [:agent/degraded
             {:requested-agent {:type "dockerfile"}
              :active-agent :any}]]
    (is (= :unsupported (:result (c/classify-build {:status :ok} [eff] {}))))))

(deftest label-agent-degraded-stays-success-when-shell-ran
  ;; label-agent degradation is benign (we fell back to controller);
  ;; what matters is whether real work ran.
  (let [effs [[:agent/degraded
               {:requested-agent {:type "label" :label "linux"}
                :active-agent :any}]
              (sh-eff 0)]]
    (is (= :success (:result (c/classify-build {:status :ok} effs {}))))))

;; ---------------------------------------------------------------------------
;; Unknown step → :unsupported
;; ---------------------------------------------------------------------------

(deftest unknown-step-classifies-unsupported
  (let [c1 (c/classify-build {:status :ok}
                             [[:unknown {:name "recordIssues" :args nil}]]
                             {})]
    (is (= :unsupported (:result c1)))
    (is (= :unsupported-construct (:rule c1)))))

;; ---------------------------------------------------------------------------
;; Non-zero sh exit → :failure
;; ---------------------------------------------------------------------------

(deftest nonzero-sh-exit-classifies-failure
  (let [c1 (c/classify-build {:status :failed
                              :error :sh-non-zero
                              :exit 2}
                             [(sh-eff 0) (sh-eff 2)]
                             {})]
    (is (= :failure (:result c1)))
    (is (= :step-nonzero-exit (:rule c1)))))

;; ---------------------------------------------------------------------------
;; Run-pipeline :failed with no exit (script-block-failed, etc.) still :failure
;; ---------------------------------------------------------------------------

(deftest script-block-failure-without-sh-effect-classifies-failure
  ;; dispatcher returned :failed but no :sh effect captured a non-zero
  ;; exit (e.g. a script {} block threw a Groovy exception). We must
  ;; still classify as :failure, not pretend success.
  (let [c1 (c/classify-build
            {:status :failed :error :script-block-failed
             :message "groovy.lang.MissingPropertyException"}
            []  ; no effects logged
            {})]
    (is (= :failure (:result c1)))
    (is (= :step-nonzero-exit (:rule c1)))))

;; ---------------------------------------------------------------------------
;; Cancelled
;; ---------------------------------------------------------------------------

(deftest cancellation-classifies-aborted
  (let [c1 (c/classify-build {:status :ok} [(sh-eff 0)] {:cancelled? true})]
    (is (= :aborted (:result c1)))
    (is (= :aborted-by-signal (:rule c1)))))

;; ---------------------------------------------------------------------------
;; Real success: shell ran + artifacts recorded
;; ---------------------------------------------------------------------------

(deftest real-build-classifies-success
  (let [effs [(sh-eff 0) (sh-eff 0)
              [:archive {:artifacts "target/*.jar"
                         :files-archived 3}]
              [:junit {:results "**/TEST-*.xml" :files-found 5}]]
        c1 (c/classify-build {:status :ok} effs {})]
    (is (= :success (:result c1)))
    (is (= :default (:rule c1)))))

(deftest real-build-with-just-artifact-archive-no-shell-still-success
  ;; A pipeline that only ran archiveArtifacts (no shell) is still real
  ;; work — the effect counts.
  (let [effs [[:archive {:artifacts "*.zip"}]]
        c1 (c/classify-build {:status :ok} effs {})]
    (is (= :success (:result c1)))))

;; ---------------------------------------------------------------------------
;; Wild-corpus regression battery — the 7 false SUCCESS shapes
;; ---------------------------------------------------------------------------

(deftest wild-corpus-seven-vacuous-successes-now-honest
  ;; These are the SHAPES of the seven entries that anvil v0.3 reported
  ;; as :success despite producing no real artifacts. Locking down here
  ;; prevents regression in the runner integration.
  (let [shapes
        [{:effs []
          :expected :neutral
          :why "empty walk (pipeline { steps { } })"}
         {:effs [[:agent/degraded
                  {:requested-agent {:type "docker" :image "maven:3.9"}}]]
          :expected :unsupported
          :why "agent { docker } skipped, body not executed"}
         {:effs [[:agent/degraded
                  {:requested-agent {:type "kubernetes"}}]]
          :expected :unsupported
          :why "agent { kubernetes } skipped"}
         {:effs [[:agent/degraded
                  {:requested-agent {:type "dockerfile"}}]]
          :expected :unsupported
          :why "agent { dockerfile } skipped"}
         {:effs [[:unknown {:name "recordIssues"}]]
          :expected :unsupported
          :why "unknown plugin step"}
         {:effs [[:unknown {:name "withSonarQubeEnv"}]]
          :expected :unsupported
          :why "unknown plugin wrapper"}
         {:effs [[:echo "version"]
                 [:stdout "1.0-SNAPSHOT"]]
          :expected :neutral
          :why "env-only / echo-only pipeline"}]]
    (doseq [{:keys [effs expected why]} shapes]
      (is (= expected (:result (c/classify-build {:status :ok} effs {})))
          (str "shape: " why)))))

;; ---------------------------------------------------------------------------
;; effects->observation: the underlying fold
;; ---------------------------------------------------------------------------

(deftest effects-fold-counts-shell-steps
  (let [obs (c/effects->observation [(sh-eff 0) (sh-eff 0) (sh-eff 1)])]
    (is (= 3 (:shell-steps-run obs)))
    (is (= [1] (:nonzero-exits obs)))))

(deftest effects-fold-records-artifact-effects
  (let [obs (c/effects->observation
             [[:archive {}]
              [:junit {}]
              [:stash {}]])]
    (is (= 3 (count (:recorded-effects obs))))))

(deftest effects-fold-ignores-informational-effects
  (let [obs (c/effects->observation
             [[:stdout "x"] [:stderr "y"] [:echo "z"]])]
    (is (zero? (:shell-steps-run obs)))
    (is (empty? (:recorded-effects obs)))))
