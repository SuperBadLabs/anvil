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
  (:require [chengis.engine.backend :as backend]
            [chengis.engine.backend.docker :as docker]
            [taoensso.timbre :as log]))

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

(defn backend-for-ctx
  "Pick an `ExecutionBackend` for this step's context.

     - `(:active-agent ctx)` has a `:docker {…}` shape → return a fresh
        per-step `DockerBackend` for its image
     - otherwise → return `LocalShell`

   Constructing a `DockerBackend` is cheap (no docker daemon contact
   until `prepare-workspace`), so it's safe to construct one per step
   in this AN5-3 first-cut mode. AN5-3b will cache one per build/stage.

   Returns a record implementing the `ExecutionBackend` protocol."
  [ctx]
  (if-let [docker-spec (docker-agent-spec (:active-agent ctx))]
    (docker/docker-backend
     {:image (:image docker-spec)
      :mode :per-step
      :extra-args (:extra-args docker-spec)})
    (backend/local-shell-backend {})))

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
   (`:step-nonzero-exit`)."
  ([cmd ctx] (execute-via-backend cmd ctx nil))
  ([cmd ctx mask-values]
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
         (result->shell-execute-shape result step-spec))))))
