(ns anvil.cli.provenance
  "v0.4.1 T4.6 — `anvil provenance verify <artifact>` CLI.

   Operator hands us an artifact path; we look for the sibling
   <artifact>.intoto.jsonl, shell out to cosign verify-blob-attestation,
   and exit 0 (verified) or non-zero with a diagnostic.

   Off-line / air-gapped: pass `--key PATH` to use the operator's
   long-lived signing key (the R4 fallback).  Default uses Fulcio
   keyless verification — operator may need to set
   --certificate-identity + --certificate-oidc-issuer to match
   whoever signed the attestation."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [clojure.java.io :as io]
            [anvil.provenance.cosign :as cosign]))

(def ^:private verify-opts
  [[nil  "--attestation PATH"
    "Explicit attestation path (default: <artifact>.intoto.jsonl)"]
   [nil  "--key PATH"
    "Long-lived public key for verification (R4 air-gapped flow)"]
   [nil  "--certificate-identity IDENTITY"
    "Expected signer identity for Fulcio verification (email or URI)"]
   [nil  "--certificate-oidc-issuer ISSUER"
    "Expected OIDC issuer URL for Fulcio verification"]
   ["-h" "--help"]])

(defn- print-result [{:keys [verified? exit-code stdout stderr cosign-version]}]
  (binding [*out* *err*]
    (if verified?
      (do
        (println "✓ Provenance VERIFIED")
        (when cosign-version (println (str "  cosign: " cosign-version)))
        (when (seq stdout) (println stdout)))
      (do
        (println (str "✗ Provenance VERIFICATION FAILED (cosign exit " exit-code ")"))
        (when (seq stderr)
          (println "--- cosign stderr ---")
          (println stderr)
          (println "---"))
        (println "The attestation does not match this artifact.  Likely causes:")
        (println "  - the artifact was modified after signing")
        (println "  - the attestation is for a different artifact")
        (println "  - the verifying party (identity / OIDC issuer) doesn't match the signer")))
    (flush)))

(defn run-verify
  "anvil provenance verify <artifact> [opts]"
  [argv]
  (let [{:keys [arguments options summary errors]}
        (tools-cli/parse-opts argv verify-opts)]
    (cond
      errors
      (do (binding [*out* *err*]
            (println "ERROR: " (str/join "; " errors))
            (println summary))
          2)

      (:help options)
      (do (println "Usage: anvil provenance verify <artifact> [options]")
          (println summary)
          0)

      (empty? arguments)
      (do (binding [*out* *err*]
            (println "ERROR: anvil provenance verify needs a path to an artifact")
            (println "Usage: anvil provenance verify <artifact> [options]")
            (println summary))
          2)

      :else
      (let [artifact (first arguments)
            attestation (or (:attestation options)
                            (cosign/sibling-attestation-path artifact))]
        (cond
          (not (.exists (io/file artifact)))
          (do (binding [*out* *err*]
                (println (str "ERROR: artifact not found: " artifact)))
              2)

          (not (.exists (io/file attestation)))
          (do (binding [*out* *err*]
                (println (str "ERROR: attestation not found: " attestation))
                (println "       (expected sibling .intoto.jsonl beside the artifact)"))
              2)

          :else
          (try
            (let [result (cosign/verify-blob
                          (cond-> {:artifact artifact
                                   :attestation-path attestation}
                            (:key options) (assoc :key-path (:key options))
                            (:certificate-identity options)
                            (assoc :certificate-identity (:certificate-identity options))
                            (:certificate-oidc-issuer options)
                            (assoc :certificate-oidc-issuer (:certificate-oidc-issuer options))))]
              (print-result result)
              (if (:verified? result) 0 4))
            (catch clojure.lang.ExceptionInfo e
              (let [d (ex-data e)]
                (binding [*out* *err*]
                  (println (str "ERROR: " (ex-message e)))
                  (when (:fix d)
                    (println (str "  fix: " (:fix d))))))
              (case (:reason (ex-data e))
                :cosign-missing 3
                2))))))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch — `anvil provenance <subcommand>`
;; ---------------------------------------------------------------------------

(defn run
  "Dispatcher for the `provenance` subcommand tree."
  [argv]
  (let [[subcmd & rest] argv]
    (case subcmd
      "verify" (run-verify (vec rest))
      ("help" "-h" "--help" nil)
      (do (println "Usage: anvil provenance <subcommand>")
          (println)
          (println "Subcommands:")
          (println "  verify <artifact>    Verify SLSA L3 provenance of an artifact")
          (println)
          (println "Each subcommand accepts --help for its options.")
          0)
      (do (binding [*out* *err*]
            (println (str "ERROR: unknown provenance subcommand: " subcmd))
            (println "Run 'anvil provenance help' for usage."))
          3))))
