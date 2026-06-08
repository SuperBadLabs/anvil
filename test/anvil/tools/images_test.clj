(ns anvil.tools.images-test
  "AN8-1 — unit tests for `:anvil.tools/images` resolution.

   The translator turns `tools { maven 'X' jdk 'Y' }` into a vector of
   {:type :maven :version \"X\"} maps; these tests pin the candidate-
   key ordering and resolution semantics against operator maps.
   Integration into the dispatcher is covered in
   `dispatcher_an8_tools_test`."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.tools.images :as images]))

(deftest candidate-keys-maven-jdk
  (testing "raw declaration-order versions join first"
    (let [tools [{:type :maven :version "maven_3_latest"}
                 {:type :jdk   :version "jdk_17_latest"}]
          keys (images/candidate-keys tools)]
      (is (= "maven_3_latest+jdk_17_latest" (first keys))
          "the literal Jenkinsfile surface is the first key")
      (is (some #{"jdk_17_latest+maven_3_latest"} keys)
          "canonical-sort order is offered too")
      (is (some #{"maven-maven_3_latest+jdk-jdk_17_latest"} keys)
          "composite type-version key is offered")
      (is (some #{"jdk_17_latest"} keys)
          "individual tool versions are offered")
      (is (some #{"maven-maven_3_latest"} keys)
          "individual type-version composites are offered")
      (is (= "*" (last keys))
          "wildcard is the last-resort key"))))

(deftest candidate-keys-deduped
  (testing "no duplicates in candidate-keys"
    (let [keys (images/candidate-keys
                [{:type :maven :version "X"} {:type :jdk :version "Y"}])]
      (is (= (count keys) (count (distinct keys)))))))

(deftest candidate-keys-single-tool
  (testing "only one tool — single-tool key is offered"
    (let [keys (images/candidate-keys [{:type :jdk :version "jdk_17_latest"}])]
      (is (some #{"jdk_17_latest"} keys))
      (is (some #{"jdk-jdk_17_latest"} keys))
      (is (some #{"*"} keys)))))

(deftest candidate-keys-tool-without-version
  (testing "tool with :type but no :version contributes its type name"
    (let [keys (images/candidate-keys [{:type :maven}])]
      (is (some #{"maven"} keys))
      (is (some #{"*"} keys)))))

(deftest resolve-image-hits-raw-key
  (testing "the raw join hits before any sorted/composite variant"
    (let [tools [{:type :maven :version "maven_3_latest"}
                 {:type :jdk   :version "jdk_17_latest"}]
          r (images/resolve-image tools
                                  {"maven_3_latest+jdk_17_latest"
                                   "maven:3.9-eclipse-temurin-17"})]
      (is (= "maven:3.9-eclipse-temurin-17" (:image r)))
      (is (= "maven_3_latest+jdk_17_latest" (:matched-key r))))))

(deftest resolve-image-falls-back-to-canonical
  (testing "operator can map by canonical sort when declaration order differs"
    ;; Declaration order is [maven, jdk]; versions are [m, j]. Canonical
    ;; sort puts j before m, so a "j+m" key DOES differ from the raw
    ;; "m+j" key and the operator can opt into either form.
    (let [tools [{:type :maven :version "m"} {:type :jdk :version "j"}]
          r (images/resolve-image tools
                                  {"j+m" "operator-canonical-image"})]
      (is (= "operator-canonical-image" (:image r)))
      (is (= "j+m" (:matched-key r))))))

(deftest resolve-image-single-tool-key
  (testing "single-tool maps work"
    (let [tools [{:type :jdk :version "jdk_17_latest"}]
          r (images/resolve-image tools
                                  {"jdk_17_latest" "eclipse-temurin:17-jdk"})]
      (is (= "eclipse-temurin:17-jdk" (:image r)))
      (is (= "jdk_17_latest" (:matched-key r))))))

(deftest resolve-image-wildcard-fallback
  (testing "operator can set a wildcard fallback image"
    (let [tools [{:type :maven :version "unmapped"}]
          r (images/resolve-image tools
                                  {"*" "fallback-image"})]
      (is (= "fallback-image" (:image r)))
      (is (= "*" (:matched-key r))))))

(deftest resolve-image-miss-returns-candidate-keys
  (testing "no mapping → nil image + ALL candidate keys for diagnostics"
    (let [tools [{:type :maven :version "X"} {:type :jdk :version "Y"}]
          r (images/resolve-image tools {})]
      (is (nil? (:image r)))
      (is (seq (:candidate-keys r)))
      (is (some #{"X+Y"} (:candidate-keys r))
          "diagnostic must include the raw key so operators know what to map"))))

(deftest resolve-image-priority
  (testing "raw key beats canonical when both are mapped"
    (let [tools [{:type :maven :version "M"} {:type :jdk :version "J"}]
          r (images/resolve-image tools
                                  {"M+J" "raw-image"
                                   "J+M" "canonical-image"})]
      (is (= "raw-image" (:image r))
          "raw declaration-order key wins on the priority list"))))
