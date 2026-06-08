(ns anvil.compat.jenkins.matrix-axis-step-substitution-test
  "AN8-3b — `${AXIS}` / `$AXIS` substitution inside step bodies.

   AN8-3 (PR #108) covered axis interpolation in tool-version templates
   (`tools { jdk \"${JAVA_VERSION}\" }`). That left a gap: matrix step
   bodies (`echo \"Build for ${PLATFORM}-${JDK_NAME}\"`, `sh \"$X ...\"`,
   `script { if (\"${PLATFORM}\" == ...) }`) reached the Groovy script
   evaluator as literal `$NAME` text. Groovy then raised
   `MissingPropertyException` because PLATFORM / JDK_NAME weren't bound
   in the binding.

   This is the translator-time substitution layer (AN8-3b): the matrix
   cell's axis values are interpolated INTO the step IR before
   dispatch, so the Groovy evaluator never sees the unresolved
   references.

   Wild-corpus driver: apache-camel's Jenkinsfile, where ${PLATFORM} +
   ${JDK_NAME} appear inside both `echo` and `script { }` blocks within
   a matrix whose axes ARE `PLATFORM` and `JDK_NAME`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.matrix-declarative :as mx]
            [anvil.compat.jenkins.translator :as t]))

;; ---------------------------------------------------------------------------
;; Pure substitution helpers
;; ---------------------------------------------------------------------------

(deftest interpolate-axis-refs-handles-braced-and-bare
  (testing "${VAR} braced form substitutes"
    (is (= "linux-build"
           (mx/interpolate-axis-refs "${OS}-build" {"OS" "linux"}))))
  (testing "$VAR bare form substitutes"
    (is (= "linux-build"
           (mx/interpolate-axis-refs "$OS-build" {"OS" "linux"}))))
  (testing "mixed forms substitute"
    (is (= "ubuntu-avx-jdk_17_latest"
           (mx/interpolate-axis-refs "${PLATFORM}-$JDK_NAME"
                                     {"PLATFORM" "ubuntu-avx"
                                      "JDK_NAME" "jdk_17_latest"})))))

(deftest interpolate-axis-refs-leaves-non-axis-refs-alone
  (testing "bash refs that don't name an axis pass through"
    (is (= "$HOME/$OS"
           (mx/interpolate-axis-refs "$HOME/$OS" {}))
        "no axes → unchanged")
    (is (= "$HOME/linux"
           (mx/interpolate-axis-refs "$HOME/$OS" {"OS" "linux"}))
        "$HOME survives, $OS substitutes")))

(deftest interpolate-axis-refs-word-boundary
  (testing "$PLATFORM in $PLATFORM_X does NOT substitute"
    (is (= "$PLATFORM_FILTER"
           (mx/interpolate-axis-refs "$PLATFORM_FILTER" {"PLATFORM" "linux"}))
        "axis-name as prefix of a longer identifier — left untouched")))

(deftest interpolate-axis-refs-no-allocation-on-empty-input
  (is (= "no-vars-here"
         (mx/interpolate-axis-refs "no-vars-here" {"PLATFORM" "linux"}))
      "no $ in input → unchanged")
  (is (= "$X" (mx/interpolate-axis-refs "$X" {}))
      "empty axes → unchanged"))

(deftest interpolate-axis-refs-quotes-replacement
  (testing "axis value containing `$` does not break Java regex replacement"
    (is (= "lib-1.0$RC"
           (mx/interpolate-axis-refs "${VER}" {"VER" "lib-1.0$RC"})))))

;; ---------------------------------------------------------------------------
;; Step-level walker
;; ---------------------------------------------------------------------------

(deftest interpolate-step-rewrites-sh
  (let [step {:type :jenkins/sh :script "./mvnw -P${PLATFORM}"}
        out  (mx/interpolate-step step {"PLATFORM" "ubuntu-avx"})]
    (is (= "./mvnw -Pubuntu-avx" (:script out)))))

(deftest interpolate-step-rewrites-echo
  (let [step {:type :jenkins/echo
              :message "Do Build and test for ${PLATFORM}-${JDK_NAME}"}
        out (mx/interpolate-step step
                                 {"PLATFORM" "ubuntu-avx"
                                  "JDK_NAME" "jdk_17_latest"})]
    (is (= "Do Build and test for ubuntu-avx-jdk_17_latest"
           (:message out)))))

(deftest interpolate-step-rewrites-script-body-source
  (let [step {:type :jenkins/script
              :body-source "if (\"${PLATFORM}\" == \"ubuntu-avx\") { sh 'echo a' }"}
        out (mx/interpolate-step step {"PLATFORM" "ubuntu-avx"})]
    (is (= "if (\"ubuntu-avx\" == \"ubuntu-avx\") { sh 'echo a' }"
           (:body-source out)))))

(deftest interpolate-step-recurses-into-body
  (let [step {:type :jenkins/timeout
              :unit "MINUTES"
              :time 450
              :body [{:type :jenkins/sh :script "echo ${PLATFORM}"}
                     {:type :jenkins/echo :message "phase $JDK_NAME"}]}
        out (mx/interpolate-step step
                                 {"PLATFORM" "ubuntu-avx"
                                  "JDK_NAME" "jdk_17_latest"})]
    (is (= "echo ubuntu-avx" (-> out :body (nth 0) :script)))
    (is (= "phase jdk_17_latest" (-> out :body (nth 1) :message)))))

(deftest interpolate-step-recurses-into-parallel-branches
  (let [step {:type :jenkins/parallel
              :branches {"a" [{:type :jenkins/sh :script "build ${OS}"}]
                         "b" [{:type :jenkins/sh :script "test ${OS}"}]}}
        out (mx/interpolate-step step {"OS" "linux"})]
    (is (= "build linux" (-> out :branches (get "a") (nth 0) :script)))
    (is (= "test linux" (-> out :branches (get "b") (nth 0) :script)))))

(deftest interpolate-step-leaves-unknown-keys-untouched
  (let [step {:type :jenkins/unknown
              :name "emailext"
              :raw-args [{:body "${PLATFORM} report"}]}
        out (mx/interpolate-step step {"PLATFORM" "ubuntu-avx"})]
    (is (= step out)
        ":raw-args is preserved for diagnostic display — never substituted")))

(deftest interpolate-step-handles-empty-axes
  (let [step {:type :jenkins/sh :script "echo ${PLATFORM}"}]
    (is (= step (mx/interpolate-step step {}))
        "empty axes → identical step (no allocation)")
    (is (= step (mx/interpolate-step step nil))
        "nil axes → identical step")))

;; ---------------------------------------------------------------------------
;; End-to-end: parsed Jenkinsfile matrix expansion
;; ---------------------------------------------------------------------------

(deftest camel-shape-matrix-expansion-substitutes-step-bodies
  (testing "apache-camel's `echo \"... ${PLATFORM}-${JDK_NAME}\"` + `script { ... }`
            shape: each cell's IR has the axis refs substituted."
    (let [src "pipeline {
                 agent none
                 stages {
                   stage('BuildAndTest') {
                     matrix {
                       agent { label \"${PLATFORM}\" }
                       axes {
                         axis {
                           name 'JDK_NAME'
                           values 'jdk_17_latest', 'jdk_21_latest'
                         }
                         axis {
                           name 'PLATFORM'
                           values 'ubuntu-avx'
                         }
                       }
                       stages {
                         stage('Build and test') {
                           steps {
                             echo \"Do Build and test for ${PLATFORM}-${JDK_NAME}\"
                             sh 'java -version'
                             script {
                               if (\"${PLATFORM}\" == \"ubuntu-avx\") {
                                 sh \"echo done-${JDK_NAME}\"
                               }
                             }
                           }
                         }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          cells (->> ir :stages
                     (filter #(re-find #"\[JDK_NAME=" (:name %))))]
      (is (= 2 (count cells))
          "two cells: jdk_17_latest + jdk_21_latest with PLATFORM=ubuntu-avx")
      (testing "every cell's echo message has both axes substituted"
        (let [echo-msgs (mapv (fn [c]
                                (->> (:steps c)
                                     (some #(when (= :jenkins/echo (:type %))
                                              (:message %)))))
                              cells)]
          (is (= #{"Do Build and test for ubuntu-avx-jdk_17_latest"
                   "Do Build and test for ubuntu-avx-jdk_21_latest"}
                 (set echo-msgs))
              "no literal ${PLATFORM} or ${JDK_NAME} survives in echo bodies")))
      (testing "script { } body-source has both axes substituted"
        (let [script-bodies (mapv (fn [c]
                                    (->> (:steps c)
                                         (some #(when (= :jenkins/script (:type %))
                                                  (:body-source %)))))
                                  cells)]
          (is (every? (fn [s] (and s (not (.contains s "${PLATFORM}"))))
                      script-bodies)
              "no ${PLATFORM} survives in script block bodies")
          (is (every? (fn [s] (and s (not (.contains s "${JDK_NAME}"))))
                      script-bodies)
              "no ${JDK_NAME} survives in script block bodies")
          (is (some (fn [s] (and s (.contains s "ubuntu-avx") (.contains s "jdk_17_latest")))
                    script-bodies)
              "jdk_17 cell's script body carries both axis values"))))))

(deftest matrix-substitution-leaves-bash-vars-alone
  (testing "non-axis $VAR references survive unchanged"
    (let [src "pipeline {
                 agent { label 'foo' }
                 stages {
                   stage('X') {
                     matrix {
                       agent any
                       axes {
                         axis {
                           name 'PLATFORM'
                           values 'linux', 'windows'
                         }
                       }
                       stages {
                         stage('Y') {
                           steps {
                             sh \"echo $HOME on $PLATFORM and $BRANCH_NAME\"
                           }
                         }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          cell (->> ir :stages
                    (filter #(re-find #"\[PLATFORM=linux" (:name %)))
                    first)
          sh-script (->> (:steps cell)
                         (some #(when (= :jenkins/sh (:type %)) (:script %))))]
      (is (some? sh-script))
      (is (.contains sh-script "$HOME")
          "$HOME (bash) survives")
      (is (.contains sh-script "$BRANCH_NAME")
          "$BRANCH_NAME (Jenkins runtime env) survives")
      (is (.contains sh-script "linux")
          "$PLATFORM was substituted to its axis value"))))

(deftest single-quoted-sh-still-rewrites-when-text-contains-axis-name
  (testing "Single-quoted Groovy strings come through as :const → :script.
            They look identical to GString interpolated ones at IR level,
            so we rewrite them too. This is the documented trade-off in
            matrix-declarative.clj — the wild corpus uses GString form
            for axis refs, so the rewrite is a no-op in practice."
    (let [src "pipeline {
                 agent { label 'foo' }
                 stages {
                   stage('X') {
                     matrix {
                       agent any
                       axes {
                         axis {
                           name 'OS'
                           values 'linux', 'windows'
                         }
                       }
                       stages {
                         stage('Y') {
                           steps { sh 'echo Not ${OS}' }
                         }
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          cell (->> ir :stages
                    (filter #(re-find #"\[OS=linux" (:name %)))
                    first)
          sh-script (->> (:steps cell)
                         (some #(when (= :jenkins/sh (:type %)) (:script %))))]
      ;; Documented behavior: ${OS} is substituted even from a
      ;; single-quoted string. This matches what Jenkins itself would do
      ;; if it could see the axis binding — and avoids the asymmetry of
      ;; leaving the build broken when the author quoted ambiguously.
      (is (= "echo Not linux" sh-script)))))

(deftest camel-corpus-jenkinsfile-end-to-end
  (testing "apache-camel's real Jenkinsfile: every matrix cell has both
            PLATFORM and JDK_NAME substituted in its echo + script bodies.
            This pins the camel wild-corpus regression — before AN8-3b
            these bodies arrived as literal `${PLATFORM}-${JDK_NAME}` and
            the Groovy script evaluator died on MissingPropertyException
            before reaching `mvn`."
    (let [src (slurp "test/resources/jenkins-corpus/apache__camel__main__Jenkinsfile.Jenkinsfile")
          ir (t/parse src)
          cells (->> ir :stages
                     (filter #(re-find #"\[JDK_NAME=" (:name %))))
          collect-text (fn [cell]
                         (->> (:steps cell)
                              (mapcat (fn [s]
                                        [(:script s) (:message s) (:body-source s)]))
                              (remove nil?)
                              (str/join "\n")))]
      (is (pos? (count cells))
          "camel Jenkinsfile expands to >0 matrix cells")
      (testing "every cell's combined step text resolves both axes"
        (doseq [c cells]
          (let [text (collect-text c)
                axes (:matrix-axes c)
                platform (get axes "PLATFORM")
                jdk (get axes "JDK_NAME")]
            (is (not (.contains text "${PLATFORM}"))
                (str "cell " (:name c) " — no literal ${PLATFORM} survives"))
            (is (not (.contains text "${JDK_NAME}"))
                (str "cell " (:name c) " — no literal ${JDK_NAME} survives"))
            (is (.contains text platform)
                (str "cell " (:name c) " — substituted PLATFORM=" platform
                     " appears in text"))
            (is (.contains text jdk)
                (str "cell " (:name c) " — substituted JDK_NAME=" jdk
                     " appears in text"))))))))

(deftest non-matrix-stage-untouched-by-substitution
  (testing "stages outside a matrix block see no substitution (no axes to apply)"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('A') {
                     steps { sh \"echo ${X}\" }
                   }
                 }
               }"
          ir (t/parse src)
          stage (->> (:stages ir)
                     (filter #(= "A" (:name %)))
                     first)
          sh-script (->> (:steps stage)
                         (some #(when (= :jenkins/sh (:type %)) (:script %))))]
      (is (= "echo $X" sh-script)
          "no axes → script preserves the GString text Groovy delivered"))))
