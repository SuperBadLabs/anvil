(ns anvil.ai.repo-context-test
  "v0.4.1 T3.2 — tests for anvil.ai.repo-context.

   Hermetic — creates throwaway repo skeletons under java.io.tmpdir
   so the scanner walks real files (the dir-prune + extension count
   path can't be honestly tested with stubs)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [anvil.ai.repo-context :as rc]))

(def ^:dynamic *repo* nil)

(defn- with-fresh-repo [f]
  (let [dir (fs/create-temp-dir {:prefix "anvil-rc-test-"})]
    (try
      (binding [*repo* (str dir)]
        (f))
      (finally
        (fs/delete-tree dir)))))

(use-fixtures :each with-fresh-repo)

(defn- touch
  "Create a file at rel-path under *repo*, with optional content."
  ([rel-path] (touch rel-path ""))
  ([rel-path content]
   (let [f (fs/file *repo* rel-path)]
     (fs/create-dirs (fs/parent f))
     (spit f content))))

;; ---------------------------------------------------------------------------
;; Language detection
;; ---------------------------------------------------------------------------

(deftest scan-counts-languages-by-extension
  (touch "src/main.py")
  (touch "src/util.py")
  (touch "src/helper.py")
  (touch "tests/test_main.py")
  (touch "README.md")
  (let [ctx (rc/scan *repo*)]
    (is (= 4 (get-in ctx [:languages "Python"]))
        "four .py files counted")
    (is (not (contains? (:languages ctx) "Markdown"))
        ".md is not in the language map — README isn't a build signal")))

(deftest scan-handles-polyglot-repo
  (touch "src/main/java/Foo.java")
  (touch "src/main/kotlin/Bar.kt")
  (touch "frontend/app.ts")
  (touch "frontend/util.tsx")
  (touch "scripts/build.sh")
  (let [ctx (rc/scan *repo*)]
    (is (= {"Java" 1 "Kotlin" 1 "TypeScript" 2 "Shell" 1}
           (:languages ctx)))))

(deftest scan-recognizes-dockerfile-without-extension
  (touch "Dockerfile" "FROM eclipse-temurin:21")
  (let [ctx (rc/scan *repo*)]
    (is (contains? (:languages ctx) "Docker"))))

;; ---------------------------------------------------------------------------
;; Build-tool detection
;; ---------------------------------------------------------------------------

(deftest scan-detects-single-build-tool
  (touch "project.clj" "(defproject foo \"0.1.0\")")
  (touch "src/foo.clj")
  (let [ctx (rc/scan *repo*)]
    (is (= ["Leiningen"] (:build-tools ctx)))
    (is (= ["project.clj"] (:package-files ctx)))))

(deftest scan-detects-multi-module-maven
  (testing "real-world: pom.xml at root + every module"
    (touch "pom.xml")
    (touch "module-a/pom.xml")
    (touch "module-b/pom.xml")
    (let [ctx (rc/scan *repo*)]
      (is (= ["Maven"] (:build-tools ctx)))
      (is (= 3 (count (:package-files ctx)))
          "every pom.xml surfaces in :package-files; the operator can see
           the project shape, not just 'it's Maven'"))))

(deftest scan-detects-polyglot-build-tools
  (testing "a repo with multiple build systems — common in monorepos"
    (touch "backend/pom.xml")
    (touch "frontend/package.json")
    (touch "docker/Dockerfile")
    (let [ctx (rc/scan *repo*)]
      (is (= ["Docker" "Maven" "npm/Node"] (:build-tools ctx))
          "alphabetical, distinct"))))

;; ---------------------------------------------------------------------------
;; CI hint detection
;; ---------------------------------------------------------------------------

(deftest scan-detects-existing-github-actions
  (touch ".github/workflows/test.yml")
  (touch ".github/workflows/release.yml")
  (let [ctx (rc/scan *repo*)]
    (is (contains? (set (:ci-systems ctx)) "GitHub Actions"))))

(deftest scan-detects-existing-jenkinsfile
  (testing "real `anvil init` case — repo already has a Jenkinsfile;
            scaffold should be informed by that, not replace it blind"
    (touch "Jenkinsfile" "pipeline { agent any }")
    (let [ctx (rc/scan *repo*)]
      (is (contains? (set (:ci-systems ctx)) "Jenkins (existing)")))))

(deftest scan-detects-gitlab-and-circle
  (touch ".gitlab-ci.yml")
  (touch ".circleci/config.yml")
  (let [ctx (rc/scan *repo*)]
    (is (= #{"CircleCI" "GitLab CI"}
           (set (:ci-systems ctx))))))

;; ---------------------------------------------------------------------------
;; Skip-dirs — performance + correctness
;; ---------------------------------------------------------------------------

(deftest scan-skips-noisy-build-dirs
  (testing "node_modules / target / .git should not pollute language counts"
    (touch "src/app.ts")
    (touch "node_modules/react/index.js")
    (touch "node_modules/react/lib/util.js")
    (touch "target/build-output/some.class")
    (touch ".git/HEAD")
    (let [ctx (rc/scan *repo*)]
      (is (= {"TypeScript" 1} (:languages ctx))
          "node_modules/*.js NOT counted; src/app.ts is")
      (is (= 1 (:file-count ctx))
          "skipped dirs don't inflate file-count either"))))

;; ---------------------------------------------------------------------------
;; Tool-version files (T7 mise wiring)
;; ---------------------------------------------------------------------------

(deftest scan-detects-tool-version-files
  (touch ".tool-versions" "java temurin-21\nnode 20")
  (touch ".nvmrc" "20")
  (let [ctx (rc/scan *repo*)]
    (is (= {".tool-versions" true ".nvmrc" true} (:tool-versions ctx)))))

;; ---------------------------------------------------------------------------
;; Empty + edge cases
;; ---------------------------------------------------------------------------

(deftest scan-empty-repo-returns-empty-shapes
  (let [ctx (rc/scan *repo*)]
    (is (= {} (:languages ctx)))
    (is (= [] (:build-tools ctx)))
    (is (= [] (:ci-systems ctx)))
    (is (= 0 (:file-count ctx)))
    (is (false? (:truncated? ctx)))))

(deftest scan-truncates-at-max-files
  (doseq [i (range 25)]
    (touch (str "src/file" i ".py")))
  (let [ctx (rc/scan *repo* {:max-files 10})]
    (is (true? (:truncated? ctx))
        "truncated flag set when cap reached")
    (is (= 10 (:file-count ctx))
        "walked exactly the cap before stopping")))

;; ---------------------------------------------------------------------------
;; primary-language + summary-string
;; ---------------------------------------------------------------------------

(deftest primary-language-picks-the-most-files
  (touch "src/a.go")
  (touch "src/b.go")
  (touch "scripts/c.py")
  (is (= "Go" (rc/primary-language (rc/scan *repo*)))))

(deftest primary-language-nil-for-empty
  (is (nil? (rc/primary-language (rc/scan *repo*)))))

(deftest summary-string-is-prompt-shaped
  (touch "pom.xml")
  (touch "src/main/java/Foo.java")
  (touch "src/main/java/Bar.java")
  (touch ".github/workflows/test.yml")
  (touch ".tool-versions")
  (let [s (rc/summary-string (rc/scan *repo*))]
    (is (str/includes? s "Java (2 files)"))
    (is (str/includes? s "Maven"))
    (is (str/includes? s "GitHub Actions"))
    (is (str/includes? s "pom.xml"))
    (is (str/includes? s ".tool-versions"))
    (is (not (str/includes? s "/tmp/"))
        "absolute paths NOT in the summary — the operator's repo
         location is incidental and shouldn't go to the API")))
