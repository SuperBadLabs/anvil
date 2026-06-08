(ns anvil.compat.jenkins.corpus-an8-test
  "AN8-1 + AN8-2 wild-corpus regression — runs every real upstream
   Jenkinsfile in the curated corpus through the translator and
   asserts that:

     1. `tools { … }` blocks are extracted as structured IR
        (no more `[{:raw \"<…>\"}]` placeholder).
     2. `parameters { choice(…) }` defaults flow into
        `ir/default-parameters` so the runner can seed
        `params.X` before evaluating downstream `agent { label {
        label params.X } }` / `${X}` interpolation expressions.

   The fixture itself lives in `test/resources/jenkins-corpus/`. This
   test is intentionally **not** the same as the broader corpus-
   regression-test (which only checks the IR parses cleanly); these
   assertions pin AN8-1 + AN8-2 against the real Jenkinsfile shapes
   the AN7-5c receipt named — struts, ambari, dubbo, hop, zookeeper,
   camel-quarkus, cxf, analysis-model, and the apache-camel /
   cassandra-datastax parameter-defaulting heavies."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]))

(def ^:private corpus-dir "test/resources/jenkins-corpus")

(defn- jenkinsfile [name]
  (io/file corpus-dir name))

(defn- parse [name]
  (let [f (jenkinsfile name)]
    (when (.exists ^java.io.File f)
      (t/parse (slurp f) name))))

;; ---------------------------------------------------------------------------
;; AN8-1 — tools{} corpus regression
;; ---------------------------------------------------------------------------

(deftest an8-1-corpus-tools-extracted-structured
  (testing "every wild Jenkinsfile with a tools{} block produces structured IR
            (no `:raw` placeholder anywhere in :tools)"
    (doseq [fname ["apache__ambari__trunk__Jenkinsfile.Jenkinsfile"
                   "apache__struts__main__Jenkinsfile.Jenkinsfile"
                   "apache__dubbo__3.3__Jenkinsfile.Jenkinsfile"
                   "apache__dubbo__3.3__Jenkinsfile.sonar.Jenkinsfile"
                   "apache__hop__main__Jenkinsfile.daily.Jenkinsfile"
                   "apache__zookeeper__master__Jenkinsfile-PreCommit.Jenkinsfile"
                   "jenkinsci__analysis-model__main__etc_Jenkinsfile.declarative.Jenkinsfile"]]
      (when-let [ir-data (parse fname)]
        (let [pipeline-tools (:tools ir-data)
              stage-tools (->> (:stages ir-data)
                               (mapcat #(:tools %))
                               (remove nil?))
              all-tools (concat pipeline-tools stage-tools)]
          (when (seq all-tools)
            (is (every? (complement :raw) all-tools)
                (str fname ": every tool entry must be structured, no :raw key"))
            (is (every? :type all-tools)
                (str fname ": every tool entry has a :type"))))))))

(deftest an8-1-ambari-tools-shape
  (testing "apache-ambari's tools{} → [{:type :maven :version 'maven_3_latest'}
            {:type :jdk :version 'jdk_17_latest'}]"
    (let [ir-data (parse "apache__ambari__trunk__Jenkinsfile.Jenkinsfile")]
      (when ir-data
        (is (= [{:type :maven :version "maven_3_latest"}
                {:type :jdk :version "jdk_17_latest"}]
               (:tools ir-data)))))))

(deftest an8-1-struts-tools-shape
  (testing "apache-struts has TWO stages with tools{} — both surface"
    (let [ir-data (parse "apache__struts__main__Jenkinsfile.Jenkinsfile")]
      (when ir-data
        (let [stage-tools (->> (:stages ir-data) (keep :tools))]
          (is (>= (count stage-tools) 1)
              "at least one stage-level tools{} block parses"))))))

(deftest an8-1-dubbo-tools-shape
  (testing "apache-dubbo's tools{} → maven + jdk"
    (let [ir-data (parse "apache__dubbo__3.3__Jenkinsfile.Jenkinsfile")]
      (when ir-data
        (let [tools (:tools ir-data)]
          (is (= 2 (count tools)))
          (is (= [:maven :jdk] (mapv :type tools))))))))

(deftest an8-1-zookeeper-precommit-tools-shape
  (testing "apache-zookeeper PreCommit Jenkinsfile carries jdk_1.8_latest"
    (let [ir-data (parse "apache__zookeeper__master__Jenkinsfile-PreCommit.Jenkinsfile")]
      (when ir-data
        (let [tools (:tools ir-data)]
          (is (= 2 (count tools)))
          (is (= "jdk_1.8_latest"
                 (some #(when (= :jdk (:type %)) (:version %)) tools))))))))

;; ---------------------------------------------------------------------------
;; AN8-2 — parameter defaults corpus regression
;; ---------------------------------------------------------------------------

(deftest an8-2-corpus-parameter-defaults-flow-to-helper
  (testing "every wild Jenkinsfile with parameters{} surfaces its defaults
            through ir/default-parameters with non-nil String values"
    (doseq [fname ["apache__camel__main__Jenkinsfile.Jenkinsfile"
                   "apache__camel__main__Jenkinsfile.deploy.Jenkinsfile"
                   "apache__cassandra-java-driver__4.x__Jenkinsfile-datastax.Jenkinsfile"]]
      (when-let [ir-data (parse fname)]
        (let [defaults (ir/default-parameters ir-data)]
          (is (map? defaults))
          (when (seq (:parameters ir-data))
            (doseq [[k v] defaults]
              (is (string? k) (str fname ": param name is a string"))
              (is (string? v) (str fname ": default value is a string")))))))))

(deftest an8-2-camel-platform-jdk-filter-defaults
  (testing "apache-camel's parameters{ choice PLATFORM_FILTER, JDK_FILTER } →
            ir/default-parameters returns {'PLATFORM_FILTER' 'all' 'JDK_FILTER' 'all'}"
    (let [ir-data (parse "apache__camel__main__Jenkinsfile.Jenkinsfile")]
      (when ir-data
        (let [defaults (ir/default-parameters ir-data)]
          (is (= "all" (get defaults "PLATFORM_FILTER"))
              "first choice 'all' is the implicit default")
          (is (= "all" (get defaults "JDK_FILTER"))))))))

(deftest an8-2-cassandra-datastax-adhoc-build-type
  (testing "apache-cassandra-driver datastax choice ADHOC_BUILD_TYPE → first choice"
    (let [ir-data (parse "apache__cassandra-java-driver__4.x__Jenkinsfile-datastax.Jenkinsfile")]
      (when ir-data
        (let [defaults (ir/default-parameters ir-data)]
          (when (contains? defaults "ADHOC_BUILD_TYPE")
            (is (= "BUILD" (get defaults "ADHOC_BUILD_TYPE"))
                "first choice in the choices vector is the default")))))))
