(ns anvil.compat.jenkins.streaming-log-test
  "TX9 phase 4 — streaming console-log. When ctx :log-file is set, the
   subprocess's stdout + stderr stream directly into that file instead
   of being buffered into memory and emitted as :stdout / :stderr
   effect tuples.

   Why this matters: a long-running build that emits hundreds of MB of
   output would otherwise sit in memory until build end. Streaming
   means the buffer is the disk file, and the in-memory side-effects
   vector only carries metadata."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- mktmp-file! []
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "anvil-log-" (System/nanoTime) ".log"))]
    (.createNewFile f)
    f))

(deftest streaming-redirects-to-file-test
  (testing "with ctx :log-file set, sh stdout lands in the file and is
            NOT emitted as :stdout effect tuples (kept out of memory)"
    (let [log-file (mktmp-file!)
          d (ad/make {:execute? true})
          step {:type :jenkins/sh :script "echo line-1; echo line-2; echo line-3"}
          result (d/dispatch d step {:log-file log-file})]
      (is (= :ok (:status result)))
      (let [contents (slurp log-file)]
        (is (str/includes? contents "line-1"))
        (is (str/includes? contents "line-2"))
        (is (str/includes? contents "line-3")))
      (let [evs @(:effects d)]
        (is (not (some #(= :stdout (first %)) evs))
            "no per-line :stdout tuples in streaming mode")
        (let [sh-ev (first (filter #(= :sh (first %)) evs))]
          (is (true? (-> sh-ev second :streamed?))
              ":sh tuple records streamed? true")))
      (.delete log-file))))

(deftest streaming-merges-stderr-into-stdout-test
  (testing "stderr is redirected to stdout via 2>&1 — both land in the file"
    (let [log-file (mktmp-file!)
          d (ad/make {:execute? true})
          step {:type :jenkins/sh
                :script "echo to-out; echo to-err >&2; echo back-to-out"}
          _ (d/dispatch d step {:log-file log-file})
          contents (slurp log-file)]
      (is (str/includes? contents "to-out"))
      (is (str/includes? contents "to-err"))
      (is (str/includes? contents "back-to-out"))
      ;; Effects are clean of stdout/stderr tuples.
      (is (not (some #(#{:stdout :stderr} (first %)) @(:effects d))))
      (.delete log-file))))

(deftest buffered-mode-still-works-test
  (testing "without ctx :log-file, behavior matches TX9 phase 1
            — per-line :stdout tuples are still emitted"
    (let [d (ad/make {:execute? true})
          step {:type :jenkins/sh :script "echo classic-mode"}
          _ (d/dispatch d step {})
          evs @(:effects d)
          stdout-lines (->> evs (filter #(= :stdout (first %))) (mapv second))
          sh-ev (first (filter #(= :sh (first %)) evs))]
      (is (= ["classic-mode"] stdout-lines))
      (is (false? (-> sh-ev second :streamed?))))))

(deftest streaming-multi-step-pipeline-test
  (testing "across multiple sh steps in a pipeline, all output accumulates
            in the same log file (because :log-file is in ctx and ctx
            is threaded through the orchestrator)"
    (let [log-file (mktmp-file!)
          d (ad/make {:execute? true})
          pipeline {:stages [{:name "Build"
                              :steps [{:type :jenkins/sh :script "echo step-1-out"}
                                      {:type :jenkins/sh :script "echo step-2-out"}]}
                             {:name "Test"
                              :steps [{:type :jenkins/sh :script "echo step-3-out"}]}]}
          _ (d/run-pipeline pipeline d {:log-file log-file})
          contents (slurp log-file)]
      (is (str/includes? contents "step-1-out"))
      (is (str/includes? contents "step-2-out"))
      (is (str/includes? contents "step-3-out"))
      (.delete log-file))))

(deftest streaming-nonzero-exit-still-detected-test
  (testing "a failing sh in streaming mode still propagates :failed
            and the file captures whatever was emitted before the failure"
    (let [log-file (mktmp-file!)
          d (ad/make {:execute? true})
          step {:type :jenkins/sh :script "echo before-fail; exit 5"}
          result (d/dispatch d step {:log-file log-file})]
      (is (= :failed (:status result)))
      (is (= 5 (:exit result)))
      (is (str/includes? (slurp log-file) "before-fail"))
      (.delete log-file))))