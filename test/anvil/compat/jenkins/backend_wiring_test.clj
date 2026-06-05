(ns anvil.compat.jenkins.backend-wiring-test
  "AN5-3 — Lock down the bridge between anvil's dispatcher ctx and
   chengis-core's `ExecutionBackend` protocol.

   These tests exercise the wiring layer with real cmd execution
   through LocalShell, plus mock-backend tests for the docker
   branch (the real-docker integration test is opt-in under
   `:docker-integration` to stay portable across CI environments)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [chengis.engine.backend :as backend]
            [chengis.engine.backend.docker :as docker]
            [anvil.compat.jenkins.backend-wiring :as bw]))

;; ---------------------------------------------------------------------------
;; Backend selection
;; ---------------------------------------------------------------------------

(deftest backend-for-ctx-returns-local-shell-by-default
  (testing "no active-agent → LocalShell"
    (let [b (bw/backend-for-ctx {})]
      (is (= "local-shell" (backend/backend-name b)))))
  (testing "active-agent without :docker → LocalShell"
    (let [b (bw/backend-for-ctx {:active-agent {:type :label :label "linux"}})]
      (is (= "local-shell" (backend/backend-name b)))))
  (testing ":any active-agent → LocalShell"
    (let [b (bw/backend-for-ctx {:active-agent {:type :any}})]
      (is (= "local-shell" (backend/backend-name b))))))

(deftest backend-for-ctx-returns-docker-for-docker-active-agent
  (testing "active-agent with :docker {:image …} → DockerBackend"
    (let [b (bw/backend-for-ctx {:active-agent
                                  {:docker {:image "eclipse-temurin:21"}}})]
      (is (instance? chengis.engine.backend.docker.DockerBackend b))
      (is (= "docker:eclipse-temurin:21" (backend/backend-name b))))))

(deftest backend-for-ctx-passes-extra-args-through
  (testing "agent { docker { image 'X' args '--network host' } } → backend with args"
    (let [b (bw/backend-for-ctx {:active-agent
                                  {:docker {:image "alpine:3.20"
                                            :args "--network host"}}})]
      (is (instance? chengis.engine.backend.docker.DockerBackend b))
      (is (= "--network host" (-> b :config :extra-args))))))

(deftest backend-for-ctx-is-per-step-mode
  (testing "AN5-3 first cut uses :per-step mode (no lifecycle plumbing required)"
    (let [b (bw/backend-for-ctx {:active-agent {:docker {:image "alpine:3.20"}}})]
      (is (= :per-step (-> b :config :mode))
          ":per-build optimization comes in AN5-3b"))))

;; ---------------------------------------------------------------------------
;; execute-via-backend — real subprocess via LocalShell
;; ---------------------------------------------------------------------------

(defn- mktmp! []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "anvil-bw-test-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(deftest execute-via-backend-runs-real-shell
  (testing "non-docker ctx → real local subprocess"
    (let [tmp (mktmp!)
          result (bw/execute-via-backend
                  "echo backend-wiring-works"
                  {:cwd (.getAbsolutePath tmp)
                   :workspace (.getAbsolutePath tmp)
                   :env {}})]
      (is (= 0 (:exit result)))
      (is (.startsWith (or (:stdout result) "") "backend-wiring-works")
          (str "expected stdout to start with our marker, got: "
               (pr-str (:stdout result)))))))

(deftest execute-via-backend-propagates-nonzero-exit
  (testing "command that exits non-zero → :exit reflects that"
    (let [tmp (mktmp!)
          result (bw/execute-via-backend
                  "exit 7"
                  {:cwd (.getAbsolutePath tmp)
                   :workspace (.getAbsolutePath tmp)
                   :env {}})]
      (is (= 7 (:exit result))))))

(deftest execute-via-backend-returns-shell-execute-shape
  (testing "result has the keys anvil's legacy shell-execute callers expect"
    (let [tmp (mktmp!)
          result (bw/execute-via-backend
                  "true"
                  {:cwd (.getAbsolutePath tmp)
                   :workspace (.getAbsolutePath tmp)
                   :env {}})]
      (is (contains? result :exit))
      (is (contains? result :stdout))
      (is (contains? result :stderr))
      (is (contains? result :streamed?))
      (is (boolean? (:streamed? result))))))

(deftest execute-via-backend-passes-env-through
  (testing "env vars from ctx reach the subprocess"
    (let [tmp (mktmp!)
          result (bw/execute-via-backend
                  "echo $AN5_TEST_MARKER"
                  {:cwd (.getAbsolutePath tmp)
                   :workspace (.getAbsolutePath tmp)
                   :env {"AN5_TEST_MARKER" "from-env-ok"}})]
      (is (= 0 (:exit result)))
      (is (.contains (or (:stdout result) "") "from-env-ok")
          (str "expected stdout to contain env marker, got: "
               (pr-str (:stdout result)))))))

(deftest execute-via-backend-runs-in-cwd
  (testing "ctx :cwd becomes the subprocess cwd"
    (let [tmp (mktmp!)
          result (bw/execute-via-backend
                  "pwd"
                  {:cwd (.getAbsolutePath tmp)
                   :workspace (.getAbsolutePath tmp)
                   :env {}})]
      (is (= 0 (:exit result)))
      (is (.contains (or (:stdout result) "") (.getName tmp))
          (str "expected pwd to contain tmp name, got: "
               (pr-str (:stdout result)))))))

;; ---------------------------------------------------------------------------
;; Docker integration — opt-in via `:docker-integration` selector
;; ---------------------------------------------------------------------------

(defn- docker-available? []
  (try
    (let [{:keys [exit]} (sh/sh "docker" "version" "--format" "ok")]
      (zero? exit))
    (catch Exception _ false)))

(deftest ^:docker-integration execute-via-backend-runs-in-docker-container
  (when (docker-available?)
    (testing "agent { docker { image 'alpine:3.20' } } → sh runs inside the container"
      (let [tmp (mktmp!)
            result (bw/execute-via-backend
                    "echo from-container && uname -a"
                    {:cwd (.getAbsolutePath tmp)
                     :workspace (.getAbsolutePath tmp)
                     :env {}
                     :job-name "an5-3-integration"
                     :build-number 1
                     :active-agent {:docker {:image "alpine:3.20"}}})]
        (is (= 0 (:exit result))
            (str "docker run failed: stderr=" (pr-str (:stderr result))))
        (is (.contains (or (:stdout result) "") "from-container"))
        ;; uname -a on alpine reports Linux even on macOS host —
        ;; proves we're inside the container, not running on host.
        (is (.contains (or (:stdout result) "") "Linux"))))))
