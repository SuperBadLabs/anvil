(ns anvil.compat.jenkins.scm
  "Per-job SCM provisioning — clones (or fast-forwards) the configured
   source repo into the build's workspace BEFORE the dispatcher runs
   the first sh step.

   Wired in by `anvil.web.jenkins-api.runner/run-build!` after
   `ensure-workspace!` and before the dispatcher kicks off. When a job
   has no `:scm` (legacy registration), this returns `:skipped` and the
   build runs in an empty workspace exactly as it did before — pure
   backward compat.

   The wild-corpus matrix (docs/jenkins-compat/wild-corpus-receipt.md)
   identified this as the highest-ROI fix: 3+ of the 8 FAILUREs were
   `mvn -f sub/pom.xml [exit 1]` or `./mvnw: not found` — anvil ran
   the right sh command, just in an empty cwd because no clone had
   happened.

   v1 scope:
   - git only (other VCS comes later via a `:type` dispatch).
   - shallow (--depth 1) clone of the configured branch.
   - subsequent builds reuse the workspace if `.git` is already there
     (fetch + reset --hard origin/<branch>) — much faster than re-clone.
   - any failure is recorded as a scaffolding event + bubbled as an
     exception so the build records FAILURE with the git message,
     rather than silently running in an empty workspace.

   Out of scope (later PRs):
   - submodules, LFS, sparse checkout
   - credential-protected URLs (resolves via anvil.secrets when ready)
   - per-branch / per-PR refspec selection"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

(def ^:private clone-timeout-secs 120)
(def ^:private fetch-timeout-secs 60)

(defn- run-cmd!
  "Synchronous process exec with stdout+stderr captured. Returns
   {:exit n :out s :err s}. Throws on timeout."
  [^java.io.File cwd ^Long timeout-secs argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List argv)
             (.directory cwd)
             (.redirectErrorStream false))
        p (.start pb)
        out-fut (future (slurp (.getInputStream p)))
        err-fut (future (slurp (.getErrorStream p)))
        finished? (.waitFor p timeout-secs TimeUnit/SECONDS)]
    (when-not finished?
      (.destroyForcibly p)
      (throw (ex-info "git command timed out" {:argv argv :timeout-secs timeout-secs})))
    {:exit (.exitValue p)
     :out  @out-fut
     :err  @err-fut}))

(defn- git-fresh-clone! [workspace url branch]
  ;; Empty workspace → shallow clone in-place. `git clone` refuses to
  ;; clone into a non-empty dir, so we do it via a temp sibling and
  ;; mv the contents in. This keeps the workspace path stable
  ;; (existing `:cwd` references stay valid).
  (let [parent (.getParentFile ^java.io.File workspace)
        tmp    (io/file parent (str (.getName ^java.io.File workspace) ".clone-tmp"))
        _      (try (fs/delete-tree (str tmp)) (catch Exception _ nil))
        argv   ["git" "clone" "--depth" "1" "--branch" branch "--single-branch" url (.getAbsolutePath tmp)]
        _      (log/info (str "anvil.scm: " (clojure.string/join " " argv)))
        r      (run-cmd! parent clone-timeout-secs argv)]
    (when-not (zero? (:exit r))
      (throw (ex-info "git clone failed"
                      {:exit (:exit r) :err (:err r) :url url :branch branch})))
    ;; Move clone contents (incl. .git) into workspace
    (doseq [entry (fs/list-dir (str tmp))]
      (let [src (io/file (str entry))
            dst (io/file workspace (.getName src))]
        (.renameTo src dst)))
    (try (fs/delete-tree (str tmp)) (catch Exception _ nil))
    {:result :cloned :sha (clojure.string/trim
                           (:out (run-cmd! workspace 5 ["git" "rev-parse" "HEAD"])))}))

(defn- git-refresh! [workspace branch]
  ;; Workspace has .git — fetch + reset to origin/branch. Drops local
  ;; commits (none expected on anvil-managed workspaces) and any
  ;; uncommitted state from the previous build.
  (let [_      (run-cmd! workspace fetch-timeout-secs
                     ["git" "fetch" "--depth" "1" "origin" branch])
        _      (run-cmd! workspace 10
                     ["git" "reset" "--hard" (str "origin/" branch)])
        _      (run-cmd! workspace 10 ["git" "clean" "-fdx"])
        sha    (clojure.string/trim
                (:out (run-cmd! workspace 5 ["git" "rev-parse" "HEAD"])))]
    {:result :refreshed :sha sha}))

(defn provision!
  "Make `workspace` contain a checkout of `(:url scm)` at
   `(:branch scm)`. Idempotent — re-running on an existing checkout
   fetches + resets instead of re-cloning.

   `scm` shape: `{:type :git :url <str> :branch <str>}`. Other types
   are a no-op for now (returns :unsupported-scm-type).

   Returns a small status map: `{:result :cloned|:refreshed|:skipped|:failed
                                  :sha <str?> :error <str?>}`.

   Never throws — failures return `:failed` so the runner can record
   the SCM-step result as a scaffolding event and decide whether to
   keep going."
  [^java.io.File workspace {:keys [type url branch] :or {type :git branch "main"}}]
  (cond
    (not= :git type)
    {:result :unsupported-scm-type :type (clojure.core/name type)}

    (nil? url)
    {:result :skipped :reason "no scm.url"}

    :else
    (try
      (let [git-dir (io/file workspace ".git")]
        (if (.exists git-dir)
          (git-refresh! workspace branch)
          (git-fresh-clone! workspace url branch)))
      (catch Exception e
        (log/warn e (str "anvil.scm: provision failed for " url " @ " branch))
        {:result :failed
         :error  (.getMessage e)
         :url    url
         :branch branch}))))
