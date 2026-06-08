(ns anvil.parameters-defaults-test
  "AN8-2 — unit tests for the operator-side per-job parameter defaults
   loader and the three-layer resolution merge.

   Translator-default extraction lives in `ir/default-parameters`
   (covered in translator_an8_test). This namespace pins the
   resolve-build-parameters semantics and the anvil.edn cache."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.parameters-defaults :as pd]))

(use-fixtures :each
  (fn [f]
    (pd/clear-cache!)
    (try (f) (finally (pd/clear-cache!)))))

(deftest resolve-empty-inputs
  (testing "all-empty inputs → empty map"
    (is (= {} (pd/resolve-build-parameters {})))))

(deftest resolve-translator-only
  (testing "only translator defaults → those values"
    (is (= {"X" "a" "Y" "b"}
           (pd/resolve-build-parameters
            {:translator-defaults {"X" "a" "Y" "b"}})))))

(deftest resolve-operator-overrides-translator
  (testing "operator-side defaults beat translator defaults on key conflict"
    (is (= {"X" "operator-a"}
           (pd/resolve-build-parameters
            {:translator-defaults {"X" "translator-a"}
             :operator-defaults   {"X" "operator-a"}})))))

(deftest resolve-runtime-overrides-everything
  (testing "runtime trigger payload beats both translator + operator defaults"
    (is (= {"X" "runtime-X" "Y" "operator-Y" "Z" "translator-Z"}
           (pd/resolve-build-parameters
            {:translator-defaults {"X" "translator-X"
                                   "Y" "translator-Y"
                                   "Z" "translator-Z"}
             :operator-defaults   {"X" "operator-X"
                                   "Y" "operator-Y"}
             :runtime-params      {"X" "runtime-X"}})))))

(deftest resolve-merges-disjoint-keys
  (testing "non-overlapping keys all flow through"
    (is (= {"A" "1" "B" "2" "C" "3"}
           (pd/resolve-build-parameters
            {:translator-defaults {"A" "1"}
             :operator-defaults   {"B" "2"}
             :runtime-params      {"C" "3"}})))))

(deftest resolve-tolerates-nil-inputs
  (testing "nil maps coerce to {} — runner safely passes nil for missing layers"
    (is (= {"X" "runtime"}
           (pd/resolve-build-parameters
            {:translator-defaults nil
             :operator-defaults   nil
             :runtime-params      {"X" "runtime"}})))))

(deftest for-job-reads-anvil-edn-config
  (testing "for-job looks up :anvil.parameters/defaults <job-name>"
    (with-redefs [pd/all (constantly {"my-job"   {"NODE" "ubuntu" "JDK" "17"}
                                      "other"    {"FOO"  "bar"}})]
      (is (= {"NODE" "ubuntu" "JDK" "17"} (pd/for-job "my-job")))
      (is (= {"FOO" "bar"} (pd/for-job "other")))
      (is (nil? (pd/for-job "unknown")))
      (is (nil? (pd/for-job nil))))))
