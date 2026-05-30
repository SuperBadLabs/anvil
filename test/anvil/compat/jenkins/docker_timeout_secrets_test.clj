(ns anvil.compat.jenkins.docker-timeout-secrets-test
  "TX9 phase 2 — Docker agent execution, timeout enforcement, and
   credential masking end-to-end with real subprocess execution.

   The Docker-argv builder is tested as a pure function (no docker
   needed). A separate ^:docker-integration test actually runs a docker
   container; it's tagged so the default `lein test` selector skips
   it on machines without docker."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as sh]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.agent :as agent]))

;; ---------------------------------------------------------------------------
;; Docker argv construction (pure)
;; ---------------------------------------------------------------------------

(deftest docker-args-baseline-test
  (testing "build-docker-args produces a canonical docker run argv"
    (let [argv (ad/build-docker-args
                {:image "alpine:3.20"}
                "echo hi"
                "/builds/job/1"
                nil)
          n (count argv)]
      ;; Prefix
      (is (= "docker"        (nth argv 0)))
      (is (= "run"           (nth argv 1)))
      (is (= "--rm"          (nth argv 2)))
      (is (= "-i"            (nth argv 3)))
      (is (= "-v"            (nth argv 4)))
      (is (= "/builds/job/1:/builds/job/1" (nth argv 5)))
      (is (= "-w"            (nth argv 6)))
      (is (= "/builds/job/1" (nth argv 7)))
      ;; Tail: [image, sh, -c, cmd]
      (is (= "alpine:3.20"   (nth argv (- n 4))))
      (is (= "sh"            (nth argv (- n 3))))
      (is (= "-c"            (nth argv (- n 2))))
      (is (= "echo hi"       (last argv))))))

(deftest docker-args-with-env-test
  (testing "env vars become repeated -e flags before the image"
    (let [argv (ad/build-docker-args
                {:image "node:18"}
                "node -e 'console.log(process.env.MY_VAR)'"
                "/ws"
                {"MY_VAR" "hello" "OTHER" "x"})]
      (is (some #{"-e"} argv))
      (is (some #{"MY_VAR=hello"} argv))
      (is (some #{"OTHER=x"} argv))
      ;; Image is followed by sh -c cmd; verify the order.
      (let [tail (drop-while #(not= "node:18" %) argv)]
        (is (= "node:18" (first tail)))
        (is (= "sh" (second tail)))
        (is (= "-c" (nth tail 2)))))))

(deftest docker-args-extra-args-test
  (testing "extra docker-args from the agent block get spliced in"
    (let [argv (ad/build-docker-args
                {:image "alpine" :extra-args "--network host --cpus 2"}
                "uname -a"
                "/ws"
                nil)]
      (is (some #{"--network"} argv))
      (is (some #{"host"} argv))
      (is (some #{"--cpus"} argv))
      (is (some #{"2"} argv)))))

;; ---------------------------------------------------------------------------
;; Docker integration — actually runs a container
;; ---------------------------------------------------------------------------

(defn- docker-available? []
  (try
    (let [{:keys [exit]} (sh/sh "docker" "version" "--format" "ok")]
      (zero? exit))
    (catch Exception _ false)))

(deftest ^:docker-integration docker-real-execution-test
  (when (docker-available?)
    (testing "with :active-agent docker, sh runs inside a container"
      (let [d (ad/make {:execute? true})
            step {:type :jenkins/sh :script "echo from-container"}
            ctx {:cwd (System/getProperty "java.io.tmpdir")
                 :active-agent {:docker {:image "alpine:3.20"}}}
            result (d/dispatch d step ctx)
            stdout (->> @(:effects d) (filter #(= :stdout (first %))) first second)]
        (is (= :ok (:status result)))
        (is (= "from-container" stdout))))))

;; ---------------------------------------------------------------------------
;; Timeout enforcement
;; ---------------------------------------------------------------------------

(deftest timeout-kills-runaway-subprocess-test
  (testing "a sh inside `timeout` gets killed when it overruns the deadline"
    (let [d (ad/make {:execute? true})
          ;; timeout 200ms; sleep 5 should be killed at ~200ms
          step {:type :jenkins/timeout
                :time 200
                :unit "MILLISECONDS"
                :body [{:type :jenkins/sh :script "sleep 5; echo never-printed"}]}
          t0 (System/currentTimeMillis)
          result (d/dispatch d step {})
          elapsed (- (System/currentTimeMillis) t0)]
      (is (< elapsed 3000)
          (str "subprocess was killed before sleep 5 elapsed; got " elapsed "ms"))
      (let [stdout-lines (->> @(:effects d) (filter #(= :stdout (first %))) (mapv second))]
        (is (not (some #{"never-printed"} stdout-lines)))))))

(deftest timeout-allows-fast-subprocess-test
  (testing "a sh that finishes inside the deadline runs normally"
    (let [d (ad/make {:execute? true})
          step {:type :jenkins/timeout
                :time 5000
                :unit "MILLISECONDS"
                :body [{:type :jenkins/sh :script "echo fast"}]}
          result (d/dispatch d step {})
          stdout (->> @(:effects d) (filter #(= :stdout (first %))) first second)]
      (is (= :ok (:status result)))
      (is (= "fast" stdout)))))

;; ---------------------------------------------------------------------------
;; Credential masking end-to-end
;;
;; The :secrets atom on the dispatcher holds strings to redact from
;; every logged effect. h-with-credentials populates it; for this
;; test we set it directly so we don't have to wire a credential
;; resolver.
;; ---------------------------------------------------------------------------

(deftest credential-masking-end-to-end-with-real-exec-test
  (testing "a secret value echoed by a real subprocess shows up as **** in the
            stored console log — not the raw secret"
    (let [d (ad/make {:execute? true})
          _ (reset! (:secrets d) #{"super-secret-token-xyz"})
          step {:type :jenkins/sh :script "echo super-secret-token-xyz"}
          result (d/dispatch d step {})
          evs @(:effects d)
          stdout-line (->> evs (filter #(= :stdout (first %))) first second)]
      (is (= :ok (:status result)))
      ;; The line stored in :effects is masked — not the raw secret.
      (is (= "****" stdout-line)
          "the recorded stdout line is fully redacted")
      ;; And nowhere in any effect should the raw secret appear.
      (is (not (some (fn [ev]
                       (str/includes? (pr-str ev) "super-secret-token-xyz"))
                     evs))
          "the raw secret is NOT present anywhere in the effects log"))))

(deftest credential-masking-substring-test
  (testing "a secret that's a substring of a longer output still gets masked"
    (let [d (ad/make {:execute? true})
          _ (reset! (:secrets d) #{"abc123"})
          step {:type :jenkins/sh :script "echo prefix-abc123-suffix"}
          _ (d/dispatch d step {})
          stdout-line (->> @(:effects d) (filter #(= :stdout (first %))) first second)]
      (is (= "prefix-****-suffix" stdout-line)))))