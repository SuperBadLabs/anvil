(ns anvil.provenance.cosign
  "v0.4.1 T4.2 + T4.6 — wrapper around the `cosign` CLI for signing
   and verifying in-toto attestations.

   Per **AV4-5** + risk **R9** (board): we shell out to host `cosign`
   instead of bundling a sigstore Java client.  Rationale:
     - Java sigstore-client is heavy (~5 MB+ on uberjar; budget is tight)
     - operators can independently `cosign verify` attestations without anvil
     - cosign is the canonical sigstore tool — version skew issues stay
       inside one well-defined binary, not a transitive dep graph

   Two key flows:

   1. **Sign** (`sign-blob!`) — feed cosign a JSON statement file,
      get back an .intoto.jsonl envelope that contains:
        - the original statement (base64'd)
        - a sigstore signature (Fulcio cert + Rekor entry, or local key)

   2. **Verify** (`verify-blob`) — feed cosign an artifact + its
      sibling .intoto.jsonl, get back exit 0 if the signature matches.

   Key sourcing (per R4 — offline / air-gapped fallback):
     - default: Fulcio keyless flow.  Needs OIDC identity token in
       `COSIGN_IDENTITY_TOKEN` env (anvil doesn't manage OIDC — the
       operator's environment provides it, e.g. CI workload identity).
     - fallback: `{:key-path PATH}` → uses operator-managed long-lived
       key.  Documented trade-off in docs/provenance/offline-key-fallback.md.

   When cosign is not on PATH, every operation throws ex-info with
   `:reason :cosign-missing` so the dispatcher (T4.3) can record an
   honest `[:provenance/degraded :cosign-missing]` effect rather than
   silently skipping signing."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; cosign availability
;; ---------------------------------------------------------------------------

(defn cosign-on-path?
  "Returns true if `cosign` is invokable on the current PATH.  Cached
   per-process (the answer doesn't change at runtime) — first call
   runs `cosign version`, subsequent calls hit the cache."
  []
  (try
    (let [{:keys [exit]} (sh/sh "cosign" "version")]
      (zero? exit))
    (catch java.io.IOException _ false)))

(defn cosign-version
  "Returns the cosign version string (e.g. 'v3.0.6') or nil if cosign
   is missing.  Surfaced in the :provenance/attested effect so the
   build receipt names which signer produced the attestation."
  []
  (when (cosign-on-path?)
    (try
      (let [{:keys [out]} (sh/sh "cosign" "version")
            line (->> (str/split-lines out)
                      (some #(when (re-find #"GitVersion:" %) %)))]
        (when line
          (-> line
              (str/replace "GitVersion:" "")
              str/trim)))
      (catch Throwable _ nil))))

(defn- require-cosign! []
  (when-not (cosign-on-path?)
    (throw (ex-info "cosign not found on PATH"
                    {:reason :cosign-missing
                     :fix    "Install cosign from https://github.com/sigstore/cosign/releases — anvil shells out to it per AV4-5 (R9: bundling sigstore Java would bloat the uberjar)."}))))

;; ---------------------------------------------------------------------------
;; Sign — produce the .intoto.jsonl
;; ---------------------------------------------------------------------------

(defn sign-statement!
  "Sign an in-toto statement (the map returned by
   anvil.provenance.statement/build-statement) and write the resulting
   bundle as `out-path`.

   Required opts:
     :out-path PATH  — where to write the bundle (typically <artifact>.intoto.jsonl)
     :artifact PATH  — the file being attested (cosign needs it to bind the
                       attestation to the actual blob's digest)

   Optional opts:
     :key-path PATH  — long-lived signing key (offline / air-gapped flow).
                       When absent: Fulcio keyless flow (needs
                       COSIGN_IDENTITY_TOKEN env).
     :predicate-type — defaults to slsaprovenance1; override only if you
                       know what you're doing
     :extra-env      — map of extra env vars to set for the cosign call
                       (e.g. {\"COSIGN_PASSWORD\" \"…\"} for a passworded key)

   Returns {:exit-code 0 :stdout … :stderr …} on success;
   throws ex-info with :reason on failure (cosign error, missing
   binary, etc).  Operators inspect the .intoto.jsonl bundle file
   directly — anvil doesn't re-parse cosign's output here."
  [statement {:keys [out-path artifact key-path predicate-type extra-env]
              :or {predicate-type "slsaprovenance1"}}]
  (require-cosign!)
  (when-not (and out-path artifact)
    (throw (ex-info ":out-path and :artifact are required"
                    {:out-path out-path :artifact artifact})))
  (when-not (.exists (io/file artifact))
    (throw (ex-info (str "Artifact not found: " artifact)
                    {:reason :artifact-missing :artifact artifact})))
  ;; Write the predicate to a temp file — cosign sign-blob --predicate
  ;; reads from disk, not stdin.  The temp file lives only for the
  ;; duration of the cosign call.  Using cosign v3's `--bundle` format
  ;; (--output-attestation is deprecated as of cosign v3.0).
  (let [tmp (java.io.File/createTempFile "anvil-provenance-" ".json")]
    (try
      (spit tmp (json/write-str statement))
      (let [base-args ["cosign" "attest-blob"
                       "--yes"
                       "--predicate" (.getAbsolutePath tmp)
                       "--type" predicate-type
                       "--bundle" out-path
                       artifact]
            args (cond-> base-args
                   key-path (concat ["--key" key-path]))
            env-overrides (or extra-env {})
            ;; sh/sh accepts :env as a map (or String[]); we pass the
            ;; merged map directly.  Merging over System/getenv so PATH
            ;; + HOME + (for Fulcio) COSIGN_IDENTITY_TOKEN survive.
            merged-env (merge (into {} (System/getenv)) env-overrides)
            result (apply sh/sh (concat args [:env merged-env]))]
        (if (zero? (:exit result))
          {:exit-code 0
           :stdout (:out result)
           :stderr (:err result)
           :out-path out-path
           :cosign-version (cosign-version)}
          (throw (ex-info (str "cosign attest-blob failed (exit "
                               (:exit result) ")")
                          {:reason :cosign-sign-failed
                           :exit (:exit result)
                           :stdout (:out result)
                           :stderr (:err result)}))))
      (finally
        (.delete tmp)))))

;; ---------------------------------------------------------------------------
;; Verify
;; ---------------------------------------------------------------------------

(defn verify-blob
  "Verify a sigstore-signed in-toto attestation against the artifact.

   Required opts:
     :artifact         PATH — the file being verified
     :attestation-path PATH — the .intoto.jsonl bundle written by sign

   For Fulcio keyless verification (default), `:certificate-identity`
   and `:certificate-oidc-issuer` are required by cosign — the operator
   either provides them in `opts` or, more typically, in their cosign
   config.  For long-lived key verification, pass `:key-path`.

   Returns:
     {:verified? true  :stdout … :stderr …}  — exit 0 from cosign
     {:verified? false :stdout … :stderr …}  — non-zero (mismatch/tampered)

   Throws ex-info :reason :cosign-missing if cosign isn't on PATH —
   the `anvil provenance verify` CLI catches this and prints an
   actionable install hint instead of a stack trace."
  [{:keys [artifact attestation-path key-path
           certificate-identity certificate-oidc-issuer]}]
  (require-cosign!)
  (when-not (and artifact attestation-path)
    (throw (ex-info ":artifact and :attestation-path are required"
                    {:artifact artifact :attestation-path attestation-path})))
  (when-not (.exists (io/file artifact))
    (throw (ex-info (str "Artifact not found: " artifact)
                    {:reason :artifact-missing :artifact artifact})))
  (when-not (.exists (io/file attestation-path))
    (throw (ex-info (str "Attestation not found: " attestation-path)
                    {:reason :attestation-missing :attestation attestation-path})))
  (let [base-args ["cosign" "verify-blob-attestation"
                   "--type" "slsaprovenance1"
                   "--check-claims=true"
                   "--new-bundle-format"
                   "--bundle" attestation-path]
        args (cond-> base-args
               key-path                 (concat ["--key" key-path])
               certificate-identity     (concat ["--certificate-identity" certificate-identity])
               certificate-oidc-issuer  (concat ["--certificate-oidc-issuer" certificate-oidc-issuer])
               true                     (concat [artifact]))
        result (apply sh/sh args)]
    {:verified? (zero? (:exit result))
     :exit-code (:exit result)
     :stdout (:out result)
     :stderr (:err result)
     :cosign-version (cosign-version)}))

(defn sibling-attestation-path
  "Convention: an artifact at `/path/foo.jar` has its attestation at
   `/path/foo.jar.intoto.jsonl`.  The verify CLI uses this when the
   operator names the artifact (not the attestation) on the cmd line."
  [artifact-path]
  (str artifact-path ".intoto.jsonl"))
