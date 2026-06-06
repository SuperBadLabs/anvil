(ns anvil.compat.jenkins.matrix-in-stage-test
  "AN5-6 — Lockdown for `matrix { … }` block placed INSIDE a declarative
   stage body. Before AN5-6, `translate-stage` only looked for `steps`,
   `agent`, `environment`, and `post` calls; a stage whose body was
   `matrix { axes … stages … }` (no top-level `steps {}`) translated
   to `{:name X :steps []}`, which the honest classifier (AN5-1) read as
   `:unsupported/:body-skipped`. That hit apache-camel and apache-cxf
   in the wild-corpus dirty-dozen hunt.

   AN5-6 detects the matrix child, parses it via
   `matrix-declarative/parse-matrix-call`, and expands cells into
   one materialized stage per axis combination so the dispatcher
   gets real steps to run."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.compat.jenkins.translator :as t]))

;; ---------------------------------------------------------------------------
;; Headline lockdown — wild-corpus shape
;; ---------------------------------------------------------------------------

(def ^:private apache-camel-shape
  "Minimized shape derived from apache-camel's Jenkinsfile: a single
   declarative stage with a matrix block inside the body (no top-level
   steps {} sibling). 2 axes (JDK, OS) → 2×2 = 4 cells expected."
  "pipeline {
     agent any
     stages {
       stage('Build') {
         matrix {
           axes {
             axis { name 'JDK'; values '17', '21' }
             axis { name 'OS';  values 'linux', 'windows' }
           }
           stages {
             stage('Compile') { steps { sh 'mvn compile' } }
             stage('Test')    { steps { sh 'mvn test' } }
           }
         }
       }
     }
   }")

(deftest matrix-inside-stage-expands-to-cells
  (testing "AN5-6 headline: matrix inside a stage body produces N
            materialized cell-stages, NOT a single empty-steps stage."
    (let [ir (t/parse apache-camel-shape)
          stages (:stages ir)]
      ;; Before AN5-6 this was 1 stage with :steps []. After AN5-6 the
      ;; declarative-matrix expander runs at translation time, so we
      ;; see the 4 cartesian-product cells materialized.
      (is (= 4 (count stages))
          "2 JDK values × 2 OS values = 4 cells")
      (testing "each cell carries real steps (not body-skipped)"
        (doseq [s stages]
          (is (pos? (count (:steps s)))
              (str "cell '" (:name s) "' should have inner steps"))))
      (testing "every cell name includes its axis tuple"
        (let [names (mapv :name stages)]
          (is (every? #(str/starts-with? % "Build [") names)
              "cell names should be prefixed with the parent stage")
          (is (every? #(re-find #"JDK=\d+" %) names)
              "cell names should include JDK=…")
          (is (every? #(re-find #"OS=\w+" %) names)
              "cell names should include OS=…")))
      (testing "each cell's environment carries its axis values"
        (doseq [s stages]
          (let [env (:environment s)]
            (is (contains? env "JDK"))
            (is (contains? env "OS"))))))))

(deftest matrix-with-excludes-drops-the-excluded-combo
  (testing "exclude clauses are honored end-to-end"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('Build') {
                     matrix {
                       axes {
                         axis { name 'JDK'; values '17', '21' }
                         axis { name 'OS';  values 'linux', 'windows' }
                       }
                       excludes {
                         exclude {
                           axis { name 'JDK'; values '17' }
                           axis { name 'OS';  values 'windows' }
                         }
                       }
                       stages {
                         stage('Compile') { steps { sh 'mvn compile' } }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)]
      (is (= 3 (count (:stages ir)))
          "4 combos minus 1 exclusion = 3 cells")
      (is (not-any? #(and (re-find #"JDK=17" (:name %))
                          (re-find #"OS=windows" (:name %)))
                    (:stages ir))
          "the excluded JDK=17/OS=windows combo must not appear"))))

(deftest stage-without-matrix-stays-singular
  (testing "lockdown: ordinary (non-matrix) stages still translate to
            one stage each — the matrix wiring is purely additive."
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('Build') { steps { sh 'make' } }
                   stage('Test')  { steps { sh 'make test' } }
                 }
               }"
          ir (t/parse src)]
      (is (= 2 (count (:stages ir))))
      (is (= ["Build" "Test"] (mapv :name (:stages ir))))
      (is (every? #(seq (:steps %)) (:stages ir))))))

(deftest single-axis-matrix-still-expands
  (testing "1-axis matrix collapses to N cells, still expanded"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('Build') {
                     matrix {
                       axes { axis { name 'JDK'; values '17', '21' } }
                       stages {
                         stage('Compile') { steps { sh 'mvn -DjdkHome=$JDK compile' } }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          stages (:stages ir)]
      (is (= 2 (count stages))
          "single-axis 2-value matrix → 2 cells")
      (is (= #{"Build [JDK=17]" "Build [JDK=21]"}
             (set (mapv :name stages)))))))

(deftest cell-steps-concat-inner-stages
  (testing "every inner matrix.stage's steps are concatenated into the
            cell's :steps vector so the dispatcher actually runs them.

            Inner stages: Compile (1 sh) + Test (1 sh) → 2 steps per cell."
    (let [ir (t/parse apache-camel-shape)
          steps-counts (mapv #(count (:steps %)) (:stages ir))]
      (is (every? #(= 2 %) steps-counts)
          (str "expected every cell to carry 2 steps; got " steps-counts)))))
