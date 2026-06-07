(ns anvil.cli.ai-test
  "v0.4.1 T3.3 — argv validation + dispatch tests for the new
   `anvil init / explain / optimize` CLI commands.

   Cannot hit the real Anthropic API in CI; instead we verify the
   shell of each command: missing-argument error paths, --help,
   not-found path on explain/optimize.  The happy-path call into
   anvil.ai.client is covered by client_test.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [anvil.cli.ai :as ai-cli]
            [anvil.cli.core :as cli-core]))

(defn- with-captured-output
  "Run f, capture stdout + stderr + exit code.  Returns
   {:out :err :exit}."
  [f]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out
              *err* err]
      (let [exit (try (f) (catch Throwable e
                            (.printStackTrace e err)
                            99))]
        {:out (str out) :err (str err) :exit exit}))))

;; ---------------------------------------------------------------------------
;; explain — argv validation
;; ---------------------------------------------------------------------------

(deftest explain-without-args-prints-usage-and-fails
  (let [{:keys [exit err]} (with-captured-output #(ai-cli/run-explain []))]
    (is (= 2 exit) "exit 2 = bad usage")
    (is (str/includes? err "needs a path")
        "operator-actionable error names what's missing")))

(deftest explain-with-nonexistent-file-fails-cleanly
  (let [{:keys [exit err]} (with-captured-output
                             #(ai-cli/run-explain ["/tmp/does-not-exist-anvil-test"]))]
    (is (= 2 exit))
    (is (str/includes? err "not found")
        "filesystem error surfaced before any API call")))

(deftest explain-help-exits-zero
  (let [{:keys [exit out]} (with-captured-output #(ai-cli/run-explain ["--help"]))]
    (is (= 0 exit))
    (is (str/includes? out "Usage: anvil explain"))
    (is (str/includes? out "--model"))
    (is (str/includes? out "--no-stream"))))

;; ---------------------------------------------------------------------------
;; optimize — same argv-validation shape
;; ---------------------------------------------------------------------------

(deftest optimize-without-args-prints-usage-and-fails
  (let [{:keys [exit err]} (with-captured-output #(ai-cli/run-optimize []))]
    (is (= 2 exit))
    (is (str/includes? err "needs a path"))))

(deftest optimize-with-nonexistent-file-fails-cleanly
  (let [{:keys [exit err]} (with-captured-output
                             #(ai-cli/run-optimize ["/tmp/does-not-exist-anvil-test"]))]
    (is (= 2 exit))
    (is (str/includes? err "not found"))))

(deftest optimize-help-exits-zero
  (let [{:keys [exit out]} (with-captured-output #(ai-cli/run-optimize ["--help"]))]
    (is (= 0 exit))
    (is (str/includes? out "Usage: anvil optimize"))))

;; ---------------------------------------------------------------------------
;; init — --help + --print mode (no file write)
;; ---------------------------------------------------------------------------

(deftest init-help-exits-zero
  (let [{:keys [exit out]} (with-captured-output #(ai-cli/run-init ["--help"]))]
    (is (= 0 exit))
    (is (str/includes? out "Usage: anvil init"))
    (is (str/includes? out "--out"))
    (is (str/includes? out "--force"))
    (is (str/includes? out "--print"))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch — confirm the three commands route correctly
;; ---------------------------------------------------------------------------

(deftest dispatch-help-mentions-ai-commands
  (testing "top-level usage lists the three v0.4.1 AI commands"
    (let [{:keys [out]} (with-captured-output #(cli-core/dispatch []))]
      (is (str/includes? out "init"))
      (is (str/includes? out "explain"))
      (is (str/includes? out "optimize"))
      (is (str/includes? out "ANTHROPIC_API_KEY")
          "operator sees the prereq up front"))))

(deftest dispatch-routes-explain-to-ai-cli
  (testing "anvil explain → ai-cli/run-explain (verified via the missing-arg path)"
    (let [{:keys [exit err]} (with-captured-output
                               #(cli-core/dispatch ["explain"]))]
      (is (= 2 exit))
      (is (str/includes? err "needs a path")))))

(deftest dispatch-routes-optimize-to-ai-cli
  (let [{:keys [exit err]} (with-captured-output
                             #(cli-core/dispatch ["optimize"]))]
    (is (= 2 exit))
    (is (str/includes? err "needs a path"))))

(deftest dispatch-routes-init-to-ai-cli
  (testing "anvil init --help routes through dispatch + lands on ai-cli"
    (let [{:keys [exit out]} (with-captured-output
                               #(cli-core/dispatch ["init" "--help"]))]
      (is (= 0 exit))
      (is (str/includes? out "Usage: anvil init")))))

;; ---------------------------------------------------------------------------
;; Argv parsing — verify --model + --no-stream propagate
;; ---------------------------------------------------------------------------

(deftest explain-flags-parse-cleanly
  (testing "--model + --no-stream + a file path are all accepted"
    (let [tmp (fs/create-temp-file {:suffix ".Jenkinsfile"})
          path (str tmp)]
      (try
        (spit (fs/file path) "pipeline { agent any }")
        ;; No real API call; the env doesn't have ANTHROPIC_API_KEY in
        ;; CI so the call will throw and the error-trap will catch it,
        ;; producing exit 2.  The point is to confirm argv parses
        ;; without an :error key from tools.cli.
        (let [{:keys [exit err out]} (with-captured-output
                                       #(ai-cli/run-explain
                                         ["--model" "claude-haiku-4-5"
                                          "--no-stream"
                                          path]))]
          (is (#{0 2 4} exit)
              "valid flags don't produce parse error (exit 2 here would
               be from the API call itself failing in CI, not argv)")
          (is (not (re-find #"Unknown option" (str err out)))
              "--model and --no-stream are recognized flags"))
        (finally (fs/delete-if-exists tmp))))))
