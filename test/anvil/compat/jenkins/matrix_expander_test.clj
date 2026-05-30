(ns anvil.compat.jenkins.matrix-expander-test
  "Tests for TX11B — matrix expansion of `.combinations` calls in
   scripted Pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]))

;; ---------------------------------------------------------------------------
;; Minimal synthetic Jenkinsfiles
;; ---------------------------------------------------------------------------

(def two-axis-no-filter
  "def axes = [platforms: ['linux', 'mac'], jdks: [21, 25]]
   axes.values().combinations {
     def (platform, jdk) = it
     stage(\"${platform} JDK ${jdk}\") {
       sh 'echo hello'
     }
   }")

(def two-axis-with-filter
  "def axes = [platforms: ['linux', 'windows'], jdks: [21, 25]]
   axes.values().combinations {
     def (platform, jdk) = it
     if (platform == 'windows' && jdk != axes.jdks.last()) {
       return
     }
     stage(\"${platform} JDK ${jdk}\") {
       sh 'echo hi'
     }
   }")

(def three-stages-per-combo
  "def axes = [platforms: ['linux'], jdks: [21, 25]]
   axes.values().combinations {
     def (platform, jdk) = it
     stage(\"${platform}-${jdk}-Build\")   { sh 'build' }
     stage(\"${platform}-${jdk}-Test\")    { sh 'test' }
     stage(\"${platform}-${jdk}-Publish\") { sh 'publish' }
   }")

(def no-matrix
  "stage('Build') { sh 'mvn build' }
   stage('Test')  { sh 'mvn test' }")

(defn- parse-and-expand [src]
  (mx/expand-matrices (t/parse src "test") src))

(defn- stage-names [ir]
  (mapv :name (:stages ir)))

;; ---------------------------------------------------------------------------
;; Core expansion behavior
;; ---------------------------------------------------------------------------

(deftest expands-two-axis-no-filter
  (testing "2 platforms × 2 JDKs = 4 stages, all surviving"
    (let [ir (parse-and-expand two-axis-no-filter)
          names (stage-names ir)]
      (is (= 4 (count names)) (str "expected 4 stages, got " names))
      (is (every? #(re-find #"linux|mac" %) names) (str names))
      (is (every? #(re-find #"21|25" %) names) (str names))
      (is (= #{"linux JDK 21" "linux JDK 25" "mac JDK 21" "mac JDK 25"}
             (set names))))))

(deftest applies-filter-guard
  (testing "filter guard `if (...) return` drops matching combos"
    (let [ir (parse-and-expand two-axis-with-filter)
          names (stage-names ir)]
      (is (= 3 (count names)) (str "expected 3 surviving, got " names))
      (is (= #{"linux JDK 21" "linux JDK 25" "windows JDK 25"}
             (set names)))
      (is (not (contains? (set names) "windows JDK 21"))
          "windows + 21 should be filtered out"))))

(deftest expands-multiple-stages-per-combo
  (testing "N stages × M combos = N*M expanded stages, ordered by combo"
    (let [ir (parse-and-expand three-stages-per-combo)
          names (stage-names ir)]
      (is (= 6 (count names)) (str names))
      ;; All linux-21-* stages come before all linux-25-* stages
      (is (= ["linux-21-Build" "linux-21-Test" "linux-21-Publish"
              "linux-25-Build" "linux-25-Test" "linux-25-Publish"]
             names)))))

(deftest no-matrix-is-noop
  (testing "Jenkinsfiles with no .combinations call pass through unchanged"
    (let [base (t/parse no-matrix "test")
          ir (mx/expand-matrices base no-matrix)]
      (is (= (vec (:stages base)) (vec (:stages ir))))
      ;; No :matrix-expansion entry added when nothing happens
      (is (not-any? :matrix-expansion (or (:options ir) []))))))

;; ---------------------------------------------------------------------------
;; Receipt structure
;; ---------------------------------------------------------------------------

(deftest writes-receipt-into-options
  (testing "expansion writes a :matrix-expansion summary into :options"
    (let [ir (parse-and-expand two-axis-with-filter)
          mx-entry (some :matrix-expansion (or (:options ir) []))]
      (is (some? mx-entry) "matrix-expansion should be in :options")
      (is (= 1 (:matrices-found mx-entry)))
      (is (= 4 (:combinations-tried mx-entry)))
      (is (= 3 (:combinations-surviving mx-entry)))
      (is (= 1 (:stages-removed mx-entry)))
      (is (= 3 (:stages-added mx-entry))))))

(deftest binding-attached-to-expanded-stages
  (testing "each expanded stage has :matrix-binding with axis values"
    (let [ir (parse-and-expand two-axis-no-filter)]
      (doseq [s (:stages ir)]
        (let [b (:matrix-binding s)]
          (is (some? b) (str "stage " (:name s) " missing binding"))
          (is (or (= "linux" (get b "platform"))
                  (= "mac"   (get b "platform"))))
          (is (or (= 21 (get b "jdk"))
                  (= 25 (get b "jdk")))))))))

;; ---------------------------------------------------------------------------
;; The real jenkinsci/jenkins Jenkinsfile receipt
;; ---------------------------------------------------------------------------

(def jenkins-jenkinsfile-path "/home/srikanth/projects/jenkins/Jenkinsfile")

(deftest jenkinsci-jenkins-jenkinsfile-expands
  (testing "the actual jenkinsci/jenkins Jenkinsfile expands to ≥10 stages"
    (when (.exists (io/file jenkins-jenkinsfile-path))
      (let [source (slurp jenkins-jenkinsfile-path)
            ir (mx/expand-matrices (t/parse source "Jenkinsfile") source)
            names (stage-names ir)
            mx-entry (some :matrix-expansion (or (:options ir) []))]
        (is (>= (count names) 10)
            (str "expected ≥10 stages, got " (count names) ": " names))
        (is (contains? (set names) "Record build"))
        ;; The main build matrix's 9 expanded stages should all be there
        (is (contains? (set names) "Linux - JDK 21 - Checkout"))
        (is (contains? (set names) "Linux - JDK 25 - Build / Test"))
        (is (contains? (set names) "Windows - JDK 25 - Publish"))
        ;; The filtered-out combination must NOT be there
        (is (not (contains? (set names) "Windows - JDK 21 - Checkout"))
            "Windows + JDK 21 should be filtered out by the guard")
        ;; Receipt records 3 matrices found in the file
        (is (= 3 (:matrices-found mx-entry)))
        ;; Some axes don't produce stages, but the receipt should still
        ;; record the survivors:
        (is (>= (:combinations-surviving mx-entry) 7))))))
