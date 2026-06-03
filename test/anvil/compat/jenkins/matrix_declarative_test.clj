(ns anvil.compat.jenkins.matrix-declarative-test
  "Tests for declarative-matrix expansion (T4.1, T4.2, T4.7, T4.8)."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.matrix-declarative :as mx]))

;; ---------------------------------------------------------------------------
;; Pure cartesian / exclude logic
;; ---------------------------------------------------------------------------

(deftest cartesian-empty-list-is-single-empty-tuple
  (is (= [[]] (mx/cartesian []))))

(deftest cartesian-cross-product
  (is (= [[\a \x] [\a \y] [\b \x] [\b \y]]
         (mapv vec (mx/cartesian [[\a \b] [\x \y]])))))

;; ---------------------------------------------------------------------------
;; Three matrix samples (T4.8)
;; ---------------------------------------------------------------------------

(deftest sample-2-axis-simple
  (let [ir {:type :jenkins/matrix
            :name "Build & Test"
            :axes [{:name "JDK" :values ["17" "21"]}
                   {:name "OS"  :values ["linux" "windows"]}]
            :excludes []
            :stages [{:name "Build" :steps []}]}
        cells (mx/expand-matrix ir)]
    (is (= 4 (count cells)))
    (is (= #{{"JDK" "17" "OS" "linux"}
             {"JDK" "17" "OS" "windows"}
             {"JDK" "21" "OS" "linux"}
             {"JDK" "21" "OS" "windows"}}
           (set (map :axes cells))))
    (testing "env mirrors axes with string values"
      (is (= {"JDK" "17" "OS" "linux"}
             (:env (first (filter #(= "17" (get-in % [:axes "JDK"]))
                                  (filter #(= "linux" (get-in % [:axes "OS"]))
                                          cells)))))))
    (testing "inner stages copy into each cell"
      (is (every? #(= [{:name "Build" :steps []}] (:stages %)) cells)))))

(deftest sample-3-axis-with-excludes
  (let [ir {:type :jenkins/matrix
            :name "Cross-build"
            :axes [{:name "OS" :values ["linux" "windows" "mac"]}
                   {:name "JDK" :values ["17" "21"]}
                   {:name "MAVEN" :values ["3.8" "3.9"]}]
            :excludes [{"OS" "windows" "JDK" "17"}
                       {"OS" "mac" "MAVEN" "3.8"}]
            :stages []}
        cells (mx/expand-matrix ir)
        total-without-excludes (* 3 2 2)]
    (is (= 12 total-without-excludes))
    ;; Excluded:
    ;;   (windows, 17, 3.8), (windows, 17, 3.9) — 2 cells
    ;;   (mac, 17, 3.8),     (mac, 21, 3.8)     — 2 cells
    ;; Surviving = 12 - 4 = 8
    (is (= 8 (count cells)))
    (testing "all surviving cells have all three axes"
      (is (every? #(= 3 (count (:axes %))) cells)))))

(deftest sample-1-axis-edge-case
  (let [ir {:type :jenkins/matrix
            :name "JDK-only"
            :axes [{:name "JDK" :values ["17"]}]
            :excludes []
            :stages [{:name "Build" :steps []}]}
        cells (mx/expand-matrix ir)]
    (is (= 1 (count cells)))
    (is (= {"JDK" "17"} (:axes (first cells))))))

;; ---------------------------------------------------------------------------
;; Cap enforcement (T4.7)
;; ---------------------------------------------------------------------------

(deftest cap-exceeded-fails-fast
  (with-redefs [mx/max-cells (constantly 6)]
    (let [ir {:type :jenkins/matrix
              :name "Big"
              :axes [{:name "A" :values ["1" "2" "3"]}
                     {:name "B" :values ["x" "y" "z"]}]
              :excludes []
              :stages []}]
      (try
        (mx/expand-matrix ir)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :matrix/cap-exceeded (:type (ex-data e))))
          (is (= 9 (:total (ex-data e))))
          (is (= 6 (:cap (ex-data e)))))))))

(deftest cap-respects-config
  (with-redefs [mx/max-cells (constantly 100)]
    (let [ir {:type :jenkins/matrix
              :name "Fits"
              :axes [{:name "A" :values (mapv str (range 50))}]
              :excludes []
              :stages []}]
      (is (= 50 (count (mx/expand-matrix ir)))))))

;; ---------------------------------------------------------------------------
;; Cell naming for UI / storage
;; ---------------------------------------------------------------------------

(deftest cell-name-stable-ordering
  (is (= "JDK=17, OS=linux"
         (mx/cell-name {"OS" "linux" "JDK" "17"})))
  (is (= "A=1, B=2, C=3"
         (mx/cell-name {"C" "3" "B" "2" "A" "1"}))))

;; ---------------------------------------------------------------------------
;; Parser path — feed it cdata to confirm the IR comes out right
;; ---------------------------------------------------------------------------

(defn- mk-const [v]
  {:type :const :value v})

(defn- mk-call [name args]
  {:type :call :name name :args (vec args)})

(defn- mk-closure [body]
  {:type :closure :body (vec body)})

(deftest parse-matrix-call-from-cdata
  ;; matrix {
  ;;   axes {
  ;;     axis { name 'JDK'; values '17', '21' }
  ;;     axis { name 'OS'; values 'linux', 'windows' }
  ;;   }
  ;;   excludes {
  ;;     exclude { axis { name 'OS'; values 'windows' }
  ;;               axis { name 'JDK'; values '17' } }
  ;;   }
  ;;   stages { stage('Build') { steps {} } }
  ;; }
  (let [jdk-axis (mk-call "axis"
                          [(mk-closure
                            [(mk-call "name" [(mk-const "JDK")])
                             (mk-call "values" [(mk-const "17") (mk-const "21")])])])
        os-axis (mk-call "axis"
                         [(mk-closure
                           [(mk-call "name" [(mk-const "OS")])
                            (mk-call "values" [(mk-const "linux") (mk-const "windows")])])])
        axes-call (mk-call "axes" [(mk-closure [jdk-axis os-axis])])
        excl-os (mk-call "axis"
                         [(mk-closure
                           [(mk-call "name" [(mk-const "OS")])
                            (mk-call "values" [(mk-const "windows")])])])
        excl-jdk (mk-call "axis"
                          [(mk-closure
                            [(mk-call "name" [(mk-const "JDK")])
                             (mk-call "values" [(mk-const "17")])])])
        excl-call (mk-call "exclude" [(mk-closure [excl-os excl-jdk])])
        excludes-call (mk-call "excludes" [(mk-closure [excl-call])])
        stages-call (mk-call "stages" [(mk-closure [])])
        matrix-call (mk-call "matrix" [(mk-closure [axes-call excludes-call stages-call])])
        ;; The inner-stage-parser is a no-op for this test (returns empty)
        ir (mx/parse-matrix-call matrix-call "Build & Test" (constantly []))]
    (is (= "Build & Test" (:name ir)))
    (is (= [{:name "JDK" :values ["17" "21"]}
            {:name "OS"  :values ["linux" "windows"]}]
           (:axes ir)))
    (is (= [{"OS" "windows" "JDK" "17"}] (:excludes ir)))
    (testing "expanding the parsed IR drops the excluded combination"
      (let [cells (mx/expand-matrix ir)]
        (is (= 3 (count cells)))
        (is (not-any? #(= {"OS" "windows" "JDK" "17"} (:axes %)) cells))))))

(deftest matrix-child-call-predicate
  (is (mx/matrix-child-call? {:type :call :name "matrix" :args []}))
  (is (not (mx/matrix-child-call? {:type :call :name "stages" :args []})))
  (is (not (mx/matrix-child-call? {:type :const :value "matrix"}))))
