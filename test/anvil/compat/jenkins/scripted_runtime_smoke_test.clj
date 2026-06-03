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
