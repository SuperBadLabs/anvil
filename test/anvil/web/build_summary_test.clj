(ns anvil.web.build-summary-test
  "Tests for the build-summary helpers (TU3.2 + TU3.4 + TU3.5)."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.web.build-summary :as s]))

(deftest steps-by-stage-groups-correctly
  (let [effects [[:agent/stage-enter {:stage "Build"}]
                 [:sh {:cmd "make" :exit 0}]
                 [:sh {:cmd "make test" :exit 0}]
                 [:agent/stage-leave {:stage "Build"}]
                 [:agent/stage-enter {:stage "Deploy"}]
                 [:sh {:cmd "scp .." :exit 1}]
                 [:agent/stage-leave {:stage "Deploy"}]]
        stages (s/steps-by-stage effects)]
    (is (= 2 (count stages)))
    (is (= "Build" (-> stages first :stage)))
    (is (= 2 (-> stages first :steps count)))
    (is (false? (-> stages first :failed?)))
    (is (= "Deploy" (-> stages second :stage)))
    (is (true? (-> stages second :failed?))
        "non-zero exit on any step marks the stage failed")))

(deftest steps-by-stage-handles-orphan-shells
  ;; A :sh outside any stage block is silently dropped — only
  ;; stage-grouped commands appear. Honest about the limit; we don't
  ;; conjure a synthetic <unnamed stage> at this layer.
  (let [effects [[:sh {:cmd "x" :exit 0}]
                 [:agent/stage-enter {:stage "go"}]
                 [:sh {:cmd "y" :exit 0}]
                 [:agent/stage-leave {:stage "go"}]]
        stages (s/steps-by-stage effects)]
    (is (= 1 (count stages)))
    (is (= "go" (-> stages first :stage)))
    (is (= 1 (-> stages first :steps count)))))

(deftest step-summary-aggregates
  (let [effects [[:agent/stage-enter {:stage "A"}]
                 [:sh {:cmd "x" :exit 0}]
                 [:agent/stage-leave {:stage "A"}]
                 [:agent/stage-enter {:stage "B"}]
                 [:sh {:cmd "y" :exit 1}]
                 [:sh {:cmd "z" :exit 2}]
                 [:agent/stage-leave {:stage "B"}]]
        agg (s/step-summary effects)]
    (is (= {:stage-count 2 :step-count 3 :failed-step-count 2} agg))))

(deftest param-diff-shapes
  (let [d (s/param-diff {"a" 1 "b" 2} {"a" 1 "c" 3})]
    (is (= {"c" 3} (:added d)))
    (is (= {"b" 2} (:removed d)))
    (is (= {} (:changed d))))
  (let [d (s/param-diff {"a" 1 "b" 2} {"a" 1 "b" 99})]
    (is (= {} (:added d)))
    (is (= {} (:removed d)))
    (is (= {"b" [2 99]} (:changed d))))
  (let [d (s/param-diff nil {"a" 1})]
    (is (= {"a" 1} (:added d)))))

(deftest guess-content-type-covers-CI-output-types
  (is (= "application/java-archive" (s/guess-content-type "x.jar")))
  (is (= "application/zip" (s/guess-content-type "x.zip")))
  (is (= "text/plain; charset=utf-8" (s/guess-content-type "x.txt")))
  (is (= "text/plain; charset=utf-8" (s/guess-content-type "x.log")))
  (is (= "application/xml" (s/guess-content-type "junit.xml")))
  (is (= "application/json" (s/guess-content-type "report.json")))
  (is (= "image/svg+xml" (s/guess-content-type "chart.svg")))
  (is (= "application/octet-stream" (s/guess-content-type "weird.exotic"))))

(deftest guess-content-type-case-insensitive
  (is (= "application/java-archive" (s/guess-content-type "MYAPP.JAR")))
  (is (= "image/png" (s/guess-content-type "Screenshot.PNG"))))
