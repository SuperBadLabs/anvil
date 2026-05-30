(ns anvil.compat.jenkins.shared-libs.infra
  "TX11D — Built-in shim for the ci.jenkins.io `infra` shared library.

   These are the actual methods jenkinsci/jenkins's Jenkinsfile calls
   on `infra.*`. Each shim is a handler with signature `(f step ctx)`
   returning a result map the dispatcher splices into the effects log.

   v1 substitutions (all honest — documented in
   docs/jenkins-compat/divergences.md):

   | infra method                  | anvil v1 shim does               |
   |-------------------------------|----------------------------------|
   | checkoutSCM()                 | NO-OP — workspace already set up |
   | runWithMaven(args, …)         | sh `mvn $args`                   |
   | publishReports([reports])     | record effect; no upload         |
   | maybePublishIncrementals()    | NO-OP                            |
   | withArtifactCachingProxy {b}  | wrap → run body unchanged        |
   | retainOnlyPeerProducedHashes  | NO-OP                            |

   None of these implementations talk to launchable.com, jenkins-infra
   credentials, or maven-incrementals.com — they perform the local
   equivalent of the call's intent, log what was substituted, and let
   the rest of the pipeline proceed."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helper: convert string-keyed map arg back to a Clojure map
;; ---------------------------------------------------------------------------

(defn- first-arg [step]
  (first (:args step)))

(defn- arg-as-string [step]
  (let [a (first-arg step)]
    (cond
      (string? a)  a
      (keyword? a) (name a)
      (map? a)     (pr-str a)
      :else        (str a))))

;; ---------------------------------------------------------------------------
;; The shims
;; ---------------------------------------------------------------------------

(defn checkout-scm
  "`infra.checkoutSCM()` — on ci.jenkins.io this is a wrapper around the
   standard `checkout scm` Pipeline step that resolves to the
   Multibranch-trigger's SCM definition. On anvil v1 the operator has
   already placed the source tree at the workspace cwd, so this is a
   no-op that emits a `:scm/assume-checked-out` effect."
  [_step ctx]
  (log/debug "anvil infra-shim: checkoutSCM (assume-checked-out)")
  {:effect [:scm/assume-checked-out
            {:cwd (:cwd ctx)
             :shim :infra
             :method :checkoutSCM}]})

(defn run-with-maven
  "`infra.runWithMaven(args, jdk, extraArgs)` — runs Maven with the given
   args. On ci.jenkins.io this wraps with launchable test-intel + maven
   integration plugin; anvil v1 just shells out to `mvn`."
  [step ctx]
  (let [args (str/trim (str (arg-as-string step)))
        cmd (str "mvn " args)]
    (log/info (str "anvil infra-shim: runWithMaven → " cmd))
    {:effect [:sh {:cmd cmd
                   :cwd (:cwd ctx)
                   :shim :infra/runWithMaven}]
     :sh-passthrough? true}))

(defn run-maven
  "`infra.runMaven(mavenOptions, jdk)` — alias for runWithMaven; the
   real Jenkins shared library defines both names. Same shell-out
   behavior on anvil."
  [step ctx]
  (let [args (str/trim (str (arg-as-string step)))
        cmd (str "mvn " args)]
    (log/info (str "anvil infra-shim: runMaven → " cmd))
    {:effect [:sh {:cmd cmd
                   :cwd (:cwd ctx)
                   :shim :infra/runMaven}]
     :sh-passthrough? true}))

(defn no-op
  "Generic no-op shim. Records the shimmed call and continues."
  [step _ctx]
  (log/debug (str "anvil shim: " (:name step) " (no-op)"))
  {:effect [:shimmed-no-op {:name (:name step)}]})

(defn no-op-true-predicate
  "Shim for retry-condition predicates (`kubernetesAgent()`,
   `nonresumable()`, etc.). These get called as args to retry, not as
   steps; if the dispatcher does see one as a step (because it appeared
   freestanding), record it and return ok. Predicate semantics are
   handled at the retry level."
  [step _ctx]
  (log/debug (str "anvil shim: " (:name step) " (predicate, always true)"))
  {:effect [:shimmed-predicate {:name (:name step) :value true}]})

(defn publish-reports
  "`infra.publishReports([reports])` — uploads test reports to ci.jenkins.io's
   reporting service. v1 records the effect tuple so observers can see
   what would have been published, then no-ops."
  [step _ctx]
  (log/debug "anvil infra-shim: publishReports (recorded, not uploaded)")
  {:effect [:reports/recorded
            {:reports (:args step)
             :shim :infra
             :method :publishReports}]})

(defn maybe-publish-incrementals
  "`infra.maybePublishIncrementals()` — would publish Maven SNAPSHOTs to
   maven-incrementals.jenkins.io. No-op in v1."
  [_step _ctx]
  (log/debug "anvil infra-shim: maybePublishIncrementals (no-op)")
  {:effect [:incrementals/skip
            {:shim :infra :method :maybePublishIncrementals}]})

(defn with-artifact-caching-proxy
  "`infra.withArtifactCachingProxy { … }` — on ci.jenkins.io wraps the
   body with HTTP CONNECT to a Maven proxy for faster dep download.
   v1 runs the body unchanged."
  [step _ctx]
  (log/debug "anvil infra-shim: withArtifactCachingProxy (pass-through)")
  ;; Signal to dispatcher that the wrapped body should run with the same
  ;; ctx (no env modification).
  {:wrap-body? true
   :body (:body step)
   :shim :infra
   :method :withArtifactCachingProxy})

(defn retain-only-peer-produced-hashes
  "`infra.retainOnlyPeerProducedHashes(...)` — Launchable filter helper.
   No-op in v1."
  [_step _ctx]
  {:effect [:launchable/no-op
            {:shim :infra :method :retainOnlyPeerProducedHashes}]})
