(ns anvil.tools.dockerfile
  "v0.4 AN6-3 — `agent { dockerfile { filename '…' } }` support.

   Given a workspace + a Dockerfile path, build an ephemeral image
   tagged with a content-hash of the Dockerfile and use it as the
   :active-agent's docker image.  The existing AN5-3 DockerBackend
   bridge then routes h-sh through chengis-core.

   ## Caching

   The image tag is a deterministic function of:

     1. SHA-256 of the Dockerfile content
     2. SHA-256 of any `COPY` / `ADD` source files within the
        workspace that the Dockerfile references (cheap heuristic —
        we only re-hash the named files, not the entire context)

   When the same (workspace, filename) pair already produced an image
   with the same hash, `docker build` is skipped — the existing
   image tag is reused.  Cache invalidation = a re-run with any
   changed bit in the named inputs.

   ## Why anvil-side first

   Per AV4-8 the impl should eventually live in
   `chengis.tools.dockerfile` so other downstream products can use
   the same dockerfile→image bridge.  Landing in anvil first lets
   apache-cassandra (and any other dockerfile-agent build) work
   end-to-end without gating on a chengis-core release cycle.  When
   chengis-core 0.4.0 ships, this namespace can become a thin
   re-export.

   ## Gated by :anvil.features/dockerfile-agent

   Closed-by-default per AV4-7.  When off, the dispatcher's
   existing `:agent/degraded :runtime-unsupported` path holds
   (the AN5-1 honest classification still surfaces the unhonored
   shape)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as bp]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Content hashing
;; ---------------------------------------------------------------------------

(defn- bytes->hex
  "Render a byte array as a lowercase hex string."
  [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [byte b]
      (.append sb (format "%02x" (bit-and byte 0xff))))
    (.toString sb)))

(defn sha256
  "SHA-256 hex digest of a byte array, a string, or a java.io.File."
  [input]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes-form (cond
                     (bytes? input) input
                     (string? input) (.getBytes ^String input "UTF-8")
                     (instance? java.io.File input)
                     (with-open [is (io/input-stream input)]
                       (let [baos (java.io.ByteArrayOutputStream.)]
                         (io/copy is baos)
                         (.toByteArray baos)))
                     :else (throw (ex-info "sha256: unsupported input type"
                                           {:type (class input)})))]
    (bytes->hex (.digest md bytes-form))))

;; ---------------------------------------------------------------------------
;; Dockerfile dependency scanning
;;
;; We don't walk the full Docker build-context (that's what the daemon does
;; once we hand it a `docker build .`).  But for the cache-key we want to
;; sniff any COPY / ADD source files that live inside the workspace, so
;; that a code change inside one of those files busts our cache before
;; the daemon's own layer cache.
;; ---------------------------------------------------------------------------

(defn- parse-copy-add-sources
  "Extract the source paths from COPY / ADD instructions in a Dockerfile.
   Honors:
     COPY src dst
     COPY src1 src2 dst
     COPY [\"src\", \"dst\"]   (JSON form — last entry is dst)
     ADD  src dst              (same shapes)
   Skips lines whose first source begins with --, the `--chown=` etc.
   flags Docker tolerates."
  [dockerfile-text]
  (let [lines (->> (str/split-lines dockerfile-text)
                   (map str/trim)
                   (remove str/blank?)
                   (remove #(str/starts-with? % "#")))]
    (->> lines
         (mapcat (fn [line]
                   (let [[verb & rest-tokens] (str/split line #"\s+")]
                     (when (#{"COPY" "ADD"} (str/upper-case (or verb "")))
                       (let [;; drop flags like --chown=user, --from=stage
                             tokens (remove #(str/starts-with? % "--") rest-tokens)
                             ;; last token is dst; everything before is src
                             srcs (butlast tokens)]
                         srcs)))))
         (remove str/blank?)
         distinct
         vec)))

(defn- workspace-file-hash
  "Hash a single workspace-relative path; returns nil if the file
   doesn't exist (which means it's outside the workspace or is a
   build artifact). We deliberately don't ASCII-recurse into
   directories — that's what the daemon does at build time."
  [workspace path]
  (let [f (io/file workspace path)]
    (when (and (.exists f) (.isFile f))
      (sha256 f))))

;; ---------------------------------------------------------------------------
;; Image tag computation
;; ---------------------------------------------------------------------------

(def ^:private tag-prefix "anvil-dockerfile")

(defn dockerfile-image-tag
  "Compute the deterministic image tag for building `(workspace,
   filename)` as a dockerfile-agent image.  Returns
   `\"anvil-dockerfile:<16-hex>\"` — a short hex suffix so the tag is
   readable in docker UI and CLI listings.

   Hash inputs:
     - SHA-256 of the Dockerfile content
     - SHA-256 of each COPY / ADD source that resolves to a
       workspace file (others ignored)

   Returns nil when the Dockerfile path doesn't exist — the caller
   must surface this as a build failure honestly."
  [workspace filename]
  (let [dockerfile (io/file workspace filename)]
    (when (.exists dockerfile)
      (let [df-text (slurp dockerfile)
            df-hash (sha256 df-text)
            sources (parse-copy-add-sources df-text)
            source-hashes (->> sources
                               (keep #(workspace-file-hash workspace %))
                               sort
                               (str/join ":"))
            combined (str df-hash "|" source-hashes)
            short-hash (subs (sha256 combined) 0 16)]
        (str tag-prefix ":" short-hash)))))

;; ---------------------------------------------------------------------------
;; Build orchestration
;; ---------------------------------------------------------------------------

(defn image-exists?
  "True iff the docker daemon already has an image with this tag.
   Cheaper than `docker pull` — it just hits the local image-store
   (`docker images -q <tag>` returns the image ID when present,
   empty string when not). Wrapped in a try/catch so a missing
   docker binary returns false instead of throwing."
  [tag]
  (try
    (let [{:keys [exit out]}
          (bp/sh {:err :inherit} "docker" "images" "-q" tag)]
      (and (zero? exit) (seq (str/trim out))))
    (catch Throwable _ false)))

(defn build-image!
  "Run `docker build -t <tag> -f <filename> <workspace>` to build the
   image.  Returns {:tag <tag> :exit <int> :cached? false}; cached?
   is always false here — the caller decides whether to call this
   at all based on `image-exists?`.

   The `:execute?` arg gates the actual subprocess. When false (the
   record-only mode the dispatcher uses for most tests), this returns
   {:exit 0 :cached? true} without invoking docker — emulating the
   happy path so the test doubles work without docker on PATH."
  [workspace filename tag {:keys [execute? extra-args]
                           :or {execute? false}}]
  (if-not execute?
    {:tag tag :exit 0 :cached? true :recorded-only? true}
    (try
      (let [argv (cond-> ["docker" "build" "-t" tag "-f" filename "."]
                   (seq extra-args) (into extra-args))
            {:keys [exit out err]}
            (bp/sh {:dir workspace
                    :err :string}
                   argv)]
        {:tag tag :exit exit :cached? false
         :stdout-bytes (count (or out ""))
         :stderr-bytes (count (or err ""))})
      (catch Throwable t
        (log/warn t "dockerfile build threw")
        {:tag tag :exit -1 :cached? false :error (.getMessage t)}))))

(defn ensure-image!
  "High-level entry point.  Compute the deterministic tag; if the
   image is already present locally, skip the build; otherwise
   invoke `docker build`.  Returns a result map:

     {:tag <str>           ; the computed tag (or nil if Dockerfile missing)
      :exit <int>          ; 0 on success, non-zero on build failure
      :cached? <bool>      ; true when build was skipped
      :recorded-only? <bool> ; true in :execute? false test mode
      :missing-dockerfile? <bool>} ; true when the .file doesn't exist"
  [workspace filename {:keys [execute?] :as opts}]
  (if-let [tag (dockerfile-image-tag workspace filename)]
    (cond
      (and execute? (image-exists? tag))
      {:tag tag :exit 0 :cached? true}

      :else
      (build-image! workspace filename tag opts))

    {:tag nil :exit -1 :cached? false :missing-dockerfile? true}))
