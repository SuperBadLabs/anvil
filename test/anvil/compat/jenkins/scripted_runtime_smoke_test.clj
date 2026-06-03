(ns anvil.compat.jenkins.scripted-runtime-smoke-test
  "Tier-3 scripted runtime smoke — prove Groovy + DSL bindings together
   resolve GStrings against destructured `def (a,b) = it` inside
   `combinations { … }` and dispatch `sh` with the resolved string."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.scripted-runtime :as srt]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- effects [dsp] @(:effects dsp))

(deftest gstring-resolves-from-def-binding
  (let [dsp (ad/make)
        ctx-atom (atom {:cwd "/workspace" :env {"BUILD_TAG" "anvil-1"}})
        src "def name = 'world'\nsh \"echo hello-${name}\"\n"]
    (srt/run-scripted-file src dsp ctx-atom)
    (let [shs (filter #(= :sh (first %)) (effects dsp))]
      (is (some #(re-find #"echo hello-world" (str (:cmd (second %)))) shs)
          "GString ${name} → resolved 'world' from def"))))

(deftest combinations-with-destructuring-iterates-with-bindings
  (let [dsp (ad/make)
        ctx-atom (atom {:cwd "/workspace" :env {}})
        src (str "def axes = [platforms: ['linux','windows'], jdks: [21,25]]\n"
                 "axes.values().combinations { def (platform, jdk) = it\n"
                 "  if (platform == 'windows' && jdk != axes.jdks.last()) return\n"
                 "  sh \"build-${platform}-jdk${jdk}\"\n"
                 "}\n")]
    (srt/run-scripted-file src dsp ctx-atom)
    (let [shs (->> (effects dsp)
                   (filter #(= :sh (first %)))
                   (map #(str (:cmd (second %))))
                   set)]
      (testing "matrix produces 3 cells (linux x [21,25] + windows@25)"
        (is (contains? shs "build-linux-jdk21"))
        (is (contains? shs "build-linux-jdk25"))
        (is (contains? shs "build-windows-jdk25")))
      (testing "windows-jdk21 was excluded by the filter"
        (is (not (contains? shs "build-windows-jdk21")))))))

(deftest node-and-stage-block-bodies-run
  (let [dsp (ad/make)
        ctx-atom (atom {:cwd "/workspace" :env {}})
        src "stage('build') { node('any') { sh 'echo inside' } }"]
    (srt/run-scripted-file src dsp ctx-atom)
    (let [shs (filter #(= :sh (first %)) (effects dsp))]
      (is (some #(re-find #"echo inside" (str (:cmd (second %)))) shs)))))

(deftest infra-runMaven-fires-as-sh
  (let [dsp (ad/make)
        ctx-atom (atom {:cwd "/workspace" :env {}})
        src "infra.runMaven(['clean', 'install'], 21)"]
    (srt/run-scripted-file src dsp ctx-atom)
    (let [shs (filter #(= :sh (first %)) (effects dsp))]
      (is (some #(re-find #"mvn clean install" (str (:cmd (second %)))) shs)
          "infra.runMaven should shell out to mvn"))))

(deftest jenkins-env-globals-resolve-as-bare-identifiers
  (testing "JENKINS_URL et al. are bare-identifier accessible — fixes
            blueocean-style `if (JENKINS_URL == '...') { … }` which used
            to throw MissingPropertyException in scripted-eval"
    (let [dsp (ad/make)
          ctx-atom (atom {:cwd "/workspace" :env {}
                          :job-name "demo-job" :build-number 42
                          :anvil-url "http://dogfood:8765/"})
          src (str "def hits = []\n"
                   "hits << JENKINS_URL\n"
                   "hits << BUILD_NUMBER\n"
                   "hits << JOB_NAME\n"
                   "hits << WORKSPACE\n"
                   "sh \"echo ${hits.join(',')}\"\n")]
      (srt/run-scripted-file src dsp ctx-atom)
      (let [shs (->> (effects dsp)
                     (filter #(= :sh (first %)))
                     (map #(str (:cmd (second %)))))]
        (is (some #(re-find #"http://dogfood:8765/" %) shs))
        (is (some #(re-find #",42," %) shs)         "BUILD_NUMBER threads through")
        (is (some #(re-find #"demo-job" %) shs)     "JOB_NAME threads through")
        (is (some #(re-find #"/workspace" %) shs)   "WORKSPACE threads through")))))

(deftest env-dot-globals-also-work
  (testing "env.JENKINS_URL — the Expando-property form — should resolve
            to the same value as the bare identifier"
    (let [dsp (ad/make)
          ctx-atom (atom {:cwd "/workspace" :env {}
                          :job-name "job-a" :build-number 7})
          src "sh \"j=${env.JOB_NAME} b=${env.BUILD_NUMBER}\""]
      (srt/run-scripted-file src dsp ctx-atom)
      (let [shs (->> (effects dsp) (filter #(= :sh (first %)))
                     (map #(str (:cmd (second %)))))]
        (is (some #(re-find #"j=job-a b=7" %) shs))))))

(deftest buildPlugin-records-as-shared-lib-unresolved
  (testing "buildPlugin(...) — the dominant jenkins-infra one-liner — used
            to throw MissingMethodException. Now it's recorded as a
            shared-lib-unresolved scaffolding effect so the build report
            shows 'we saw the call but cannot resolve the shared library'
            instead of either crashing or silently passing"
    (let [dsp (ad/make)
          ctx-atom (atom {:cwd "/workspace" :env {}})
          src (str "buildPlugin(\n"
                   "  forkCount: '1C',\n"
                   "  useContainerAgent: false,\n"
                   "  configurations: [\n"
                   "    [platform: 'linux', jdk: 25],\n"
                   "    [platform: 'windows', jdk: 21]\n"
                   "])\n")]
      (srt/run-scripted-file src dsp ctx-atom)
      (let [recs (filter #(= :jenkins/shared-lib-unresolved (first %))
                         (effects dsp))]
        (is (seq recs) "buildPlugin call should be recorded")
        (is (some (fn [[_ v]] (= "buildPlugin" (:name v))) recs)
            "the recorded :name is buildPlugin")))))

(deftest blueocean-style-conditional-buildPlugin-runs
  (testing "blueocean's Jenkinsfile: `if (JENKINS_URL == X) buildPlugin(…); return`
            — exercises BOTH new behaviors at once. Used to throw
            MissingPropertyException on the bare JENKINS_URL identifier"
    (let [dsp (ad/make)
          ctx-atom (atom {:cwd "/workspace" :env {}
                          :anvil-url "http://localhost:8765/"})
          src (str "if (JENKINS_URL == 'http://localhost:8765/') {\n"
                   "  buildPlugin(configurations: [[platform: 'linux', jdk: 21]])\n"
                   "  return\n"
                   "}\n"
                   "buildPlugin()\n")]
      (srt/run-scripted-file src dsp ctx-atom)
      (let [bp (filter (fn [[k v]] (and (= :jenkins/shared-lib-unresolved k)
                                        (= "buildPlugin" (:name v))))
                       (effects dsp))]
        (is (= 1 (count bp))
            "should record exactly one buildPlugin call (the if branch ran, then return)")))))
