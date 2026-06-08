(ns anvil.compat.jenkins.backend-wiring
  "AN5-3 — bridges anvil's Jenkinsfile dispatcher to chengis-core's
   pluggable `ExecutionBackend` protocol.

   Why this exists
   ===============
   anvil v0.3.1 honored `agent { docker { image '…' } }` by inlining
   `docker run --rm` argv construction in `shell-execute` (the legacy
   TX9 path). That works for the common case but:

     - couples anvil to one specific docker invocation strategy
     - has no `prepare-workspace` / `cleanup` lifecycle, so per-build
       container reuse is impossible
     - has no portable cancel signal (SIGINT → SIGKILL with grace)
     - locks out k8s / podman / nomad backends entirely

   chengis-core 0.2.0 shipped `chengis.engine.backend/ExecutionBackend`
   — a protocol with `backend-name`, `prepare-workspace`, `execute-step`,
   `cleanup`, `cancel`. The `LocalShell` reference impl matches anvil's
   non-docker path; `DockerBackend` (also in chengis-core 0.2.0) matches
   the docker path with proper lifecycle.

   This namespace is the bridge: it inspects `(:active-agent ctx)`,
   returns the appropriate backend instance, and provides
   `execute-via-backend` which translates between chengis-core's
   step-spec / return shapes and the shape `shell-execute`'s callers
   already expect.

   Scope (AN5-3 first cut — `:per-step` mode only)
   ===============================================
   For now, both backends operate in `:per-step` mode: each `sh` step
   spins up a fresh `docker run --rm` for docker agents, or a fresh
   subprocess for non-docker. This is a near-identical replacement for
   anvil's current behavior — same per-step semantics, no lifecycle
   plumbing required from the dispatcher caller.

   AN5-3b will add `:per-build` mode (long-running container reused
   across steps in the same stage) — a substantive speedup, requires
   wiring `prepare-workspace` into `h-agent-stage-enter` and `cleanup`
   into `h-agent-stage-leave`."
  (:require [babashka.process :as bp]
            [clojure.string :as str]
            [chengis.engine.backend :as backend]
            [chengis.engine.backend.docker :as docker]
            [chengis.engine.backend.k8s :as k8s]
            [anvil.config :as config]
            [taoensso.timbre :as log]
            [anvil.build-overrides :as build-overrides]))

;; ---------------------------------------------------------------------------
;; Backend selection
;; ---------------------------------------------------------------------------

(defn- docker-agent-spec
  "Extract a docker `{:image :extra-args}` spec from a Jenkins-shape
   active-agent map. Returns nil when the active-agent is not docker."
  [active-agent]
  (when (and (map? active-agent) (:docker active-agent))
    {:image      (-> active-agent :docker :image)
     :extra-args (-> active-agent :docker :args)}))

(defn- k8s-agent-spec
  "Extract a k8s `{:image :namespace :resource-limits}` spec from a
   Jenkins-shape active-agent map. Returns nil when the active-agent
   isn't kubernetes-shaped or when no image is extractable (i.e. the
   translator couldn't pull one from the yaml/containerTemplate)."
  [active-agent]
  (when (and (map? active-agent) (:kubernetes active-agent)
             (string? (-> active-agent :kubernetes :image))
             (not (str/blank? (-> active-agent :kubernetes :image))))
    (let [k (:kubernetes active-agent)]
      (cond-> {:image (:image k)}
        (:namespace k)       (assoc :namespace (:namespace k))
        (:resource-limits k) (assoc :resource-limits (:resource-limits k))))))

(defn- k8s-kubeconfig-override
  "Resolve the operator's kubeconfig override from anvil.edn (the v0.6
   T1.5 `:anvil.k8s/kubeconfig-path` key). Returns nil when no override
   is set, in which case the K8sBackend falls back to KUBECONFIG env →
   ~/.kube/config."
  []
  (try
    (-> (config/load-edn "anvil" {})
        :anvil.k8s/kubeconfig-path)
    (catch Throwable _ nil)))

(defn- file-mounts->extra-args
  "Convert AN7-3 file-mount specs [{:host-path :container-path …}] into
   extra `docker run` args: `-v /host/path:/anvil-creds/<id>:ro` flags.
   The `:ro` suffix makes the mount read-only so steps can't accidentally
   write back to the operator's credential store."
  [file-mounts]
  (when (seq file-mounts)
    (mapcat (fn [{:keys [host-path container-path]}]
              ["-v" (str host-path ":" container-path ":ro")])
            file-mounts)))

;; ---------------------------------------------------------------------------
;; AN7-5 — resource-limits parsing from `agent { docker { args '…' } }`
;;
;; Jenkins's `docker { args '…' }` block is the operator's natural place to
;; tune container resources (`--memory=4g`, `--cpus=2`, …). chengis-core's
;; `DockerBackend` already honors structured `:resource-limits` (memory-mb,
;; cpus, pids-max, cpu-shares) — but it ignores anvil's `:extra-args` string
;; entirely. So unless we parse those flags out and convert them to the
;; structured shape, an operator writing `args '--memory=4g'` gets silent
;; nothing.
;;
;; This is the AN7-5 minimum: the four well-known resource flags get
;; recognized and converted; anything else stays in extra-args (where
;; chengis-core continues to drop it). When the inline-docker fallback
;; runs (file-mounts case), it splits extra-args directly into argv, so
;; the resource flags still work via that path even without this parse —
;; but most builds don't use file-mounts, and the non-inline path
;; previously had no way to honor them.

(def ^:private ^:const memory-flag-pattern
  ;; Matches `--memory=4g`, `--memory=512m`, `--memory=4G`, or `--memory=4096`
  ;; (raw MB if no suffix). Docker accepts `b/k/m/g`; we accept `m/g` (and
  ;; bare numbers as MB) since those are the operator-relevant units for
  ;; build tuning.
  #"--memory=(\d+)([gGmM]?)")

(def ^:private ^:const cpus-flag-pattern
  ;; `--cpus=2`, `--cpus=1.5`. Chengis-core expects a double.
  #"--cpus=(\d+(?:\.\d+)?)")

(def ^:private ^:const pids-limit-flag-pattern
  ;; `--pids-limit=512`.
  #"--pids-limit=(\d+)")

(def ^:private ^:const cpu-shares-flag-pattern
  ;; `--cpu-shares=1024`. Note hyphenation: docker uses `--cpu-shares`.
  #"--cpu-shares=(\d+)")

(defn- memory->mb
  "Parse a docker --memory= value into MB (chengis-core's :memory-mb shape).
   Bare digits = MB; `g/G` = ×1024; `m/M` = ×1; rejects unparseable units."
  [digits unit]
  (let [n (Long/parseLong digits)]
    (case (and unit (str/lower-case unit))
      ("" nil) n
      "m"      n
      "g"      (* n 1024)
      nil)))

(defn parse-resource-limits
  "Extract `:resource-limits` (the structured shape chengis-core's DockerBackend
   honors) from a docker `args '…'` string. Returns
     {:resource-limits {…} :residual-extra-args \"…with parsed flags stripped\"}.

   The residual string is what we hand to chengis-core's :extra-args (where
   it's silently dropped) AND to the inline-docker path (where it's split into
   argv). Stripping the parsed flags from the residual prevents
   double-application on the inline path.

   Unknown flags are preserved verbatim in the residual. Numerically-invalid
   recognised flags (`--memory=potato`) don't match the digit-anchored regex
   and stay in the residual — chengis-core / docker will surface the error
   at run time the same way a hand-edited extra-args would."
  [extra-args]
  (if (or (nil? extra-args) (str/blank? extra-args))
    {:resource-limits {} :residual-extra-args extra-args}
    (let [memory-match (re-find memory-flag-pattern extra-args)
          cpus-match (re-find cpus-flag-pattern extra-args)
          pids-match (re-find pids-limit-flag-pattern extra-args)
          shares-match (re-find cpu-shares-flag-pattern extra-args)
          memory-mb (when memory-match (memory->mb (nth memory-match 1) (nth memory-match 2)))
          cpus (when cpus-match (Double/parseDouble (nth cpus-match 1)))
          pids-max (when pids-match (Long/parseLong (nth pids-match 1)))
          cpu-shares (when shares-match (Long/parseLong (nth shares-match 1)))
          limits (cond-> {}
                   memory-mb  (assoc :memory-mb memory-mb)
                   cpus       (assoc :cpus cpus)
                   pids-max   (assoc :pids-max pids-max)
                   cpu-shares (assoc :cpu-shares cpu-shares))
          residual (-> extra-args
                       (str/replace memory-flag-pattern "")
                       (str/replace cpus-flag-pattern "")
                       (str/replace pids-limit-flag-pattern "")
                       (str/replace cpu-shares-flag-pattern "")
                       (str/replace #"\s+" " ")
                       str/trim)]
      {:resource-limits limits
       :residual-extra-args (when-not (str/blank? residual) residual)})))

(defn backend-for-ctx
  "Pick an `ExecutionBackend` for this step's context.

     - `(:active-agent ctx)` has a `:docker {…}` shape → return a fresh
        per-step `DockerBackend` for its image
     - otherwise → return `LocalShell`

   AN7-3: When ctx has :file-mounts (from `h-with-credentials`), the
   corresponding `-v host:container:ro` flags are appended to extra-args
   so the docker backend mounts credential files read-only.

   AN7-5a: `--memory=`, `--cpus=`, `--pids-limit=`, `--cpu-shares=` in the
   agent's args string are parsed into chengis-core's structured
   `:resource-limits`. This is the path that makes
   `agent { docker { image '…' args '--memory=4g' } }` actually request
   the memory cap.

   AN7-5b: If `anvil.edn`'s `:anvil.build-overrides` map contains an
   entry for this build's `:job-name`, the override's
   `:docker-resource-limits` are merged on top of the parsed resource
   limits (operator wins). This lets operators tune resource caps without
   touching the upstream Jenkinsfile — the path the wild-corpus heavies
   need since none of them declare docker args in their pipelines.

   Constructing a `DockerBackend` is cheap (no docker daemon contact
   until `prepare-workspace`), so it's safe to construct one per step
   in this AN5-3 first-cut mode. AN5-3b will cache one per build/stage.

   Returns a record implementing the `ExecutionBackend` protocol."
  [ctx]
  (cond
    ;; v0.6 T1 — `agent { kubernetes { ... } }`. Checked BEFORE docker
    ;; because the active-agent map carries `:kubernetes`; docker
    ;; resolution (via `:active-agent {:docker {…}}`) is a separate
    ;; shape entirely (the resolve-agent path doesn't upgrade k8s to
    ;; docker today).
    (k8s-agent-spec (:active-agent ctx))
    (let [spec (k8s-agent-spec (:active-agent ctx))
          override (build-overrides/for-job (:job-name ctx))
          ;; Operators can override k8s resource limits per-job the
          ;; same way AN7-5b overrides docker. Reuse the docker key —
          ;; the shape ({:memory-mb :cpus :pids-max :cpu-shares}) is
          ;; identical and the K8sBackend silently drops the
          ;; docker-only fields.
          override-limits (:docker-resource-limits override)
          merged-limits (merge (:resource-limits spec) override-limits)
          kc (k8s-kubeconfig-override)]
      (k8s/k8s-backend
       (cond-> {:image (:image spec)
                :mode :per-step}
         (:namespace spec) (assoc :namespace (:namespace spec))
         kc                (assoc :kubeconfig-path kc)
         (seq merged-limits) (assoc :resource-limits merged-limits))))

    :else
    (if-let [docker-spec (docker-agent-spec (:active-agent ctx))]
      (let [base-extra-args (or (:extra-args docker-spec) "")
            {:keys [resource-limits residual-extra-args]} (parse-resource-limits base-extra-args)
            override (build-overrides/for-job (:job-name ctx))
            override-limits (:docker-resource-limits override)
            merged-limits (merge resource-limits override-limits)
            file-mount-args (file-mounts->extra-args (:file-mounts ctx))
            ;; Concatenate the residual (post-parse) extra-args with the -v flags.
            ;; The parsed resource flags are now in `resource-limits` and will
            ;; be applied via chengis-core's structured key; keeping them in
            ;; extra-args too would double-apply them on the inline-docker path.
            extra-args-str (if file-mount-args
                             (str/trim (str (or residual-extra-args "") " "
                                            (str/join " " file-mount-args)))
                             residual-extra-args)]
        (docker/docker-backend
         (cond-> {:image (:image docker-spec)
                  :mode :per-step
                  :extra-args (when-not (str/blank? extra-args-str) extra-args-str)}
           (seq merged-limits) (assoc :resource-limits merged-limits))))
      (backend/local-shell-backend {}))))

;; ---------------------------------------------------------------------------
;; Step-spec / result-shape bridge
;; ---------------------------------------------------------------------------

(defn- ctx->step-spec
  "Translate anvil's dispatcher ctx + command into chengis-core's
   step-spec shape (see `chengis.engine.backend` header docstring).

   Anvil ctx keys mapped:
     :cwd / :workspace      → :dir
     :env                   → :env
     :timeout-deadline      → :timeout (relative ms)
     :log-file              → :log-file
     mask-values (passed in) → :mask-values"
  [cmd ctx mask-values]
  (let [now-ms (System/currentTimeMillis)
        deadline (:timeout-deadline ctx)
        timeout-ms (when (number? deadline)
                     (max 1 (- deadline now-ms)))]
    (cond-> {:command cmd
             :job-name (:job-name ctx)
             :build-number (:build-number ctx)
             :dir (or (:cwd ctx) (:workspace ctx))
             :env (:env ctx {})}
      timeout-ms       (assoc :timeout timeout-ms)
      (:log-file ctx)  (assoc :log-file (:log-file ctx))
      (seq mask-values) (assoc :mask-values (vec mask-values)))))

(defn- result->shell-execute-shape
  "Translate chengis-core's execute-step return into the shape
   `shell-execute`'s callers already expect:

     chengis-core         shell-execute
     -------------------  --------------
     :exit-code           :exit
     :stdout              :stdout
     :stderr              :stderr
     :duration-ms         (dropped — anvil's per-step duration is
                           tracked elsewhere via console-line timestamps)
     :timed-out?          (folded into :exit — backend returns 124 for
                           timeouts, matching anvil's existing convention)

   We also synthesize `:streamed?` from the input step-spec — anvil's
   callers use this to decide whether to fan out :stdout/:stderr line
   effects (in buffered mode) or trust the log-file (in streamed mode)."
  [result step-spec]
  {:exit (:exit-code result)
   :stdout (or (:stdout result) "")
   :stderr (or (:stderr result) "")
   :streamed? (boolean (:log-file step-spec))})

;; ---------------------------------------------------------------------------
;; Inline-docker fallback — AN7-3 file credentials
;;
;; chengis-core 0.3.0's `DockerBackend` has no hook for arbitrary mount
;; flags. We pass `-v host:container:ro` strings into the `:extra-args`
;; key on construction, but `run-disposable-container` never reads that
;; key, so the file mounts vanish before `docker run`. Result: the
;; credential file is never inside the container, and the env var
;; (which we DO inject — that part works) points at /anvil-creds/<id>
;; which doesn't exist.
;;
;; Until chengis-core grows a `:mounts` (or equivalent) config, we
;; bypass it for steps that carry `:file-mounts` in ctx and shell out
;; to `docker run` directly. The non-credential 99% path keeps using
;; chengis-core's backend with its full lifecycle. Cancel + per-build
;; reuse + image pulling are losses on this fallback — acceptable for
;; the credential path which always runs `:per-step` anyway and which
;; you can't easily share across builds.
;; ---------------------------------------------------------------------------

(defn- file-mount-flags
  "Per-mount `-v host:container:ro` arg pairs for an inline docker run."
  [file-mounts]
  (mapcat (fn [{:keys [host-path container-path]}]
            ["-v" (str host-path ":" container-path ":ro")])
          file-mounts))

(defn- env-flags
  "Stable-ordered `-e KEY=VAL` flags for an inline docker run. Order
   matches chengis-core's `env-flags` so logged command lines stay
   comparable."
  [env]
  (->> (sort-by key (or env {}))
       (mapcat (fn [[k v]] ["-e" (str k "=" v)]))))

(defn build-inline-docker-argv
  "Pure builder for the `docker run --rm` argv used by the inline
   fallback path. Exposed for tests so we can assert the -v / -e flags
   without invoking the daemon.

   The arg shape mirrors chengis-core's `run-disposable-container` — same
   `-v workspace:workspace`, env flags. Plus the file-mount
   `-v host:container:ro` flags chengis-core can't emit.

   `workdir` (4-arity overload — defaults to `workspace`) sets the
   container's `-w` value. Splitting it from `workspace` (the bind-mount
   root) is what makes `dir('subdir') { sh '...' }` work inside docker
   steps with file credentials — the bind mount stays at the workspace
   root so absolute paths still resolve, while the step executes in the
   subdir (Copilot review on #94)."
  ([docker-spec workspace cmd env file-mounts]
   (build-inline-docker-argv docker-spec workspace workspace cmd env file-mounts))
  ([{:keys [image extra-args]} workspace workdir cmd env file-mounts]
   (vec
    (concat ["docker" "run" "--rm" "-i"
             "-v" (str workspace ":" workspace)
             "-w" (str (or workdir workspace))]
            (when (and extra-args (not (str/blank? extra-args)))
              (str/split extra-args #"\s+"))
            (file-mount-flags file-mounts)
            (env-flags env)
            [image "sh" "-c" cmd]))))

(defn apply-build-overrides
  "Merge any `:env-extra` from the operator's build-overrides config on top
   of the ctx's :env. Resource-limit overrides are handled inside
   `backend-for-ctx`; env-extra has to be applied here so both the
   chengis-core path and the inline-docker path see the merged env.

   Operator override wins on key collision (override merged on top of
   existing env). Returns the (possibly-modified) ctx. No-op when no
   override is configured (the common case). Exposed (not private) so
   tests can lock down the merge behavior without driving a whole
   subprocess execution."
  [ctx]
  (if-let [extra (some-> (:job-name ctx)
                         build-overrides/for-job
                         :env-extra)]
    (update ctx :env (fn [env] (merge env extra)))
    ctx))

(defn- execute-inline-docker
  "Direct `docker run --rm` invocation for the file-credential case
   (see ns-level comment). Honors :timeout-deadline and :log-file the
   same way the non-docker subprocess path in `shell-execute` does."
  [cmd ctx]
  (let [docker-spec (docker-agent-spec (:active-agent ctx))
        ;; `:workspace` is the bind-mount root; `:cwd` is the step's
        ;; working directory (set by `dir('subdir')`). When :workspace
        ;; isn't set, fall back to :cwd as both — matches the chengis-core
        ;; backend's behavior where bs/workspace-path doubles as workdir.
        workspace (or (:workspace ctx) (:cwd ctx) "/workspace")
        workdir   (or (:cwd ctx) workspace)
        argv (build-inline-docker-argv docker-spec
                                       workspace
                                       workdir
                                       cmd
                                       (:env ctx {})
                                       (:file-mounts ctx))
        deadline-ms (when-let [d (:timeout-deadline ctx)]
                      (max 1 (- d (System/currentTimeMillis))))
        log-file (:log-file ctx)
        stream? (some? log-file)
        append-redirect (when stream?
                          (java.lang.ProcessBuilder$Redirect/appendTo log-file))
        base-opts (cond-> {:continue true}
                    stream?       (assoc :out append-redirect
                                         :err append-redirect)
                    (not stream?) (assoc :out :string :err :string))]
    (try
      (let [pb (apply bp/process base-opts argv)
            completed (if deadline-ms
                        (let [fut (future @pb)
                              r   (deref fut deadline-ms ::timeout)]
                          (if (= r ::timeout)
                            (do (.destroyForcibly ^Process (:proc pb))
                                ::timeout)
                            r))
                        @pb)]
        (cond
          (= completed ::timeout)
          {:exit 124 :stdout ""
           :stderr "[anvil] timeout — docker subprocess killed by anvil's timeout enforcement"
           :streamed? stream?}

          stream?
          {:exit (:exit completed) :stdout "" :stderr "" :streamed? true}

          :else
          {:exit (:exit completed)
           :stdout (or (:out completed) "")
           :stderr (or (:err completed) "")
           :streamed? false}))
      (catch Exception e
        {:exit -1 :stdout ""
         :stderr (str "[anvil] execute-inline-docker exception: " (.getMessage e))
         :streamed? false}))))

(defn execute-via-backend
  "Run `cmd` under `ctx` using the chengis-core backend resolved by
   `backend-for-ctx`. Returns the same map shape anvil's legacy
   `shell-execute` returned: `{:exit :stdout :stderr :streamed?}`.

   `mask-values` is an optional collection of secret strings to redact
   from any logged form of the command (consumed by the backend, not
   the dispatcher — the backend knows whether its log-file write path
   needs masking and applies it there).

   Lifecycle (per-step mode):
     1. Construct backend (cheap, no daemon contact)
     2. `prepare-workspace` — for docker, pulls image if missing;
        for local, ensures workspace dir exists
     3. `execute-step` — runs `cmd`, returns result map
     4. `cleanup` — no-op in per-step mode (each step is self-contained)

   On `prepare-workspace` failure (image pull failed, daemon down, …),
   returns `{:exit 125 :stdout \"\" :stderr <explain> :streamed? false}`
   matching the exit code convention chengis-core uses for setup
   failures. Anvil's classifier reads exit 125 as a real failure
   (`:step-nonzero-exit`).

   AN7-3: When ctx carries `:file-mounts` (from
   `h-with-credentials` resolving a :file-type credential), routes to
   `execute-inline-docker` instead — chengis-core 0.3.0's DockerBackend
   silently drops mount-extension flags, so the file would never make
   it into the container and the bound env var would point at an empty
   /anvil-creds/<id>."
  ([cmd ctx] (execute-via-backend cmd ctx nil))
  ([cmd ctx mask-values]
   (let [ctx (apply-build-overrides ctx)]
     (cond
       (and (seq (:file-mounts ctx))
            (docker-agent-spec (:active-agent ctx)))
       (execute-inline-docker cmd ctx)

       :else
       (let [backend-inst (backend-for-ctx ctx)
             build-spec {:workspace-path (or (:workspace ctx) (:cwd ctx))
                         :job-name (:job-name ctx)
                         :build-number (:build-number ctx)
                         :env (:env ctx {})}
           prep (backend/prepare-workspace backend-inst build-spec)]
       (if (= :failed (:result prep))
         (do (log/warn (str "anvil.backend-wiring: prepare-workspace failed: "
                            (:explain prep)))
             {:exit 125 :stdout ""
              :stderr (str "[anvil] backend prepare-workspace failed: "
                           (:explain prep))
              :streamed? false})
         (let [step-spec (-> (ctx->step-spec cmd ctx mask-values)
                             (assoc :backend-state (:backend-state prep)))
               result (backend/execute-step backend-inst step-spec)]
           (try
             (backend/cleanup backend-inst build-spec)
             (catch Throwable t
               ;; Cleanup is best-effort. Failing here would mask a real
               ;; step failure or success.
               (log/warn t "anvil.backend-wiring: cleanup failed; continuing")))
           (result->shell-execute-shape result step-spec))))))))
