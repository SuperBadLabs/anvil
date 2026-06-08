(ns anvil.compat.jenkins.dispatcher-an8-3-test
  "AN8-3 — matrix-declarative composition with `tools{}` directive.

   Pins zookeeper's real Jenkinsfile shape:

       matrix {
         axes  { axis { name 'JAVA_VERSION' values 'jdk_1.8_latest', 'jdk_11_latest' } }
         tools { maven 'maven_latest' ; jdk \"${JAVA_VERSION}\" }
         stages { stage('BuildAndTest') { steps { sh '…' } } }
       }

   The contract under :anvil.features/tools-directive:
     1. Translator extracts the matrix-level `tools{}` block.
     2. Each matrix cell carries the matrix-level tools, with parent-stage
        tools (if any) merging by `:type` and cell-level winning.
     3. The dispatcher interpolates `${JAVA_VERSION}` against the active
        cell's axis value BEFORE invoking AN8-1's tool-image lookup.
     4. The dispatcher emits :tools/axis-interpolated alongside
        :tools/resolved (or :tools/unmapped) so operators see the
        substitution chain.

   Composes with AN8-1; gated by the SAME feature flag (no new flag)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.translator :as t]
            [anvil.tools.images :as images]
            [anvil.features :as features]))

(use-fixtures :each
  (fn [f]
    (let [prev (features/snapshot)]
      (try
        (features/set! :tools-directive true)
        (images/clear-cache!)
        (f)
        (finally
          (doseq [[k v] prev]
            (features/set! k v))
          (images/clear-cache!))))))

(defn- run-with-images [src images-map]
  (with-redefs [images/all (constantly images-map)]
    (let [ir (t/parse src)
          wrapped (agent/wrap-pipeline-with-agent-events ir)
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages wrapped))}
          dispatcher (ad/make)
          result (d/run-pipeline flat dispatcher {})]
      {:result result :effects @(:effects dispatcher) :ir ir})))

(def zookeeper-source
  "pipeline {
     agent { label 'Hadoop' }
     stages {
       stage('Prepare') {
         matrix {
           agent any
           axes {
             axis {
               name 'JAVA_VERSION'
               values 'jdk_1.8_latest', 'jdk_11_latest'
             }
           }
           tools {
             maven 'maven_latest'
             jdk \"${JAVA_VERSION}\"
           }
           stages {
             stage('BuildAndTest') {
               steps { sh 'mvn verify' }
             }
           }
         }
       }
     }
   }")

(deftest matrix-level-tools-attach-to-each-cell
  (testing "the translator surfaces matrix-level tools on each cell IR"
    (let [ir (t/parse zookeeper-source)
          cells (->> ir :stages
                     (filter #(re-find #"\[JAVA_VERSION=" (:name %))))]
      (is (= 2 (count cells))
          "JAVA_VERSION axis with 2 values → 2 cells")
      (is (every? #(seq (:tools %)) cells)
          "every cell has :tools attached")
      (is (every? #(= 2 (count (:tools %))) cells)
          "every cell carries both `maven` and `jdk` tool specs")
      (is (every? #(seq (:matrix-axes %)) cells)
          "every cell rides with :matrix-axes for axis-driven interpolation"))))

(deftest matrix-tools-resolves-per-cell-via-interpolation
  (testing "${JAVA_VERSION} substitutes per cell before AN8-1 image lookup"
    (let [{:keys [effects]}
          (run-with-images
           zookeeper-source
           ;; Operator maps each interpolated (raw-declaration-order) key:
           {"maven_latest+jdk_1.8_latest" "maven:3.9-eclipse-temurin-8"
            "maven_latest+jdk_11_latest"  "maven:3.9-eclipse-temurin-11"})
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        (mapv second))]
      (is (= 2 (count resolved))
          "both cells resolve to an operator-mapped image")
      (is (= #{"maven:3.9-eclipse-temurin-8" "maven:3.9-eclipse-temurin-11"}
             (set (map :image resolved)))
          "the two cells map to the two JDK-specific images"))))

(deftest matrix-tools-axis-interpolated-effect
  (testing ":tools/axis-interpolated diagnostic effect carries the substitution"
    (let [{:keys [effects]}
          (run-with-images
           zookeeper-source
           {"maven_latest+jdk_1.8_latest" "img-8:1"
            "maven_latest+jdk_11_latest"  "img-11:1"})
          interp-effs (->> effects
                           (filter #(= :tools/axis-interpolated (first %)))
                           (mapv second))]
      (is (= 2 (count interp-effs))
          "one :tools/axis-interpolated per cell")
      (is (every? #(= ["JAVA_VERSION"] (:referenced-axes %)) interp-effs))
      (is (= #{"jdk_1.8_latest" "jdk_11_latest"}
             (set (mapv #(get (:substitutions %) "JAVA_VERSION") interp-effs)))
          "each cell shows its own JAVA_VERSION substitution"))))

(deftest matrix-tools-unmapped-uses-interpolated-keys
  (testing "with no operator mapping, :tools/unmapped carries the INTERPOLATED candidate keys"
    (let [{:keys [effects]} (run-with-images zookeeper-source {})
          unmapped (->> effects
                        (filter #(= :tools/unmapped (first %)))
                        (mapv second))]
      (is (= 2 (count unmapped)))
      (is (some (fn [u] (some #{"maven_latest+jdk_1.8_latest"}
                              (:candidate-keys u))) unmapped)
          "interpolated key for jdk_1.8 cell is in the candidate list")
      (is (some (fn [u] (some #{"maven_latest+jdk_11_latest"}
                              (:candidate-keys u))) unmapped)
          "interpolated key for jdk_11 cell is in the candidate list"))))

(deftest matrix-tools-feature-flag-gates-everything
  (testing "with :tools-directive OFF, no AN8-3 effects fire"
    (features/set! :tools-directive false)
    (let [{:keys [effects]} (run-with-images
                             zookeeper-source
                             {"maven_latest+jdk_1.8_latest" "img-8"})]
      (is (empty? (filter #(#{:tools/resolved
                              :tools/unmapped
                              :tools/axis-interpolated} (first %))
                          effects))
          "flag off → no AN8-3 / AN8-1 effects"))))

(deftest outer-pipeline-tools-compose-with-matrix-cell-tools
  (testing "pipeline tools provide base; matrix-cell tools win on :type collision.

   The composition lands during `wrap-pipeline-with-agent-events`: each
   matrix cell already carries its parent-stage⊕matrix-level merged
   tools, then the wrapper layers pipeline-level tools as the base
   (cell wins on :type collision). The effective tools-vector arrives
   at the dispatcher in the synthetic :jenkins/agent-stage-enter step."
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'maven_outer'
                   gradle 'gradle_outer'
                 }
                 stages {
                   stage('Build') {
                     matrix {
                       axes {
                         axis {
                           name 'JDK'
                           values 'jdk_17_latest'
                         }
                       }
                       tools {
                         jdk  \"${JDK}\"
                         maven 'maven_inner'
                       }
                       stages {
                         stage('Inner') { steps { sh 'mvn verify' } }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          wrapped (agent/wrap-pipeline-with-agent-events ir)
          cell (->> wrapped :stages
                    (filter #(re-find #"\[JDK=" (:name %)))
                    first)
          enter (->> (:steps cell)
                     (filter #(= :jenkins/agent-stage-enter (:type %)))
                     first)
          eff-tools (:tools enter)
          tool-types (set (map :type eff-tools))
          maven-version (some #(when (= :maven (:type %)) (:version %))
                              eff-tools)
          gradle-version (some #(when (= :gradle (:type %)) (:version %))
                               eff-tools)
          jdk-version (some #(when (= :jdk (:type %)) (:version %))
                            eff-tools)]
      (is (some? cell)
          "matrix expansion produces a JDK= cell")
      (is (some? enter)
          "the cell's agent-stage-enter step is synthesized")
      (is (= #{:maven :gradle :jdk} tool-types)
          "all three tool types composed onto the cell (pipeline ⊕ matrix)")
      (is (= "maven_inner" maven-version)
          "matrix-level maven wins over pipeline-level maven (same :type)")
      (is (= "gradle_outer" gradle-version)
          "pipeline-level gradle survives — no matrix override for that type")
      (is (= "$JDK" jdk-version)
          "matrix-level jdk template arrives un-interpolated — the
          dispatcher substitutes per cell at stage-enter time"))))

(deftest zookeeper-corpus-jenkinsfile-end-to-end
  (testing "the real zookeeper Jenkinsfile from test/resources/jenkins-corpus
            classifies into 2 matrix cells, each emitting axis-interpolated
            tools that resolve through operator mappings"
    (let [src (slurp "test/resources/jenkins-corpus/apache__zookeeper__master__Jenkinsfile.Jenkinsfile")
          {:keys [effects ir]}
          (run-with-images
           src
           {"maven_latest+jdk_1.8_latest" "maven:3.9-eclipse-temurin-8"
            "maven_latest+jdk_11_latest"  "maven:3.9-eclipse-temurin-11"})
          cells (->> ir :stages
                     (filter #(re-find #"\[JAVA_VERSION=" (:name %))))
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        (mapv second))
          interp (->> effects
                      (filter #(= :tools/axis-interpolated (first %)))
                      (mapv second))]
      (is (= 2 (count cells))
          "the zookeeper corpus matrix expands to 2 cells")
      (is (= 2 (count resolved)))
      (is (= 2 (count interp)))
      (is (= #{"maven:3.9-eclipse-temurin-8" "maven:3.9-eclipse-temurin-11"}
             (set (map :image resolved)))
          "operator-mapped JDK-specific images resolve per cell"))))

(deftest non-matrix-stage-no-axes-no-interpolation
  (testing "regular (non-matrix) stages with tools{} are unaffected by AN8-3"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools { jdk 'jdk_17_latest' }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          {:keys [effects]} (run-with-images
                             src
                             {"jdk_17_latest" "eclipse-temurin:17"})
          interp (filter #(= :tools/axis-interpolated (first %)) effects)
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        first)]
      (is (empty? interp)
          "no :tools/axis-interpolated for non-matrix stage")
      (is (= "eclipse-temurin:17" (-> resolved second :image))
          "AN8-1 path still resolves normally"))))
