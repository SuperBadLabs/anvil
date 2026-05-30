(ns anvil.compat.jenkins.env-test
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.env :as env]))

(deftest defaults-populate-cleanly-test
  (testing "missing fields render as empty strings, not nil"
    (let [e (env/build-env {})]
      (is (every? string? (vals e)))
      (is (= "" (e "BUILD_NUMBER")))
      (is (= "anvil-local" (e "NODE_NAME"))))))

(deftest provided-fields-flow-through-test
  (testing "build context fields end up in the env map under the right Jenkins keys"
    (let [e (env/build-env {:build-number 42
                            :job-name "myproject"
                            :workspace "/var/lib/anvil/builds/42"
                            :branch-name "main"})]
      (is (= "42" (e "BUILD_NUMBER")))
      (is (= "myproject" (e "JOB_NAME")))
      (is (= "myproject" (e "JOB_BASE_NAME")) "job-base-name defaults to job-name")
      (is (= "/var/lib/anvil/builds/42" (e "WORKSPACE")))
      (is (= "main" (e "BRANCH_NAME")))
      (is (= "#42" (e "BUILD_DISPLAY_NAME"))))))

(deftest pipeline-env-block-extends-test
  (testing "extra-env from the pipeline's environment {} block extends and overrides"
    (let [e (env/build-env {:build-number 7
                            :extra-env {"MAVEN_OPTS" "-Xmx3G"
                                        "BUILD_NUMBER" "OVERRIDE"}})]
      (is (= "-Xmx3G" (e "MAVEN_OPTS")))
      (is (= "OVERRIDE" (e "BUILD_NUMBER"))
          "user-provided env wins over Jenkins-conventional"))))

(deftest divergences-documented-test
  (testing "divergences/0 returns the documented divergence list"
    (let [ds (env/divergences)]
      (is (seq ds))
      (is (every? #(contains? % :jenkins-var) ds))
      (is (every? #(contains? % :status) ds)))))
