(ns anvil.agents.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.agents.registry :as r]))

(use-fixtures :each (fn [f] (r/reset-cache!) (f) (r/reset-cache!)))

(deftest known-labels-include-maven-21
  (testing "default agents.edn ships with the labels the Jenkins build expects"
    (let [labels (r/known-labels)]
      (is (some #{"maven-21"} labels))
      (is (some #{"maven-25"} labels))
      (is (some #{"linux"} labels))
      (is (some #{"docker-highmem"} labels)))))

(deftest resolves-known-label
  (testing "a labeled lookup returns the executor config + label"
    (let [r (r/resolve-label "maven-21")]
      (is (= :local (:executor r)))
      (is (= "maven-21" (:label r)))
      (is (contains? (:env r) "PATH") "PATH should be exported for the Maven label")
      (is (contains? (:env r) "JAVA_HOME"))
      (is (not (:degraded? r))))))

(deftest unknown-label-degrades-to-default
  (testing "unknown label falls back to default with a degrade marker"
    (let [r (r/resolve-label "completely-made-up-label")]
      (is (= :local (:executor r)))
      (is (= "completely-made-up-label" (:fallback-from r)))
      (is (:degraded? r))
      (is (some? (:degrade-reason r))))))

(deftest empty-label-silently-uses-default
  (testing "blank or nil labels resolve to default with no degrade warning"
    (let [r-nil (r/resolve-label nil)
          r-blank (r/resolve-label "")
          r-spaces (r/resolve-label "   ")]
      (is (= :local (:executor r-nil)))
      (is (not (:fallback-from r-nil)))
      (is (not (:fallback-from r-blank)))
      (is (not (:fallback-from r-spaces))))))

(deftest windows-label-is-degraded-on-linux
  (testing "the windows labels are marked degraded? in the default config"
    (let [r (r/resolve-label "maven-21-windows")]
      (is (:degraded? r))
      (is (re-find #"(?i)windows" (:degrade-reason r))))))

(deftest env-merges-default-into-label-specific
  (testing "default env is merged with the label's env (label wins)"
    ;; The default config doesn't set PATH or JAVA_HOME globally; the
    ;; maven-21 label sets both. So resolving should produce label
    ;; values for PATH and JAVA_HOME and no extras from default.
    (let [r (r/resolve-label "maven-21")]
      (is (re-find #"apache-maven" (get-in r [:env "PATH"])))
      (is (re-find #"temurin" (get-in r [:env "JAVA_HOME"]))))))
