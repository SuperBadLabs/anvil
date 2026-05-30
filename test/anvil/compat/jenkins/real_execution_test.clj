(ns anvil.compat.jenkins.real-execution-test
  "TX9 — verifies the dispatcher actually subprocess-executes when
   constructed with `:execute? true`. Up to TX8 the dispatcher
   recorded effects only; this turns recording into real work.

   Tests don't mock the shell — they invoke `echo`, `mkdir`, `printf`,
   etc. directly. If your environment doesn't have a POSIX-ish shell,
   skip this namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- make-execute-dispatcher []
  (ad/make {:execute? true}))

(defn- mktmp! []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "anvil-test-" (System/nanoTime)))]
    (.mkdirs d)
    d))

;; ---------------------------------------------------------------------------
;; sh execution
;; ---------------------------------------------------------------------------

(deftest sh-echo-captures-stdout-test
  (testing "sh 'echo hello' actually runs and stdout shows up in effects"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh :script "echo hello world"}
          result (d/dispatch d step {})
          evs @(:effects d)]
      (is (= :ok (:status result)))
      (is (some #(= [:stdout "hello world"] %) evs))
      (let [sh-ev (first (filter #(= :sh (first %)) evs))]
        (is (= 0 (-> sh-ev second :exit)))))))

(deftest sh-nonzero-exit-fails-test
  (testing "a command that exits non-zero produces :failed status"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh :script "exit 42"}
          result (d/dispatch d step {})]
      (is (= :failed (:status result)))
      (is (= :sh-non-zero (:error result)))
      (is (= 42 (:exit result))))))

(deftest sh-return-stdout-captures-string-test
  (testing "sh(script:..., returnStdout: true) returns the trimmed stdout"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh
                :script "printf 'v1.2.3'"
                :return-stdout? true}
          result (d/dispatch d step {})]
      (is (= :ok (:status result)))
      (is (= "v1.2.3" (:stdout result))))))

(deftest sh-return-status-returns-exit-test
  (testing "sh(script:..., returnStatus: true) returns the exit code as data
            rather than failing the step"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh
                :script "exit 7"
                :return-status? true}
          result (d/dispatch d step {})]
      (is (= :ok (:status result)) "step itself succeeded; failure is in the int")
      (is (= 7 (:status-code result))))))

(deftest cwd-flows-into-subprocess-test
  (testing "the dispatcher's ctx :cwd becomes the subprocess working directory"
    (let [tmpdir (mktmp!)
          d (make-execute-dispatcher)
          step {:type :jenkins/sh :script "pwd"}
          result (d/dispatch d step {:cwd (.getAbsolutePath tmpdir)})
          evs @(:effects d)
          stdout-line (->> evs (filter #(= :stdout (first %))) first second)]
      (is (= :ok (:status result)))
      ;; macOS may report /private/tmp/... when /tmp/... is asked for.
      (is (str/includes? stdout-line (.getName tmpdir))))))

(deftest env-vars-flow-into-subprocess-test
  (testing "ctx :env entries are exported into the subprocess environment"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh :script "echo $MY_VAR"}
          result (d/dispatch d step {:env {"MY_VAR" "anvil-rules"}})
          evs @(:effects d)
          stdout (->> evs (filter #(= :stdout (first %))) first second)]
      (is (= :ok (:status result)))
      (is (= "anvil-rules" stdout)))))

(deftest stderr-captured-separately-test
  (testing "stderr lands as :stderr events, not :stdout"
    (let [d (make-execute-dispatcher)
          step {:type :jenkins/sh
                :script "echo to-out; echo to-err >&2"}
          _ (d/dispatch d step {})
          evs @(:effects d)
          outs (filter #(= :stdout (first %)) evs)
          errs (filter #(= :stderr (first %)) evs)]
      (is (= ["to-out"] (mapv second outs)))
      (is (= ["to-err"] (mapv second errs))))))

(deftest multi-step-pipeline-real-execution-test
  (testing "an end-to-end pipeline runs each step as a real subprocess"
    (let [d (make-execute-dispatcher)
          pipeline {:stages [{:name "Build"
                              :steps [{:type :jenkins/sh :script "echo step-1"}
                                      {:type :jenkins/sh :script "echo step-2"}]}
                             {:name "Test"
                              :steps [{:type :jenkins/sh :script "echo step-3"}]}]}
          result (d/run-pipeline pipeline d {:cwd (System/getProperty "java.io.tmpdir")})
          evs @(:effects d)
          stdout-lines (->> evs (filter #(= :stdout (first %))) (mapv second))]
      (is (= :ok (:status result)))
      (is (= ["step-1" "step-2" "step-3"] stdout-lines)))))

(deftest pipeline-aborts-on-failed-step-test
  (testing "a step exiting non-zero aborts the stage; subsequent stages skip"
    (let [d (make-execute-dispatcher)
          pipeline {:stages [{:name "Build"
                              :steps [{:type :jenkins/sh :script "echo ok"}
                                      {:type :jenkins/sh :script "exit 1"}
                                      {:type :jenkins/sh :script "echo never"}]}
                             {:name "Publish"
                              :steps [{:type :jenkins/sh :script "echo also-never"}]}]}
          result (d/run-pipeline pipeline d {})
          evs @(:effects d)
          stdout-lines (->> evs (filter #(= :stdout (first %))) (mapv second))]
      (is (= :failed (:status result)))
      (is (= ["ok"] stdout-lines)
          "only 'ok' ran; 'never' and 'also-never' were skipped"))))

(deftest record-only-mode-still-default-test
  (testing "a dispatcher made WITHOUT :execute? true records — no subprocess
            (sanity check that the default is preserved)"
    (let [d (ad/make)                  ; default, :execute? = false
          step {:type :jenkins/sh :script "exit 99"}
          result (d/dispatch d step {})]
      ;; In record-only mode, exit 99 isn't actually run, so :failed
      ;; isn't propagated.
      (is (= :ok (:status result))
          "record-only mode succeeds regardless of cmd"))))