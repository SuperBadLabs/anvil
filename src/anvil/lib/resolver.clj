(ns anvil.lib.resolver
  "AN7-4b+d — External shared library resolver.

   Jenkins's `@Library('shared-lib@ref')` syntax points at a named Git
   repository. This namespace:

     1. Reads the remote URL for the library name from `anvil.edn` under
        `:anvil.libs/remotes` (AN7-4d).
     2. Clones or updates the remote into a local cache at
        `~/.anvil/libs/<name>/<ref>/` (keyed by (remote-url, ref) per R7).
     3. Returns the path to the resolved directory so the existing
        `anvil.compat.jenkins.libraries/load-library!` can register
        `vars/*.groovy` files as plugin adapters (AN7-4c).

   The cache policy is intentionally simple for v0.5:
     - On first resolve: `git clone --branch <ref> --depth 1 <url> <path>`
     - On subsequent resolves of the same (name, ref): if the dir exists
       AND is a valid git repo (`.git/` present), skip re-fetching.
     - Operators can evict by deleting `~/.anvil/libs/<name>/`.
     - For non-tag/commit refs (e.g. `main`), anvil does NOT auto-update
       on re-runs — this is honest: pinned builds vs rolling branches is
       operator territory; the receipt (AN7-4f) documents this explicitly.

   Security note: Shared libraries run code inside the build. The operator
   is responsible for trusting the sources they list under
   `:anvil.libs/remotes`. Anvil does not sandbox library evaluation.

   Config shape (anvil.edn):

       {:anvil.libs/remotes
        {\"pipeline-library\"   {:url \"https://github.com/jenkinsci/pipeline-library.git\"}
         \"hibernate-ci-tools\"  {:url \"https://github.com/hibernate/ci-tools.git\"}}}

   `ANVIL_LIBS_DIR` env var overrides the default cache root
   (`~/.anvil/libs`). Tests supply their own tmp dirs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.config :as config]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Cache root
;; ---------------------------------------------------------------------------

(defn- libs-cache-root
  "Return the local directory used as the library cache root.
   Precedence: ANVIL_LIBS_DIR env var → ~/.anvil/libs."
  []
  (or (System/getenv "ANVIL_LIBS_DIR")
      (str (System/getProperty "user.home") "/.anvil/libs")))

(defn cache-path
  "Compute the local cache path for library `name` at `ref`.
   Returns a java.io.File (may not exist yet)."
  [name ref]
  (io/file (libs-cache-root) name ref))

;; ---------------------------------------------------------------------------
;; Remote URL lookup
;; ---------------------------------------------------------------------------

(defn remote-url
  "Look up the remote URL for library `name` in `anvil.edn` under
   `:anvil.libs/remotes`. Returns the URL string, or nil if the
   library is not configured.

   Example anvil.edn:
     {:anvil.libs/remotes {\"pipeline-library\" {:url \"https://…\"}}}

   Unknown library names return nil — the resolver surfaces this as
   `:remote-not-configured` so operators get an actionable message
   rather than a confusing 404 from git."
  [name]
  (let [cfg   ((requiring-resolve 'anvil.config/load-edn) "anvil" {})
        remotes (get cfg :anvil.libs/remotes {})]
    (get-in remotes [name :url])))

;; ---------------------------------------------------------------------------
;; Git operations (thin shell wrappers)
;; ---------------------------------------------------------------------------

(defn- run-git!
  "Run a git command as a subprocess. Returns {:exit INT :out STRING :err STRING}.
   The command is a vector of strings starting with 'git'. Logs at debug
   level; callers check :exit for success."
  [& cmd-parts]
  (let [pb (ProcessBuilder. ^java.util.List (map str cmd-parts))
        _  (.redirectErrorStream pb false)
        p  (.start pb)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))
        exit (.waitFor p)]
    {:exit exit :out @out :err @err}))

(defn- git-available? []
  (= 0 (:exit (run-git! "git" "--version"))))

(defn- repo-exists?
  "True if `dir` contains a `.git` subdirectory (is a valid checkout)."
  [^File dir]
  (and (.exists dir) (.exists (io/file dir ".git"))))

(defn- git-clone!
  "Clone `url` at `ref` into `target-dir`. Depth-1 shallow for speed.
   On branch refs, the `--branch` flag selects the branch. On SHA refs
   (40-char hex), clone the default branch and then checkout the SHA,
   since `--branch` doesn't accept commits."
  [^String url ^String ref ^File target-dir]
  (.mkdirs (.getParentFile target-dir))
  (let [sha-ref? (and (= 40 (count ref)) (re-matches #"[0-9a-f]+" ref))
        result   (if sha-ref?
                   ;; Two-step for commit SHAs: full clone, then reset.
                   (let [r1 (run-git! "git" "clone" "--no-tags" url
                                      (.getAbsolutePath target-dir))]
                     (if (zero? (:exit r1))
                       (run-git! "git" "-C" (.getAbsolutePath target-dir)
                                 "reset" "--hard" ref)
                       r1))
                   ;; Branch or tag: depth-1 is fine.
                   (run-git! "git" "clone" "--depth" "1"
                             "--branch" ref
                             "--no-tags"
                             url
                             (.getAbsolutePath target-dir)))]
    result))

;; ---------------------------------------------------------------------------
;; Resolve entry point
;; ---------------------------------------------------------------------------

(defn resolve!
  "Ensure library `name@ref` is available locally. Clones if not cached.

   Returns one of:
     {:status :ok    :path <File>}
       — library is ready at :path (either was cached or just cloned)
     {:status :error :reason :remote-not-configured :detail STR}
       — no entry for `name` in :anvil.libs/remotes
     {:status :error :reason :git-not-available   :detail STR}
       — `git` binary not on PATH
     {:status :error :reason :clone-failed         :detail STR :exit INT}
       — git clone exited non-zero; :detail is git's stderr

   Callers pass the result to `load-library!` from
   `anvil.compat.jenkins.libraries`, which reads the local :path."
  ([name ref] (resolve! name ref nil))
  ([name ref _opts]
   (let [url (remote-url name)]
     (cond
       (nil? url)
       {:status :error
        :reason :remote-not-configured
        :detail (str "No entry for library '" name "' under "
                     ":anvil.libs/remotes in anvil.edn. "
                     "Add {:anvil.libs/remotes {\""
                     name "\" {:url \"<git-url>\"}}} to configure it.")}

       (not (git-available?))
       {:status :error
        :reason :git-not-available
        :detail (str "The `git` binary was not found on PATH. "
                     "Install git and ensure it's accessible by the anvil process.")}

       :else
       (let [target (cache-path name ref)]
         (if (repo-exists? target)
           (do
             (log/debug (str "anvil.lib.resolver: cache hit for " name "@" ref
                             " at " (.getAbsolutePath target)))
             {:status :ok :path target})
           (do
             (log/info (str "anvil.lib.resolver: cloning " name "@" ref
                            " from " url
                            " → " (.getAbsolutePath target)))
             (let [result (git-clone! url ref target)]
               (if (zero? (:exit result))
                 (do
                   (log/info (str "anvil.lib.resolver: cloned " name "@" ref))
                   {:status :ok :path target})
                 (do
                   (log/warn (str "anvil.lib.resolver: clone failed for "
                                  name "@" ref " — " (:err result)))
                   {:status :error
                    :reason :clone-failed
                    :detail (str (str/trim (:err result)))
                    :exit   (:exit result)}))))))))))

;; ---------------------------------------------------------------------------
;; Batch resolve (AN7-4c integration hook)
;; ---------------------------------------------------------------------------

(defn resolve-all!
  "Resolve every library in a pipeline-IR's :libraries vector.
   Returns a map from {:name N :ref R} → resolve! result.
   Continues on individual failures (one failing library doesn't stop
   the others)."
  ([pipeline-ir] (resolve-all! pipeline-ir nil))
  ([pipeline-ir opts]
   (reduce (fn [acc {:keys [name version]}]
             (let [ref (or version "main")]
               (assoc acc {:name name :ref ref}
                          (resolve! name ref opts))))
           {}
           (:libraries pipeline-ir []))))
