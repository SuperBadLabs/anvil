(ns anvil.compat.jenkins.deploy-degrade
  "AN5-4 — Detect `mvn ... deploy ...` shell commands when no
   deploy-target credentials are configured, and rewrite them to
   `mvn ... package ...` so the build produces real artifacts in
   `target/*.jar` instead of crashing at the deploy step with HTTP
   401 from `repository.apache.org` etc.

   Why this exists
   ===============
   The wild-corpus dirty-dozen Jenkinsfiles call
   `mvn ... clean deploy` because they expect to run inside Apache /
   Eclipse / Hibernate's own Jenkins instance, which has the deploy
   credentials configured. SuperBadLabs's instance does not. The
   compile + test + package phases all succeed inside the maven
   docker image (AN5-3b proved it); they only crash at the deploy
   step. Without this degrade, every wild-corpus build that gets to
   maven crashes at deploy with `:failure :step-nonzero-exit` and
   produces no jar.

   With this degrade and the `:anvil.features/mvn-deploy-degrade`
   flag enabled, `deploy` is rewritten to `package` BEFORE the
   subprocess is spawned, the jar lands in `target/`, and
   `archiveArtifacts` can pick it up.

   Scope (deliberately narrow)
   ===========================
   - Only acts when the feature flag is on (closed-by-default — the
     default v0.3.x behavior is unchanged, no surprise rewrites)
   - Only matches `mvn` invocations (not `mvnDeploy`,
     `mvn-deploy-plugin:deploy`, gradle `./gradlew publish`, etc.)
   - Only replaces lifecycle-phase `deploy` (the word standing alone
     as a goal/phase), NOT plugin invocations like
     `deploy:deploy-file`, NOT paths like `deploy/target.txt`
   - Emits a `[:mvn/deploy-degraded {...}]` effect so operators see
     in the build log + raw-effects fold exactly what was rewritten

   Limitations
   ===========
   - Doesn't detect whether deploy credentials ARE configured —
     that's a per-job concern. The feature flag is the on/off switch.
     Future work (AN5-4b): integrate with anvil.compat.jenkins.credentials
     to auto-degrade ONLY when the deploy target has no resolved cred.
   - Only handles `mvn` today. `gradle publish`, `sbt publish`,
     `npm publish`, `cargo publish` are the gradle-and-friends extensions
     this layer can absorb in follow-ups."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Goal-detection regex
;;
;; We want to match `deploy` as a standalone maven lifecycle phase or
;; goal — separated by whitespace from neighboring tokens. Cases the
;; regex SHOULD match:
;;
;;   mvn deploy
;;   mvn clean deploy
;;   mvn -Pci -B deploy
;;   mvn clean install deploy -DskipTests
;;   ./mvnw clean deploy
;;
;; Cases it SHOULD NOT match (still need real Maven):
;;   mvn deploy:deploy-file …            ← plugin-mojo invocation
;;   mvn maven-deploy-plugin:deploy …    ← explicit plugin form
;;   cd src/deploy && mvn package        ← `deploy` is a directory
;;   echo "deploy step starts"           ← not an mvn command
;;   mvn package -Pdeploy                ← profile name, not a goal
;; ---------------------------------------------------------------------------

;; Token-based detection. Splitting on whitespace gives us shell-style
;; tokens; we then scan for the mvn invocation token, then look for a
;; bare-`deploy` token AFTER it. This is more robust than regex with
;; lookbehinds because the mvn detection and the goal detection use the
;; same token shape — no positional drift between the two.

(def ^:private mvn-token-pattern
  "Matches a single shell token that is a maven invocation:
   `mvn`, `./mvnw`, `mvnDebug`, `mvnyjp`, `/usr/local/bin/mvn`, etc."
  #"(?:\.?\/)?(?:[^\s/]+/)*mvn(?:w|Debug|yjp)?")

(defn- mvn-token? [tok]
  (boolean (re-matches mvn-token-pattern tok)))

(defn detect-mvn-deploy
  "Pure detector. Splits `cmd` into shell tokens and looks for:

     1. An mvn-invocation token (mvn / ./mvnw / mvnDebug / mvnyjp)
     2. A bare `deploy` token APPEARING AFTER the mvn token

   Returns a map describing the match when both conditions hold,
   else nil:

     {:matched? true
      :tokens VEC          — split-on-whitespace tokens
      :mvn-token-index INT — index of the mvn invocation token
      :goal-token-index INT — index of the bare `deploy` goal token}

   Rejects:
     - `cd deploy && mvn install`        (no `deploy` after mvn)
     - `mvn deploy:deploy-file`          (token is `deploy:deploy-file`, not bare)
     - `mvn -Pdeploy`, `mvn -Ddeploy`    (token is `-Pdeploy`, not bare)
     - `cd src/deploy && make install`   (no mvn at all)
     - `echo deploy`                     (no mvn)"
  [cmd]
  (when (string? cmd)
    (let [tokens (clojure.string/split cmd #"\s+")
          mvn-idx (first (keep-indexed (fn [i t] (when (mvn-token? t) i)) tokens))]
      (when mvn-idx
        (when-let [deploy-idx (first (keep-indexed
                                       (fn [i t]
                                         (when (and (> i mvn-idx) (= "deploy" t))
                                           i))
                                       tokens))]
          {:matched? true
           :tokens (vec tokens)
           :mvn-token-index mvn-idx
           :goal-token-index deploy-idx})))))

(defn degrade-cmd
  "Rewrite `cmd` by replacing the bare `deploy` goal-token AFTER the
   mvn invocation with `package`. Returns the rewritten string, or
   `cmd` unchanged when no degrade applies. Pure.

   Crucially, `deploy` mentions BEFORE the mvn invocation (`cd src/deploy
   &&` directory tokens) are left untouched — only the bare `deploy`
   token that follows the mvn call gets rewritten."
  [cmd]
  (if-let [{:keys [tokens goal-token-index]} (detect-mvn-deploy cmd)]
    (clojure.string/join " " (assoc tokens goal-token-index "package"))
    cmd))

(defn maybe-degrade
  "Top-level entry point. Returns a map describing the decision:

     {:degraded? false}                 — no change, original cmd stands
     {:degraded? true
      :original STRING                  — pre-rewrite command
      :rewritten STRING                 — post-rewrite command
      :reason :no-deploy-credentials}   — why we degraded

   The dispatcher's h-sh calls this BEFORE shell-execute, uses the
   :rewritten value when :degraded?, and emits a
   [:mvn/deploy-degraded {…}] effect so operators see what happened.

   Argument `enabled?` is the feature flag value — when false (the
   default), this is a pure no-op regardless of `cmd`. When true,
   detect + rewrite per the rules in the ns docstring."
  [cmd enabled?]
  (if-not enabled?
    {:degraded? false}
    (if-let [_ (detect-mvn-deploy cmd)]
      (let [rewritten (degrade-cmd cmd)]
        (if (= cmd rewritten)
          {:degraded? false}
          {:degraded? true
           :original cmd
           :rewritten rewritten
           :reason :no-deploy-credentials}))
      {:degraded? false})))
