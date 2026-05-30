(ns anvil.compat.jenkins.steps.stash-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.compat.jenkins.steps.stash :as stash])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-dir fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *workspace*)
(def ^:dynamic *stash-root*)

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn- write! [dir rel-path content]
  (let [f (io/file dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(use-fixtures :each
  (fn [f]
    (let [ws (temp-dir "anvil-stash-test-ws-")
          sr (temp-dir "anvil-stash-test-sr-")]
      (binding [*workspace* ws *stash-root* sr]
        (try (f)
             (finally (rm-rf ws) (rm-rf sr)))))))

;; ---------------------------------------------------------------------------
;; stash!
;; ---------------------------------------------------------------------------

(deftest stash-copies-matching-files
  (testing "stash with includes='**/*' picks up everything"
    (write! *workspace* "a.txt" "hello")
    (write! *workspace* "sub/b.txt" "world")
    (let [summary (stash/stash! {:name "all"
                                 :workspace *workspace*
                                 :stash-root *stash-root*
                                 :includes "**/*"})]
      (is (= 2 (:files-stashed summary)))
      (is (= "all" (:name summary)))
      (is (.exists (io/file *stash-root* "all" "a.txt")))
      (is (.exists (io/file *stash-root* "all" "sub" "b.txt"))))))

(deftest stash-respects-includes-glob
  (testing "includes='**/*.txt' filters to *.txt files"
    (write! *workspace* "a.txt" "x")
    (write! *workspace* "b.log" "log")
    (write! *workspace* "deep/c.txt" "y")
    (let [summary (stash/stash! {:name "only-txt"
                                 :workspace *workspace*
                                 :stash-root *stash-root*
                                 :includes "**/*.txt"})]
      (is (= 2 (:files-stashed summary)))
      (is (.exists (io/file *stash-root* "only-txt" "a.txt")))
      (is (.exists (io/file *stash-root* "only-txt" "deep" "c.txt")))
      (is (not (.exists (io/file *stash-root* "only-txt" "b.log")))))))

(deftest stash-respects-excludes-glob
  (testing "excludes drops matching files"
    (write! *workspace* "include.txt" "k")
    (write! *workspace* "skip.txt" "v")
    (let [summary (stash/stash! {:name "no-skip"
                                 :workspace *workspace*
                                 :stash-root *stash-root*
                                 :includes "**/*.txt"
                                 :excludes "skip.txt"})]
      (is (= 1 (:files-stashed summary)))
      (is (>= (:skipped-by-excludes summary) 0))
      (is (.exists (io/file *stash-root* "no-skip" "include.txt")))
      (is (not (.exists (io/file *stash-root* "no-skip" "skip.txt")))))))

(deftest stash-overwrites-prior-stash
  (testing "re-stashing the same name replaces prior contents"
    (write! *workspace* "v1.txt" "first")
    (stash/stash! {:name "x" :workspace *workspace* :stash-root *stash-root*
                   :includes "**/*"})
    (is (.exists (io/file *stash-root* "x" "v1.txt")))
    (rm-rf (io/file *workspace* "v1.txt"))
    (write! *workspace* "v2.txt" "second")
    (stash/stash! {:name "x" :workspace *workspace* :stash-root *stash-root*
                   :includes "**/*"})
    (is (not (.exists (io/file *stash-root* "x" "v1.txt")))
        "prior stash content should be cleared")
    (is (.exists (io/file *stash-root* "x" "v2.txt")))))

(deftest stash-rejects-bad-workspace
  (testing "missing workspace dir raises"
    (is (thrown? Exception
                 (stash/stash! {:name "x"
                                :workspace "/nonexistent/path/here/we/hope"
                                :stash-root *stash-root*
                                :includes "**/*"})))))

;; ---------------------------------------------------------------------------
;; unstash!
;; ---------------------------------------------------------------------------

(deftest unstash-restores-files
  (testing "unstash copies the stash dir back into the workspace"
    (write! *workspace* "a.txt" "alpha")
    (write! *workspace* "nested/b.txt" "beta")
    (stash/stash! {:name "roundtrip" :workspace *workspace*
                   :stash-root *stash-root* :includes "**/*"})
    ;; Wipe workspace
    (rm-rf *workspace*)
    (.mkdirs *workspace*)
    (let [r (stash/unstash! {:name "roundtrip"
                             :workspace *workspace*
                             :stash-root *stash-root*})]
      (is (not (:missing? r)))
      (is (= 2 (:files-restored r)))
      (is (= "alpha" (slurp (io/file *workspace* "a.txt"))))
      (is (= "beta"  (slurp (io/file *workspace* "nested/b.txt")))))))

(deftest unstash-missing-name-returns-error-marker
  (testing "unstashing a non-existent stash returns {:missing? true}"
    (let [r (stash/unstash! {:name "never-stashed"
                             :workspace *workspace*
                             :stash-root *stash-root*})]
      (is (:missing? r))
      (is (string? (:error r))))))

;; ---------------------------------------------------------------------------
;; Round-trip across "agents" — two separate workspaces, one stash root
;; ---------------------------------------------------------------------------

(deftest cross-agent-round-trip
  (testing "stash on workspace A, unstash on workspace B (shared stash root)"
    (let [ws-a (temp-dir "ws-a-")
          ws-b (temp-dir "ws-b-")]
      (try
        (write! ws-a "target/jenkins.war" "<war bytes>")
        (stash/stash! {:name "warfile" :workspace ws-a
                       :stash-root *stash-root*
                       :includes "target/*.war"})
        (let [r (stash/unstash! {:name "warfile"
                                 :workspace ws-b
                                 :stash-root *stash-root*})]
          (is (= 1 (:files-restored r)))
          (is (.exists (io/file ws-b "target/jenkins.war"))))
        (finally (rm-rf ws-a) (rm-rf ws-b))))))
