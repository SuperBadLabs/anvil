(ns anvil.compat.jenkins.file-credentials-test
  "AN7-3 — :file-type credential support.

   Tests verify:
   1. build-docker-args appends -v :ro flags for file-mounts
   2. :file cred resolves → file-mounts + env-var → container-path
   3. :file credentials do NOT get values added to the secrets masker
   4. :credential-unresolved is still emitted when :file cred is missing
   5. :file-credential/mounted effect is emitted on resolution
   6. AN7-3 fix: translator → dispatcher integration — a real
      withCredentials([file(...)]) Jenkinsfile parsed through the
      translator and threaded through h-with-credentials produces a
      ctx with :file-mounts AND :env populated, and the inline-docker
      argv emitted by backend-wiring contains the -v ...:ro flag."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as dispatcher]
            [anvil.compat.jenkins.translator :as translator]
            [anvil.compat.jenkins.backend-wiring :as backend-wiring]
            [anvil.storage.db :as db]
            [anvil.storage.credentials :as creds])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(def ^:dynamic *db-dir* nil)

(use-fixtures :each
  (fn [f]
    (let [d (temp-dir "anvil-file-creds-test-")
          path (str d "/test.db")]
      (binding [*db-dir* d]
        (try
          (db/init! path)
          (f)
          (finally
            (db/close!)
            (rm-rf d)))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- effects [disp]
  @(:effects disp))

(defn- find-effects [disp kw]
  (filterv #(= kw (first %)) (effects disp)))

(defn- find-effect [disp kw]
  (first (find-effects disp kw)))

;; ---------------------------------------------------------------------------
;; build-docker-args — pure function tests (no DB / dispatcher needed)
;; ---------------------------------------------------------------------------

(deftest build-docker-args-no-file-mounts
  (testing "without file-mounts, only workspace -v flag is present"
    (let [argv (dispatcher/build-docker-args
                {:image "eclipse-temurin:21" :extra-args nil}
                "gpg --import $KEY"
                "/workspace"
                {})]
      (is (vector? argv))
      (is (some #{"eclipse-temurin:21"} argv))
      (let [v-pairs (->> (partition 2 1 argv)
                         (filter #(= "-v" (first %)))
                         (mapv second))]
        (is (= 1 (count v-pairs)) "exactly one -v flag (workspace)")
        (is (re-find #"/workspace:/workspace" (first v-pairs)))))))

(deftest build-docker-args-with-file-mounts
  (testing "file-mounts add -v :ro flags to docker args"
    (let [mounts [{:host-path "/run/secrets/keyring.asc"
                   :container-path "/anvil-creds/jkube-gpg-key"}
                  {:host-path "/run/secrets/tls.pem"
                   :container-path "/anvil-creds/tls-cert"}]
          argv (dispatcher/build-docker-args
                {:image "eclipse-temurin:21" :extra-args nil}
                "gpg --import $KEY"
                "/workspace"
                {}
                mounts)]
      (is (vector? argv))
      (let [v-pairs (->> (partition 2 1 argv)
                         (filter #(= "-v" (first %)))
                         (mapv second))]
        (is (= 3 (count v-pairs)) "workspace + 2 file mounts = 3 -v flags")
        (is (some #(= "/run/secrets/keyring.asc:/anvil-creds/jkube-gpg-key:ro" %) v-pairs)
            "file mount has :ro suffix")
        (is (some #(= "/run/secrets/tls.pem:/anvil-creds/tls-cert:ro" %) v-pairs))))))

(deftest build-docker-args-nil-file-mounts-same-as-no-arg
  (testing "nil file-mounts is equivalent to omitting the argument"
    (let [a (dispatcher/build-docker-args
             {:image "img:latest" :extra-args nil}
             "echo hi" "/ws" {} nil)
          b (dispatcher/build-docker-args
             {:image "img:latest" :extra-args nil}
             "echo hi" "/ws" {})]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; :file credential resolved → :file-credential/mounted effect
;; ---------------------------------------------------------------------------

(deftest file-credential-resolved-emits-mount-effect
  (testing ":file cred resolves from store → :file-credential/mounted effect emitted"
    ;; Write a real temp file to use as the credential path.
    (let [tmp (java.io.File/createTempFile "anvil-gpg-test" ".asc")]
      (try
        (spit tmp "GPG KEY DATA")
        (creds/add! {:id "jkube-gpg-key"
                     :type :file
                     :value (str tmp)
                     :description "test gpg key"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'jkube-gpg-key', variable: 'GPG_KEY_FILE'"}]
                    :body [{:type :jenkins/sh :script "gpg --import \"$GPG_KEY_FILE\""}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})]
          ;; :credential-unresolved must NOT be emitted
          (is (empty? (find-effects disp :credential-unresolved))
              "resolved :file cred must NOT emit :credential-unresolved")
          ;; :file-credential/mounted MUST be emitted
          (let [mount-eff (find-effect disp :file-credential/mounted)]
            (is (some? mount-eff) ":file-credential/mounted effect must be emitted")
            (is (= "jkube-gpg-key" (-> mount-eff second :credential-id)))
            (is (= (str tmp) (-> mount-eff second :host-path))
                "host-path in effect matches what was registered")
            (is (= "/anvil-creds/jkube-gpg-key" (-> mount-eff second :container-path))
                "container path is /anvil-creds/<credential-id>")
            (is (= "GPG_KEY_FILE" (-> mount-eff second :var-name))
                "var-name matches the `variable:` binding")))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; :file credential unresolved → :credential-unresolved (AN4-4)
;; ---------------------------------------------------------------------------

(deftest file-credential-unresolved-emits-credential-unresolved
  (testing ":file cred missing from store → :credential-unresolved per AN4-4"
    ;; Do NOT add the credential; store is empty after fixture setup.
    (let [disp (dispatcher/make)
          step {:type :jenkins/with-credentials
                :credentials [{:kind "file"
                               :raw-args "credentialsId: 'missing-key', variable: 'KEY'"}]
                :body []}
          _r (d/dispatch disp step {})]
      (let [unresolved (find-effects disp :credential-unresolved)]
        (is (= 1 (count unresolved)))
        (is (= "missing-key" (-> unresolved first second :credential-id)))))))

;; ---------------------------------------------------------------------------
;; :file credential host-path NOT in secrets masker
;; ---------------------------------------------------------------------------

(deftest file-credential-host-path-not-in-secrets-masker
  (testing ":file cred host-path is NOT added to the secrets masker (it's a path)"
    (let [tmp (java.io.File/createTempFile "anvil-mask-test" ".key")]
      (try
        (spit tmp "KEY DATA")
        (creds/add! {:id "test-key"
                     :type :file
                     :value (str tmp)
                     :description "test"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'test-key', variable: 'KEY_FILE'"}]
                    :body [{:type :jenkins/sh :script "echo done"}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})]
          (is (not (contains? @(:secrets disp) (str tmp)))
              "host file path must NOT be in the secrets masker"))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; :file credential enter-effect reports file-credential-count
;; ---------------------------------------------------------------------------

(deftest file-credential-enter-effect-counts-file-creds
  (testing ":with-credentials/enter effect includes :file-credential-count"
    (let [tmp (java.io.File/createTempFile "anvil-enter-test" ".pem")]
      (try
        (spit tmp "TLS CERT DATA")
        (creds/add! {:id "my-cert"
                     :type :file
                     :value (str tmp)
                     :description "test cert"})
        (let [disp (dispatcher/make)
              step {:type :jenkins/with-credentials
                    :credentials [{:kind "file"
                                   :raw-args "credentialsId: 'my-cert', variable: 'TLS_CERT'"}]
                    :body [{:type :jenkins/sh :script "cat $TLS_CERT"}]}
              _r (d/dispatch disp step {:env {} :cwd "/ws"})
              enter-eff (find-effect disp :with-credentials/enter)]
          (is (some? enter-eff) ":with-credentials/enter must be emitted")
          (is (= 1 (-> enter-eff second :file-credential-count))
              "enter effect reports 1 file credential mounted"))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; Translator → dispatcher integration
;;
;; Before this PR the file-credentials tests only exercised the dispatcher
;; with a hand-rolled `:raw-args "credentialsId: 'X', variable: 'Y'"`
;; string, which happened to match the regex in
;; `credential-var-bindings`. The real translator emits a vector-of-maps
;; shape: `[{:credentialsId "X" :variable "Y"}]`. The (str ...) of that
;; vector still matches the regex by coincidence, but no test exercised
;; the end-to-end path — so the AN7-3 regression where the docker
;; backend dropped file-mounts went unnoticed until the eclipse-jkube
;; dogfood (PR #93).
;;
;; These tests close that gap:
;;   - parse real Groovy `withCredentials([file(...)])` through the
;;     translator, locate the :jenkins/with-credentials node
;;   - dispatch it through h-with-credentials with a docker
;;     active-agent in ctx
;;   - assert :file-mounts AND :env land in ctx with the right
;;     credential-id / variable binding
;;   - assert backend-wiring's inline-docker argv carries the
;;     `-v host:container:ro` flag
;; ---------------------------------------------------------------------------

(defn- find-with-credentials [ir]
  (let [search (fn search [node]
                 (cond
                   (and (map? node)
                        (= :jenkins/with-credentials (:type node)))
                   node
                   (map? node)       (some search (vals node))
                   (sequential? node) (some search node)
                   :else nil))]
    (search ir)))

(defn- capture-body-ctx
  "Dispatch `wc-step` with `outer-ctx`, capturing the ctx that
   h-with-credentials threads through to the body. The capture works by
   redef'ing the private h-sh handler to snapshot ctx + return :ok
   without subprocess work. wc-step's body must contain a single sh
   step (we don't care about its script)."
  [disp wc-step outer-ctx]
  (let [captured (atom nil)
        h-sh-var (resolve 'anvil.compat.jenkins.dispatcher/h-sh)]
    (with-redefs-fn {h-sh-var (fn [_d _s ctx]
                                (reset! captured ctx)
                                {:status :ok :ctx ctx})}
      #(d/dispatch disp wc-step outer-ctx))
    @captured))

(deftest translator-to-dispatcher-file-cred-end-to-end
  (testing "real Groovy withCredentials([file(...)]) parsed → dispatched → ctx has :file-mounts + :env binding"
    (let [tmp (java.io.File/createTempFile "anvil-e2e-gpg" ".asc")]
      (try
        (spit tmp "GPG KEY DATA")
        (creds/add! {:id "jkube-gpg-key"
                     :type :file
                     :value (str tmp)
                     :description "test gpg key"})
        (let [jenkinsfile (str "pipeline {"
                               "  agent { docker { image 'eclipse-temurin:21-jdk' } }"
                               "  stages {"
                               "    stage('s') {"
                               "      steps {"
                               "        withCredentials([file(credentialsId: 'jkube-gpg-key', "
                               "                              variable: 'GPG_KEY_FILE')]) {"
                               "          sh 'echo done'"
                               "        }"
                               "      }"
                               "    }"
                               "  }"
                               "}")
              ir (translator/parse jenkinsfile)
              wc (find-with-credentials ir)]
          (is (some? wc) "translator must emit a :jenkins/with-credentials node")
          (is (= "file" (-> wc :credentials first :kind))
              "translator must tag the credential as :kind \"file\"")
          ;; Sanity-check the translator output shape — the regression
          ;; this test catches is the dispatcher silently dropping
          ;; the (vec-of-map) :raw-args shape.
          (let [raw (-> wc :credentials first :raw-args)]
            (is (vector? raw)
                "translator emits :raw-args as a vector (not a string)")
            (is (= "jkube-gpg-key" (-> raw first :credentialsId))
                "translator preserves credentialsId verbatim from Groovy named arg")
            (is (= "GPG_KEY_FILE" (-> raw first :variable))
                "translator preserves variable verbatim from Groovy named arg"))
          (let [disp (dispatcher/make)
                outer-ctx {:env {} :cwd "/ws"
                           :active-agent {:docker {:image "eclipse-temurin:21-jdk"}}}
                inner-ctx (capture-body-ctx disp wc outer-ctx)]
            (is (some? inner-ctx) "body must execute and snapshot ctx")
            (is (seq (:file-mounts inner-ctx))
                "ctx :file-mounts must be populated for the file credential")
            (let [mount (first (:file-mounts inner-ctx))]
              (is (= "jkube-gpg-key" (:credential-id mount)))
              (is (= (str tmp)        (:host-path mount)))
              (is (= "/anvil-creds/jkube-gpg-key" (:container-path mount)))
              (is (= "GPG_KEY_FILE"  (:var-name mount))))
            (is (= "/anvil-creds/jkube-gpg-key"
                   (get-in inner-ctx [:env "GPG_KEY_FILE"]))
                ":env binding must point at the container path under docker")))
        (finally (.delete tmp))))))

(deftest translator-to-dispatcher-non-docker-binds-host-path
  (testing "without a docker active-agent the env var binds the HOST path (not /anvil-creds/...)"
    (let [tmp (java.io.File/createTempFile "anvil-e2e-host" ".pem")]
      (try
        (spit tmp "CERT DATA")
        (creds/add! {:id "host-cert" :type :file :value (str tmp) :description "x"})
        (let [jenkinsfile (str "pipeline { agent any stages { stage('s') { steps {"
                               "  withCredentials([file(credentialsId: 'host-cert', variable: 'TLS_CERT')]) {"
                               "    sh 'true'"
                               "  } } } } }")
              wc (find-with-credentials (translator/parse jenkinsfile))
              disp (dispatcher/make)
              inner-ctx (capture-body-ctx disp wc {:env {} :cwd "/ws"})]
          (is (= (str tmp) (get-in inner-ctx [:env "TLS_CERT"]))
              "non-docker agent → env var binds host filesystem path"))
        (finally (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; backend-wiring inline-docker argv carries the -v ...:ro flag
;;
;; This is the bug PR #93 documented: chengis-core 0.3.0's DockerBackend
;; ignores `:extra-args`, so the file-mount -v flags vanish between
;; backend-for-ctx and `docker run`. The fix routes file-mount-bearing
;; steps through `build-inline-docker-argv` which emits the flag
;; directly.
;; ---------------------------------------------------------------------------

(deftest backend-wiring-inline-docker-includes-file-mount-flag
  (testing "build-inline-docker-argv emits -v host:container:ro for each file-mount"
    (let [argv (backend-wiring/build-inline-docker-argv
                {:image "eclipse-temurin:21-jdk" :extra-args nil}
                "/workspace"
                "gpg --import \"$GPG_KEY_FILE\""
                {"GPG_KEY_FILE" "/anvil-creds/jkube-gpg-key"}
                [{:host-path "/host/keys/keyring.asc"
                  :container-path "/anvil-creds/jkube-gpg-key"
                  :credential-id "jkube-gpg-key"
                  :var-name "GPG_KEY_FILE"}])]
      (is (some #{"docker"} argv) "docker is the first executable")
      (is (some #{"run"} argv))
      (is (some #{"eclipse-temurin:21-jdk"} argv) "image is in argv")
      (let [v-pairs (->> (partition 2 1 argv)
                         (filter #(= "-v" (first %)))
                         (mapv second))]
        (is (some #(= "/host/keys/keyring.asc:/anvil-creds/jkube-gpg-key:ro" %) v-pairs)
            "file mount -v flag is present with :ro suffix"))
      (let [e-pairs (->> (partition 2 1 argv)
                         (filter #(= "-e" (first %)))
                         (mapv second))]
        (is (some #(= "GPG_KEY_FILE=/anvil-creds/jkube-gpg-key" %) e-pairs)
            "env binding makes it through as -e flag")))))

(deftest backend-wiring-inline-docker-honors-cwd-workdir
  (testing "build-inline-docker-argv -w honors workdir distinct from workspace (Jenkins dir())"
    (let [argv (backend-wiring/build-inline-docker-argv
                {:image "alpine:3" :extra-args nil}
                "/workspace"        ; bind-mount root
                "/workspace/subdir" ; workdir — set by dir('subdir')
                "pwd"
                {}
                [])
          w-flag-idx (.indexOf ^java.util.List argv "-w")
          v-pairs (->> (partition 2 1 argv)
                       (filter #(= "-v" (first %)))
                       (mapv second))]
      (is (pos? w-flag-idx) "-w flag is present")
      (is (= "/workspace/subdir" (nth argv (inc w-flag-idx)))
          "-w value is the workdir, not the workspace root")
      (is (some #(= "/workspace:/workspace" %) v-pairs)
          "bind mount still anchored at workspace root, not workdir")))
  (testing "4-arity (no workdir) defaults -w to workspace for back-compat"
    (let [argv (backend-wiring/build-inline-docker-argv
                {:image "alpine:3" :extra-args nil}
                "/workspace" "pwd" {} [])
          w-flag-idx (.indexOf ^java.util.List argv "-w")]
      (is (= "/workspace" (nth argv (inc w-flag-idx)))
          "without workdir, -w falls back to workspace"))))
