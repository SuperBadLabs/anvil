(ns anvil.compat.jenkins.runtime-test
  "Production tests for the Pipeline DSL runtime — script {} execution
   that routes through the AnvilJenkinsDispatcher.

   Mirrors the spike #3 tests; the runtime productization preserves the
   same behavior with a different side-effects path (now goes through
   the StepDispatcher protocol rather than a local atom)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.compat.jenkins.runtime :as r]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- run [src & {:keys [canned env-vars]}]
  (let [d (ad/make {:sh-canned (atom (or canned {}))})
        ctx-atom (atom {:cwd "/workspace" :env (or env-vars {})})
        result (r/run-script-block src d ctx-atom)]
    {:result result
     :effects @(:effects d)
     :ctx @ctx-atom}))

(deftest sh-and-echo-route-through-dispatcher-test
  (testing "scripted sh/echo calls land in the dispatcher's effects atom"
    (let [{:keys [effects]}
          (run "echo 'starting'
                sh 'make compile'
                sh 'make test'
                echo 'done'")]
      (is (= [[:echo "starting"]
              [:sh {:cmd "make compile" :cwd "/workspace"}]
              [:sh {:cmd "make test"    :cwd "/workspace"}]
              [:echo "done"]]
             effects)))))

(deftest sh-returnstdout-and-trim-test
  (testing "sh(script: '...', returnStdout: true).trim() returns canned stdout"
    (let [{:keys [result effects]}
          (run "def v = sh(script: 'cat VERSION', returnStdout: true).trim()
                echo \"version is ${v}\"
                v"
               :canned {"cat VERSION" "  1.2.3-RELEASE  \n"})]
      (is (= "1.2.3-RELEASE" result))
      (is (some #{[:echo "version is 1.2.3-RELEASE"]} effects)))))

(deftest env-property-access-test
  (testing "env.X = 'foo' and ${env.X} flow through the Expando"
    (let [{:keys [effects]}
          (run "env.BUILD_TIME = '2026-05-29'
                echo \"time is ${env.BUILD_TIME}\"
                sh \"tag-release ${env.BUILD_TIME}\"")]
      (is (some #{[:echo "time is 2026-05-29"]} effects))
      (is (some #(and (= :sh (first %))
                      (= "tag-release 2026-05-29" (-> % second :cmd)))
                effects)))))

(deftest control-flow-test
  (testing "if / else if cascades over sh-returned strings"
    (let [{:keys [effects]}
          (run "def v = sh(script: 'cat VERSION', returnStdout: true).trim()
                def t = 'milestone'
                if (v.endsWith('SNAPSHOT')) { t = 'snapshot' }
                else if (v.endsWith('RELEASE')) { t = 'release' }
                echo \"type=${t}\""
               :canned {"cat VERSION" "1.2.3-RELEASE"})]
      (is (some #{[:echo "type=release"]} effects)))))

(deftest dir-scope-wrapper-test
  (testing "dir('subdir') { sh '...' } pushes and pops cwd; effects show enter/leave"
    (let [{:keys [effects ctx]}
          (run "sh 'echo top'
                dir('subproject') {
                  sh 'echo inside'
                }
                sh 'echo back'")]
      (is (= "/workspace" (:cwd ctx)) "cwd restored after dir")
      (let [sh-cwds (->> effects (filter #(= :sh (first %))) (mapv #(-> % second :cwd)))]
        (is (= ["/workspace" "/workspace/subproject" "/workspace"] sh-cwds)))
      (is (some #(and (= :dir/enter (first %)) (= "/workspace/subproject" (second %))) effects))
      (is (some #(and (= :dir/leave (first %)) (= "/workspace/subproject" (second %))) effects)))))

(deftest deleteDir-and-cleanWs-test
  (testing "deleteDir() and cleanWs() both emit :delete-dir effects"
    (let [{:keys [effects]}
          (run "deleteDir()
                cleanWs()")]
      (is (= 2 (count (filter #(= :delete-dir (first %)) effects)))))))

(deftest stress-real-script-block-test
  (testing "spring-plugin-style: assignment, named-args sh, if/else, interpolation, dir+stash"
    (let [{:keys [result effects]}
          (run "sh 'rm -rf ?'
                sh 'MAVEN_OPTS=\"-Duser.name=jenkins\" ./mvnw -q help:evaluate'

                PROJECT_VERSION = sh(
                        script: 'MAVEN_OPTS=\"-Duser.name=jenkins\" ./mvnw help:evaluate -o | grep -v INFO',
                        returnStdout: true).trim()

                RELEASE_TYPE = 'milestone'
                if (PROJECT_VERSION.endsWith('SNAPSHOT')) {
                  RELEASE_TYPE = 'snapshot'
                } else if (PROJECT_VERSION.endsWith('RELEASE')) {
                  RELEASE_TYPE = 'release'
                }

                OUTPUT = sh(script: \"PROFILE=ci,${RELEASE_TYPE} ci/build.sh\",
                            returnStdout: true).trim()
                echo \"$OUTPUT\"

                dir('build_info_dir') {
                  stash name: 'build_info', includes: '*.json'
                }
                PROJECT_VERSION + ':' + RELEASE_TYPE"
               :canned {"MAVEN_OPTS=\"-Duser.name=jenkins\" ./mvnw help:evaluate -o | grep -v INFO" "  1.2.3-RELEASE  "
                        "PROFILE=ci,release ci/build.sh"
                        "  Artifactory Build Info Recorder: /tmp/x.json  "})]
      (is (= "1.2.3-RELEASE:release" result))
      ;; Spot-check critical effects
      (is (= 4 (count (filter #(= :sh (first %)) effects))))
      (is (some #(and (= :echo (first %))
                      (str/includes? (second %) "Artifactory")) effects))
      (is (some #(and (= :dir/enter (first %))
                      (= "/workspace/build_info_dir" (second %))) effects))
      (is (some #(= :stash (first %)) effects))
      (is (some #(and (= :dir/leave (first %))
                      (= "/workspace/build_info_dir" (second %))) effects)))))
