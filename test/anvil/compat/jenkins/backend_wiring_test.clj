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
            [anvil.config :as config]
            [anvil.build-overrides :as bo]
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

;; ---------------------------------------------------------------------------
;; AN7-5 — resource-limits parsing from agent { docker { args '…' } }
;; ---------------------------------------------------------------------------

(deftest parse-resource-limits-extracts-memory-units
  (testing "--memory=4g → 4096 MB"
    (is (= {:memory-mb 4096} (:resource-limits (bw/parse-resource-limits "--memory=4g")))))
  (testing "uppercase --memory=4G works too"
    (is (= {:memory-mb 4096} (:resource-limits (bw/parse-resource-limits "--memory=4G")))))
  (testing "--memory=512m → 512 MB"
    (is (= {:memory-mb 512} (:resource-limits (bw/parse-resource-limits "--memory=512m")))))
  (testing "bare digits assumed MB"
    (is (= {:memory-mb 2048} (:resource-limits (bw/parse-resource-limits "--memory=2048"))))))

(deftest parse-resource-limits-extracts-cpus
  (testing "--cpus=2 → 2.0 (chengis-core expects double)"
    (is (= {:cpus 2.0} (:resource-limits (bw/parse-resource-limits "--cpus=2")))))
  (testing "fractional --cpus=1.5"
    (is (= {:cpus 1.5} (:resource-limits (bw/parse-resource-limits "--cpus=1.5"))))))

(deftest parse-resource-limits-extracts-pids-limit
  (is (= {:pids-max 512} (:resource-limits (bw/parse-resource-limits "--pids-limit=512")))))

(deftest parse-resource-limits-extracts-cpu-shares
  (is (= {:cpu-shares 1024} (:resource-limits (bw/parse-resource-limits "--cpu-shares=1024")))))

(deftest parse-resource-limits-handles-multiple-flags
  (testing "all four resource flags at once"
    (let [{:keys [resource-limits residual-extra-args]}
          (bw/parse-resource-limits "--memory=4g --cpus=2 --pids-limit=512 --cpu-shares=1024")]
      (is (= {:memory-mb 4096 :cpus 2.0 :pids-max 512 :cpu-shares 1024}
             resource-limits))
      (is (nil? residual-extra-args)
          "all flags consumed → residual is nil"))))

(deftest parse-resource-limits-preserves-unknown-flags-in-residual
  (testing "--network host stays in residual"
    (let [{:keys [resource-limits residual-extra-args]}
          (bw/parse-resource-limits "--memory=4g --network host")]
      (is (= {:memory-mb 4096} resource-limits))
      (is (= "--network host" residual-extra-args)
          "non-resource flags pass through"))))

(deftest parse-resource-limits-empty-and-nil-input
  (testing "nil input → empty limits + nil residual"
    (let [r (bw/parse-resource-limits nil)]
      (is (= {} (:resource-limits r)))
      (is (nil? (:residual-extra-args r)))))
  (testing "blank input → empty limits"
    (is (= {} (:resource-limits (bw/parse-resource-limits ""))))
    (is (= {} (:resource-limits (bw/parse-resource-limits "   "))))))

(deftest parse-resource-limits-rejects-malformed
  (testing "--memory=potato doesn't match digit regex; stays in residual"
    (let [r (bw/parse-resource-limits "--memory=potato")]
      (is (= {} (:resource-limits r))
          "no recognized resource flag")
      (is (= "--memory=potato" (:residual-extra-args r))
          "malformed value passes through to docker for its own error"))))

(deftest backend-for-ctx-applies-resource-limits
  (testing "agent { docker { args '--memory=4g --cpus=2' } } → :resource-limits on backend"
    (let [b (bw/backend-for-ctx {:active-agent
                                  {:docker {:image "maven:3.9-eclipse-temurin-17"
                                            :args "--memory=4g --cpus=2"}}})]
      (is (instance? chengis.engine.backend.docker.DockerBackend b))
      (is (= {:memory-mb 4096 :cpus 2.0}
             (-> b :config :resource-limits))
          "memory + cpus reach chengis-core as structured shape")
      (is (nil? (-> b :config :extra-args))
          "extra-args is nil once all flags are consumed"))))

(deftest backend-for-ctx-resource-limits-coexist-with-other-flags
  (testing "mix of resource + non-resource flags: structured + residual extra-args"
    (let [b (bw/backend-for-ctx {:active-agent
                                  {:docker {:image "alpine:3.20"
                                            :args "--memory=2g --network host -u root"}}})]
      (is (= {:memory-mb 2048} (-> b :config :resource-limits)))
      (is (= "--network host -u root" (-> b :config :extra-args))
          "non-resource flags remain in extra-args"))))

(deftest backend-for-ctx-no-resource-limits-key-when-empty
  (testing "no resource flags → :resource-limits key absent"
    (let [b (bw/backend-for-ctx {:active-agent
                                  {:docker {:image "alpine:3.20"
                                            :args "--network host"}}})]
      (is (not (contains? (:config b) :resource-limits))
          "should not assoc empty :resource-limits"))))

;; ---------------------------------------------------------------------------
;; AN7-5b — operator-side build overrides via anvil.edn
;; ---------------------------------------------------------------------------

(defn- with-override [edn-map f]
  (bo/clear-cache!)
  (with-redefs [config/load-edn (fn ([_] edn-map) ([_ _] edn-map))]
    (try (f) (finally (bo/clear-cache!)))))

(deftest backend-for-ctx-merges-override-resource-limits
  (testing "operator's :docker-resource-limits override wins over parsed Jenkinsfile flags"
    (with-override
      {:anvil.build-overrides
       {"wild-apache-activemq"
        {:docker-resource-limits {:memory-mb 4096 :cpus 2.0}}}}
      (fn []
        (let [b (bw/backend-for-ctx
                 {:job-name "wild-apache-activemq"
                  :active-agent {:docker {:image "maven:3.9-eclipse-temurin-17"}}})]
          (is (= {:memory-mb 4096 :cpus 2.0}
                 (-> b :config :resource-limits))
              "override applied even when Jenkinsfile has no args"))))))

(deftest backend-for-ctx-override-augments-jenkinsfile-flags
  (testing "Jenkinsfile parses :memory-mb; override adds :cpus → both end up on the backend"
    (with-override
      {:anvil.build-overrides
       {"a" {:docker-resource-limits {:cpus 2.0}}}}
      (fn []
        (let [b (bw/backend-for-ctx
                 {:job-name "a"
                  :active-agent {:docker {:image "alpine:3.20"
                                          :args "--memory=2g"}}})]
          (is (= {:memory-mb 2048 :cpus 2.0}
                 (-> b :config :resource-limits))
              "Jenkinsfile + override compose"))))))

(deftest backend-for-ctx-override-replaces-overlapping-key
  (testing "if Jenkinsfile sets --memory=2g and override sets :memory-mb 4096, override wins"
    (with-override
      {:anvil.build-overrides
       {"a" {:docker-resource-limits {:memory-mb 4096}}}}
      (fn []
        (let [b (bw/backend-for-ctx
                 {:job-name "a"
                  :active-agent {:docker {:image "alpine:3.20"
                                          :args "--memory=2g"}}})]
          (is (= {:memory-mb 4096}
                 (-> b :config :resource-limits))
              "operator override wins on collision"))))))

(deftest backend-for-ctx-no-override-for-unmatched-job
  (testing "override-config present but no key matches job-name → no override applied"
    (with-override
      {:anvil.build-overrides
       {"other-build" {:docker-resource-limits {:memory-mb 4096}}}}
      (fn []
        (let [b (bw/backend-for-ctx
                 {:job-name "untuned-build"
                  :active-agent {:docker {:image "alpine:3.20"}}})]
          (is (not (contains? (:config b) :resource-limits))
              "unmatched job-name → no resource-limits"))))))

(deftest apply-build-overrides-merges-env-extra
  (testing "override env-extra merged onto ctx :env, override wins on collision"
    (with-override
      {:anvil.build-overrides
       {"a" {:env-extra {"MAVEN_OPTS" "-Xmx2g" "FOO" "bar"}}}}
      (fn []
        (let [ctx (bw/apply-build-overrides
                   {:job-name "a"
                    :env {"PATH" "/usr/bin" "MAVEN_OPTS" "-Xmx512m"}})]
          (is (= "/usr/bin" (get (:env ctx) "PATH"))
              "existing keys preserved")
          (is (= "-Xmx2g" (get (:env ctx) "MAVEN_OPTS"))
              "override wins on collision")
          (is (= "bar" (get (:env ctx) "FOO"))
              "new keys added"))))))

(deftest apply-build-overrides-noop-when-no-override
  (testing "ctx returned unchanged when no override matches"
    (with-override {}
      (fn []
        (let [ctx {:job-name "a" :env {"X" "Y"}}]
          (is (identical? ctx (bw/apply-build-overrides ctx))
              "identical pointer when nothing to merge"))))))

(deftest apply-build-overrides-handles-missing-env
  (testing "ctx with no :env key gets env populated from override"
    (with-override
      {:anvil.build-overrides
       {"a" {:env-extra {"K" "V"}}}}
      (fn []
        (let [ctx (bw/apply-build-overrides {:job-name "a"})]
          (is (= {"K" "V"} (:env ctx))))))))

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
