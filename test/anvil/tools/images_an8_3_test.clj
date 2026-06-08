(ns anvil.tools.images-an8-3-test
  "AN8-3 — unit tests for ${AXIS} interpolation in tool versions.

   The translator surfaces matrix-cell tools with raw `$JAVA_VERSION`
   templates; the dispatcher calls `interpolate-tools` to substitute
   against the active cell's axis values before the AN8-1 image
   lookup. These tests pin the interpolation contract; dispatcher
   integration is covered in
   `anvil.compat.jenkins.dispatcher-an8-3-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.tools.images :as images]))

(deftest interpolate-tools-bare-dollar-form
  (testing "Groovy GStringExpression.getText() emits bare $VAR; we substitute"
    (let [tools [{:type :maven :version "maven_latest"}
                 {:type :jdk   :version "$JAVA_VERSION"}]
          axes {"JAVA_VERSION" "jdk_1.8_latest"}
          {:keys [tools referenced-axes substitutions]}
          (images/interpolate-tools tools axes)]
      (is (= [{:type :maven :version "maven_latest"}
              {:type :jdk
               :version "jdk_1.8_latest"
               :version-template "$JAVA_VERSION"}]
             tools))
      (is (= ["JAVA_VERSION"] referenced-axes))
      (is (= {"JAVA_VERSION" "jdk_1.8_latest"} substitutions)))))

(deftest interpolate-tools-braced-form
  (testing "developer writes ${X}; the cdata may carry it as braced too"
    (let [tools [{:type :jdk :version "${JAVA_VERSION}"}]
          axes {"JAVA_VERSION" "jdk_11_latest"}
          {:keys [tools]} (images/interpolate-tools tools axes)]
      (is (= "jdk_11_latest" (-> tools first :version))))))

(deftest interpolate-tools-no-axes-no-op
  (testing "empty axes returns tools unchanged"
    (let [tools [{:type :jdk :version "$JAVA_VERSION"}]
          {result :tools} (images/interpolate-tools tools {})]
      (is (= tools result)))))

(deftest interpolate-tools-no-variables-no-op
  (testing "tools without $ references are returned unchanged"
    (let [tools [{:type :jdk :version "jdk_17_latest"}]
          axes {"JAVA_VERSION" "jdk_1.8_latest"}
          {:keys [tools referenced-axes]} (images/interpolate-tools tools axes)]
      (is (= [{:type :jdk :version "jdk_17_latest"}] tools))
      (is (empty? referenced-axes)))))

(deftest interpolate-tools-unrelated-var-stays
  (testing "${X} that isn't an axis is left in place — diagnostic surface stays honest"
    (let [tools [{:type :jdk :version "$NOT_AN_AXIS"}]
          axes {"JAVA_VERSION" "jdk_11_latest"}
          {result :tools referenced-axes :referenced-axes}
          (images/interpolate-tools tools axes)]
      (is (= "$NOT_AN_AXIS" (-> result first :version)))
      (is (empty? referenced-axes)))))

(deftest interpolate-tools-word-boundary
  (testing "$JAVA does not consume $JAVA_VERSION"
    (let [tools [{:type :jdk :version "$JAVA_VERSION"}]
          axes {"JAVA" "wrong"}
          {result :tools} (images/interpolate-tools tools axes)]
      (is (= "$JAVA_VERSION" (-> result first :version))
          "$JAVA does NOT match inside $JAVA_VERSION"))))

(deftest interpolate-tools-multiple-axes
  (testing "multiple axes substituted in the same template"
    (let [tools [{:type :foo :version "$A-$B"}]
          axes {"A" "alpha" "B" "beta"}
          {result :tools} (images/interpolate-tools tools axes)]
      (is (= "alpha-beta" (-> result first :version))))))

(deftest interpolate-tools-special-chars-in-value
  (testing "axis values containing $ are properly quoted in replacement"
    (let [tools [{:type :jdk :version "$JAVA_VERSION"}]
          axes {"JAVA_VERSION" "x$y\\z"}
          {result :tools} (images/interpolate-tools tools axes)]
      (is (= "x$y\\z" (-> result first :version))))))
