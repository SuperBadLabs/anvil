(ns anvil.web.params-parse-test
  "Tests for the Jenkinsfile parameters{} regex extractor (TU4.1)."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.web.params-parse :as p]))

(deftest empty-source-yields-empty
  (is (= [] (p/extract nil)))
  (is (= [] (p/extract "")))
  (is (= [] (p/extract "pipeline { agent any }"))))

(deftest string-param-extracted
  (let [src "pipeline { agent any; parameters {
               string(name: 'BRANCH', defaultValue: 'main', description: 'git branch')
             }}"
        out (p/extract src)]
    (is (= 1 (count out)))
    (let [p (first out)]
      (is (= :string (:kind p)))
      (is (= "BRANCH" (:name p)))
      (is (= "main" (:default p)))
      (is (= "git branch" (:description p))))))

(deftest boolean-param-extracted
  (let [out (p/extract "parameters {
                          booleanParam(name: 'CLEAN', defaultValue: true, description: 'wipe ws')
                        }")]
    (is (= 1 (count out)))
    (let [p (first out)]
      (is (= :boolean (:kind p)))
      (is (= "CLEAN" (:name p)))
      (is (true? (:default p))))))

(deftest choice-param-extracted-list-syntax
  (let [out (p/extract "parameters {
                          choice(name: 'ENV', choices: ['dev', 'stg', 'prod'], description: 'target')
                        }")]
    (is (= 1 (count out)))
    (let [p (first out)]
      (is (= :choice (:kind p)))
      (is (= ["dev" "stg" "prod"] (:choices p)))
      (is (= "dev" (:default p)) "default is first choice"))))

(deftest choice-param-extracted-newline-syntax
  (let [out (p/extract "parameters {
                          choice(name: 'TIER', choices: 'free\\npro\\nteam')
                        }")]
    (is (= ["free" "pro" "team"] (-> out first :choices)))))

(deftest password-param-extracted
  (let [out (p/extract "parameters {
                          password(name: 'TOKEN', defaultValue: '', description: 'auth')
                        }")]
    (is (= :password (-> out first :kind)))
    (is (= "TOKEN" (-> out first :name)))))

(deftest file-param-extracted
  (let [out (p/extract "parameters {
                          file(name: 'UPLOAD', description: 'tar.gz to install')
                        }")]
    (is (= :file (-> out first :kind)))
    (is (= "UPLOAD" (-> out first :name)))))

(deftest preserves-source-order
  (let [src "parameters {
               string(name: 'A', defaultValue: '1')
               booleanParam(name: 'B', defaultValue: false)
               choice(name: 'C', choices: ['x'])
             }"
        out (p/extract src)]
    (is (= ["A" "B" "C"] (map :name out)))))

(deftest nested-braces-handled
  (let [src "pipeline {
               agent { label 'linux' }
               parameters {
                 string(name: 'X', defaultValue: 'y')
               }
               stages { stage('s') { steps { sh 'x' } } }
             }"
        out (p/extract src)]
    (is (= 1 (count out)))
    (is (= "X" (-> out first :name)))))

(deftest missing-name-skipped
  (let [src "parameters {
               string(defaultValue: 'orphan')
               string(name: 'GOOD', defaultValue: 'ok')
             }"
        out (p/extract src)]
    (is (= 1 (count out)))
    (is (= "GOOD" (-> out first :name)))))

(deftest unknown-call-kinds-ignored
  (let [src "parameters {
               string(name: 'A', defaultValue: 'a')
               somePluginParam(name: 'X')
             }"
        out (p/extract src)]
    (is (= 1 (count out)))
    (is (= "A" (-> out first :name)))))
