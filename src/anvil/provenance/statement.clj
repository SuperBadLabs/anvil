(ns anvil.provenance.statement
  "v0.4.1 T4.1 — in-toto v1 statement builder for SLSA L3 provenance.

   Pure data — takes a build record + artifact metadata, returns a
   map shaped exactly like the in-toto v1 attestation envelope that
   cosign sign-blob --predicate consumes.  No network, no signing,
   no cosign in this ns: the round-trip is:

     build-statement → JSON → cosign sign-blob → .intoto.jsonl on disk

   Shape conformance: github.com/in-toto/attestation/spec/v1
     - _type: \"https://in-toto.io/Statement/v1\"
     - subject: [{name digest:{sha256:<hex>}}, …]
     - predicateType: \"https://slsa.dev/provenance/v1\"
     - predicate: slsa-provenance/v1 shape — buildDefinition + runDetails

   We populate the minimum SLSA L3 fields:
     - builder.id              — anvil's identity + version string
     - buildType               — anvil-pipeline URI
     - externalParameters      — job-name + Jenkinsfile sha
     - resolvedDependencies    — git SCM materials (url + commit sha)
     - metadata.invocationId   — the build's id
     - metadata.startedOn      — build start ISO-8601
     - metadata.finishedOn     — build end ISO-8601

   Operators who need additional fields (e.g. github-actions-style
   triggerInfo) layer them on via the optional `:extras` arg —
   merged into the predicate before serialization.

   Tests live in test/anvil/provenance/statement_test.clj — every
   field is asserted against fixture build records to catch shape
   regressions before cosign rejects the predicate."
  (:require [clojure.string :as str]
            [anvil.version :as v])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

;; ---------------------------------------------------------------------------
;; URIs — per the in-toto + SLSA v1 specs
;; ---------------------------------------------------------------------------

(def ^:const in-toto-statement-type
  "https://in-toto.io/Statement/v1")

(def ^:const slsa-provenance-v1-uri
  "https://slsa.dev/provenance/v1")

(def ^:const anvil-build-type-uri
  "URI naming anvil as the build system that produced this provenance.
   Per SLSA convention this is a namespaced opaque string; the path
   makes it human-greppable in cross-builder attestations."
  "https://anvil.superbadlabs.dev/buildtype/jenkins-pipeline/v1")

(defn builder-id
  "anvil's builder identity, baked from the version string at build
   time.  Verifiers use this to know which builder produced an
   attestation (e.g. 'anvil 0.4.1' vs 'anvil 0.5.0')."
  []
  (str "https://anvil.superbadlabs.dev/builder/" (str/replace v/version " " "-")))

;; ---------------------------------------------------------------------------
;; SHA-256 helpers — used for the subject digest
;; ---------------------------------------------------------------------------

(defn sha256-hex
  "Hex-encoded SHA-256 of a byte array.  Used for subject digests
   and for hashing the Jenkinsfile in externalParameters."
  [^bytes b]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md b)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn sha256-of-string
  "Hex-encoded SHA-256 of a UTF-8 string."
  [^String s]
  (sha256-hex (.getBytes s StandardCharsets/UTF_8)))

(defn sha256-of-file
  "Hex-encoded SHA-256 of a file's bytes.  Used for the subject
   digest of each artifact in the attestation.  Reads the whole
   file into memory — fine for typical CI artifacts (tarballs,
   jars, wheels in the tens of MB); operators with multi-GB
   artifacts should stream, but that's not on the v0.4.1 plate."
  [^String path]
  (let [f (java.io.File. path)]
    (when-not (.exists f)
      (throw (ex-info (str "Artifact not found: " path)
                      {:path path})))
    (sha256-hex (with-open [is (java.io.FileInputStream. f)
                            baos (java.io.ByteArrayOutputStream.)]
                  (.transferTo is baos)
                  (.toByteArray baos)))))

;; ---------------------------------------------------------------------------
;; Statement builder
;; ---------------------------------------------------------------------------

(defn build-statement
  "Build the in-toto v1 + slsa-provenance/v1 statement for a build.

   Required keys in `opts`:
     :job-name      string
     :build-number  long/int
     :artifacts     [{:name <basename> :sha256 <hex>} …]
                    — caller's responsibility to have hashed the files
                      already (so this fn stays pure / no I/O)

   Optional:
     :scm           {:url <repo-url> :commit <sha>}
                    — populates resolvedDependencies if present
     :jenkinsfile   string source of the Jenkinsfile
                    — its sha256 goes into externalParameters
     :started-at    ISO-8601 string
     :finished-at   ISO-8601 string
     :invocation-id string (defaults to a deterministic '<job>#<n>')
     :extras        map merged into the predicate post-construction

   Returns a plain map ready for JSON serialization.  Stable key
   ordering is the caller's problem (clojure.data.json sorts maps
   alphabetically by default which is fine for our purposes)."
  [{:keys [job-name build-number artifacts scm jenkinsfile
           started-at finished-at invocation-id extras]
    :as opts}]
  (when-not (and job-name build-number)
    (throw (ex-info ":job-name and :build-number are required"
                    {:opts opts})))
  (when-not (and (sequential? artifacts) (seq artifacts))
    (throw (ex-info ":artifacts must be a non-empty sequence"
                    {:artifacts artifacts})))
  (let [subjects (mapv (fn [{:keys [name sha256]}]
                         (when-not (and name sha256)
                           (throw (ex-info ":artifacts entries need :name + :sha256"
                                           {:artifact name})))
                         {:name name
                          :digest {:sha256 sha256}})
                       artifacts)
        ext-params (cond-> {:jobName job-name
                            :buildNumber build-number}
                     jenkinsfile (assoc :jenkinsfileSha256
                                        (sha256-of-string jenkinsfile)))
        resolved-deps (when (and scm (:url scm) (:commit scm))
                        [{:uri (str "git+" (:url scm) "@" (:commit scm))
                          :digest {:sha1 (:commit scm)}}])
        metadata (cond-> {:invocationId (or invocation-id
                                            (str job-name "#" build-number))}
                   started-at  (assoc :startedOn started-at)
                   finished-at (assoc :finishedOn finished-at))
        predicate (cond-> {:buildDefinition
                           (cond-> {:buildType anvil-build-type-uri
                                    :externalParameters ext-params}
                             resolved-deps (assoc :resolvedDependencies resolved-deps))
                           :runDetails
                           {:builder {:id (builder-id)}
                            :metadata metadata}}
                    (seq extras) (merge extras))]
    {:_type in-toto-statement-type
     :subject subjects
     :predicateType slsa-provenance-v1-uri
     :predicate predicate}))

(defn single-artifact-statement
  "Convenience for the common case: one artifact at `path`.  Hashes
   the file (real I/O) and delegates to build-statement.  Used by the
   dispatcher hook (T4.3) — one statement per archived artifact."
  [path opts]
  (let [name (.getName (java.io.File. path))
        sha  (sha256-of-file path)]
    (build-statement (assoc opts
                            :artifacts [{:name name :sha256 sha}]))))
