(ns anvil.cli.provenance-test
  "v0.4.1 T4.6 — CLI argv + dispatch tests for `anvil provenance verify`.
   The real verify path is exercised end-to-end in cosign-test's
   integration suite; here we cover the argv shell + error UX."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [anvil.cli.provenance :as prov-cli]
            [anvil.cli.core :as cli-core]))

(defn- with-captured-output
  [f]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [exit (try (f) (catch Throwable e (.printStackTrace e err) 99))]
        {:out (str out) :err (str err) :exit exit}))))

(deftest verify-without-args-prints-usage-and-fails
  (let [{:keys [exit err]} (with-captured-output #(prov-cli/run-verify []))]
    (is (= 2 exit))
    (is (str/includes? err "needs a path"))))

(deftest verify-help-exits-zero
  (let [{:keys [exit out]} (with-captured-output #(prov-cli/run-verify ["--help"]))]
    (is (= 0 exit))
    (is (str/includes? out "Usage: anvil provenance verify"))
    (is (str/includes? out "--key"))
    (is (str/includes? out "--certificate-identity"))
    (is (str/includes? out "--certificate-oidc-issuer"))))

(deftest verify-missing-artifact-fails-cleanly
  (let [{:keys [exit err]} (with-captured-output
                             #(prov-cli/run-verify ["/tmp/does-not-exist-anvil"]))]
    (is (= 2 exit))
    (is (str/includes? err "artifact not found"))))

(deftest verify-missing-attestation-fails-cleanly
  (let [tmp (fs/create-temp-file {:suffix ".jar"})]
    (try
      (spit (fs/file tmp) "fake")
      ;; No sibling .intoto.jsonl exists → operator-actionable error
      (let [{:keys [exit err]} (with-captured-output
                                 #(prov-cli/run-verify [(str tmp)]))]
        (is (= 2 exit))
        (is (str/includes? err "attestation not found"))
        (is (str/includes? err "expected sibling")
            "operator told WHERE we looked, not just THAT we didn't find it"))
      (finally (fs/delete-if-exists tmp)))))

(deftest provenance-subcommand-help
  (let [{:keys [exit out]} (with-captured-output #(prov-cli/run []))]
    (is (= 0 exit))
    (is (str/includes? out "Usage: anvil provenance"))
    (is (str/includes? out "verify"))))

(deftest provenance-unknown-subcommand-fails
  (let [{:keys [exit err]} (with-captured-output #(prov-cli/run ["bogus"]))]
    (is (= 3 exit))
    (is (str/includes? err "unknown provenance subcommand"))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch
;; ---------------------------------------------------------------------------

(deftest dispatch-help-mentions-provenance
  (let [{:keys [out]} (with-captured-output #(cli-core/dispatch []))]
    (is (str/includes? out "provenance verify"))
    (is (str/includes? out "cosign"))))

(deftest dispatch-routes-provenance-verify
  (let [{:keys [exit err]} (with-captured-output
                             #(cli-core/dispatch ["provenance" "verify"]))]
    (is (= 2 exit))
    (is (str/includes? err "needs a path"))))
