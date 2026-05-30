(ns anvil.compat.jenkins.libraries-test
  "Tests for the shared library loader."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.libraries :as lib]
            [anvil.compat.jenkins.plugins :as plugins]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.translator :as t]))

(def ^:private sample-base "test/resources/sample-libraries")

(use-fixtures :each (fn [f] (plugins/clear-registry!) (f) (plugins/clear-registry!)))

;; ---------------------------------------------------------------------------
;; Coord parsing
;; ---------------------------------------------------------------------------

(deftest parse-coord-test
  (testing "library coordinates parse with sensible defaults"
    (is (= {:name "foo" :ref "main"}   (lib/parse-coord "foo")))
    (is (= {:name "foo" :ref "v1.2.3"} (lib/parse-coord "foo@v1.2.3")))
    (is (= {:name "org/lib" :ref "sha-abc"} (lib/parse-coord "org/lib@sha-abc")))
    (is (nil? (lib/parse-coord nil)))))

;; ---------------------------------------------------------------------------
;; Vars discovery
;; ---------------------------------------------------------------------------

(deftest list-vars-finds-groovy-files-test
  (testing "list-vars finds all .groovy files in a resolved library's vars/"
    (let [resolved (lib/resolve-source-dir sample-base "sample-lib" "main")
          vars (lib/list-vars resolved)
          step-names (set (map :step-name vars))]
      (is (= 3 (count vars)))
      (is (= #{"sayHello" "runStandardChecks" "deployTo"} step-names))
      (is (every? :source vars)
          "every entry has its source slurped"))))

(deftest list-vars-missing-dir-test
  (testing "list-vars returns [] when vars/ doesn't exist"
    (let [non-existent (io/file "test/resources/sample-libraries/missing-lib/main")]
      (is (= [] (lib/list-vars non-existent))))))

;; ---------------------------------------------------------------------------
;; load-library!
;; ---------------------------------------------------------------------------

(deftest load-library-registers-each-var-test
  (testing "load-library! registers each vars/*.groovy as a plugin adapter"
    (let [result (lib/load-library! sample-base "sample-lib" "main")]
      (is (= :ok (:status result)))
      (is (= #{"sayHello" "runStandardChecks" "deployTo"}
             (set (:registered result))))
      ;; Each step name is now in the plugin registry
      (is (contains? (plugins/registered-step-names) "sayHello"))
      (is (contains? (plugins/registered-step-names) "deployTo")))))

(deftest load-library-missing-returns-error-test
  (testing "loading a non-existent library returns :error with a clear reason"
    (let [result (lib/load-library! sample-base "no-such-lib" "main")]
      (is (= :error (:status result)))
      (is (= :library-not-found (:reason result))))))

;; ---------------------------------------------------------------------------
;; ->groovy-value
;; ---------------------------------------------------------------------------

(deftest groovy-value-conversion-test
  (testing "Clojure values convert to Groovy-friendly equivalents"
    (is (= "foo" (lib/->groovy-value "foo")))
    (is (= 42    (lib/->groovy-value 42)))
    (is (true?   (lib/->groovy-value true)))
    (let [m (lib/->groovy-value {:env "prod" :region "us-east-1"})]
      (is (instance? java.util.LinkedHashMap m))
      (is (= "prod" (.get m "env"))))
    (let [a (lib/->groovy-value ["x" "y"])]
      (is (instance? java.util.ArrayList a))
      (is (= 2 (.size a))))))

;; ---------------------------------------------------------------------------
;; End-to-end: library step runs inside a pipeline
;; ---------------------------------------------------------------------------

(deftest library-step-no-args-runs-test
  (testing "a library step that takes no args runs via the dispatcher"
    (lib/load-library! sample-base "sample-lib" "main")
    (let [d (ad/make)
          ir (t/parse "pipeline { agent any; stages { stage('S') { steps {
                          runStandardChecks()
                        } } } }")
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages ir))}
          ;; Thread dispatcher in via ctx so library code can find it.
          ctx {:dispatcher d}
          _ (d/run-pipeline flat d ctx)
          evs @(:effects d)
          types (mapv first evs)]
      ;; The library's `def call()` runs `sh 'lint'`, `sh 'test'`,
      ;; `echo 'standard checks complete'` — should land as 2 sh + 1 echo.
      (is (= [:sh :sh :echo] types))
      (is (= "lint" (-> evs (nth 0) second :cmd)))
      (is (= "test" (-> evs (nth 1) second :cmd)))
      (is (= "standard checks complete" (second (nth evs 2)))))))

(deftest library-step-with-string-arg-test
  (testing "a library step with a single string arg receives it correctly"
    (lib/load-library! sample-base "sample-lib" "main")
    (let [d (ad/make)
          ir (t/parse "pipeline { agent any; stages { stage('S') { steps {
                          sayHello 'anvil'
                        } } } }")
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages ir))}
          _ (d/run-pipeline flat d {:dispatcher d})
          evs @(:effects d)
          echo-events (filter #(= :echo (first %)) evs)]
      (is (= 1 (count echo-events)))
      (is (= "hello, anvil" (second (first echo-events)))))))

(deftest library-step-with-map-arg-test
  (testing "a library step with a Map arg sees named-arg keys correctly"
    (lib/load-library! sample-base "sample-lib" "main")
    (let [d (ad/make)
          ir (t/parse "pipeline { agent any; stages { stage('S') { steps {
                          deployTo env: 'prod', region: 'us-west-2'
                        } } } }")
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages ir))}
          _ (d/run-pipeline flat d {:dispatcher d})
          evs @(:effects d)
          sh-events (filter #(= :sh (first %)) evs)]
      (is (= 1 (count sh-events)))
      (is (= "deploy --env=prod --region=us-west-2"
             (-> sh-events first second :cmd))))))

;; ---------------------------------------------------------------------------
;; @Library annotation → load-libraries-from-ir!
;; ---------------------------------------------------------------------------

(deftest load-libraries-from-ir-test
  (testing "load-libraries-from-ir! walks a pipeline IR's :libraries vector"
    (let [pipeline {:libraries [{:name "sample-lib" :version "main"}]}
          results (lib/load-libraries-from-ir! pipeline sample-base)]
      (is (= 1 (count results)))
      (is (= :ok (-> results first :status)))
      (is (contains? (plugins/registered-step-names) "sayHello")))))
