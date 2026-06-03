(ns anvil.tools.mise-test
  "T7.5 — 3 project sample fixtures + stubbed-backend smoke."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [anvil.tools.mise :as mise])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-ws [files]
  (let [d (.toFile (Files/createTempDirectory
                    "anvil-mise-test-"
                    (into-array FileAttribute [])))]
    (doseq [[name content] files]
      (spit (io/file d name) content))
    d))

(defn- rm-rf [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf c)))
  (.delete f))

(deftest detects-tool-versions
  (let [ws (tmp-ws {".tool-versions"
                    "nodejs 22.5.1\npython 3.12.7\nruby 3.3.0\n"})]
    (try
      (let [d (mise/detect ws)]
        (is (= :tool-versions (:source d)))
        (is (= {"nodejs" "22.5.1" "python" "3.12.7" "ruby" "3.3.0"}
               (:tools d))))
      (finally (rm-rf ws)))))

(deftest tool-versions-ignores-comments-and-blanks
  (let [ws (tmp-ws {".tool-versions"
                    "# project tools\nnodejs 22.5.1\n\n# python\npython 3.12.7\n"})]
    (try
      (let [d (mise/detect ws)]
        (is (= 2 (count (:tools d))))
        (is (= "22.5.1" (get (:tools d) "nodejs"))))
      (finally (rm-rf ws)))))

(deftest detects-mise-toml
  (let [ws (tmp-ws {".mise.toml"
                    "[tools]\nnodejs = '22.5.1'\npython = '3.12.7'\n"})]
    (try
      (let [d (mise/detect ws)]
        (is (= :mise-toml (:source d)))
        (is (= {"nodejs" "22.5.1" "python" "3.12.7"} (:tools d))))
      (finally (rm-rf ws)))))

(deftest mise-toml-wins-when-both-present
  (let [ws (tmp-ws {".mise.toml" "[tools]\nnodejs = '22.5.1'\n"
                    ".tool-versions" "nodejs 18.0.0\nruby 3.3.0\n"})]
    (try
      (let [d (mise/detect ws)]
        (is (= :mise-toml (:source d)))
        (is (= "22.5.1" (get (:tools d) "nodejs"))))
      (finally (rm-rf ws)))))

(deftest no-tools-returns-nil
  (let [ws (tmp-ws {"unrelated.txt" "hi"})]
    (try
      (is (nil? (mise/detect ws)))
      (finally (rm-rf ws)))))

(deftest provision-skipped-when-no-tools
  (let [ws (tmp-ws {"unrelated.txt" "x"})]
    (try
      (is (= :skipped (:status (mise/provision! ws))))
      (finally (rm-rf ws)))))

(deftest provision-skipped-when-no-backend
  (let [ws (tmp-ws {".tool-versions" "nodejs 22.5.1\n"})]
    (try
      (with-redefs [mise/resolve-backend (constantly :none)]
        (let [r (mise/provision! ws)]
          (is (= :none (:backend r)))
          (is (= :skipped (:status r)))
          (is (= {"nodejs" "22.5.1"} (:tools r)))))
      (finally (rm-rf ws)))))

(deftest provision-calls-backend-when-detected
  (let [ws (tmp-ws {".tool-versions" "nodejs 22.5.1\n"})
        calls (atom [])]
    (try
      (with-redefs [mise/resolve-backend (constantly :mise)
                    babashka.process/sh
                    (fn [& args]
                      (swap! calls conj args)
                      {:exit 0 :out "mocked" :err ""})]
        (let [r (mise/provision! ws)]
          (is (= :mise (:backend r)))
          (is (= :ok (:status r)))
          (is (pos? (count @calls)))))
      (finally (rm-rf ws)))))
