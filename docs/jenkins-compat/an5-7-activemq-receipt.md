# AN5-7 — apache-activemq "MojoExecutionException" root-cause receipt

**Date**: 2026-06-06
**Build**: `wild-apache-activemq #1` from the 2026-06-06 AN5-RERUN
**Classification**: `:failure` `:step-nonzero-exit` `last exit: 1`
**One-line answer**: **NOT a Maven plugin crash.** It's
`maven-enforcer-plugin` correctly refusing to build because the host
Maven is too old. The deeper cause is an anvil translator gap that
ran the build on the host instead of in the configured docker image.

## What the wild-corpus receipt asked

> `apache-activemq → :failure :step-nonzero-exit (MojoExecutionException
> — honest failure, root-cause in 0.3.3 AN5-7)`

The v0.3.2 receipt left the root-cause as TBD. AN5-7 closes that.

## What actually happened

Reading `target/anvil-builds/wild-apache-activemq/logs/1.log` lines
116–179, three identical `[INFO] BUILD FAILURE` cycles each carry:

```
[ERROR] Failed to execute goal
        org.apache.maven.plugins:maven-enforcer-plugin:3.6.2:enforce
        (enforce-maven-version) on project activemq-parent:
[ERROR] Rule 0: org.apache.maven.enforcer.rules.version
        .RequireMavenVersion failed with message:
[ERROR] Detected Maven Version: 3.8.7 is not in the allowed range [3.9,).
```

The `org.apache.maven.plugin.MojoExecutionException` further down the
stack trace is the standard wrapper Maven throws when any plugin's
mojo execution fails. The actual failure is the **enforcer rule
refusing the host Maven version**.

apache-activemq's parent POM pins
`maven-enforcer-plugin/RequireMavenVersion` to `[3.9,)` to guarantee
all builds run on a Maven that supports the toolchains config that
activemq's submodule layout needs. **3.8.7 is correctly refused;
nothing in the plugin chain is broken.**

## Why anvil ran with Maven 3.8.7 instead of the docker image

The wild-corpus `agents.edn` overlay maps `label "ubuntu"` →
`{:executor :docker :image "maven:3.9-eclipse-temurin-21"}`. That
image ships Maven 3.9.16. Confirmed:

```
$ docker run --rm maven:3.9-eclipse-temurin-21 mvn --version
Apache Maven 3.9.16
Maven home: /usr/share/maven
Java version: 21.0.11, vendor: Eclipse Adoptium
```

So if `label "ubuntu"` had been honored, the build would have run on
Maven 3.9 and the enforcer would have passed.

It wasn't honored because **apache-activemq uses a parameter-driven
nested label shape** the translator doesn't fully resolve:

```groovy
pipeline {
    agent {
        label {
            label params.nodeLabel
        }
    }
    parameters {
        choice(name: 'nodeLabel', choices: ['ubuntu', 's390x', 'arm', 'Windows'])
    }
}
```

The outer `label { … }` block is Jenkins's
[node-label-parameter form][nodelabel] — the inner `label
params.nodeLabel` is a value setter whose argument is a Groovy
`PropertyExpression` (`params.nodeLabel`), not a constant.

`anvil.compat.jenkins.translator/translate-agent-block` handles the
`label` case as:

```clojure
label-call
{:label (or (const-val (first (:args label-call))) "<dynamic>")}
```

For the apache-activemq shape, `(first (:args label-call))` is a
`:closure` IR node (the `{ label params.nodeLabel }` body), not a
`:const`. `const-val` returns nil. So the agent emerges as
`{:label "<dynamic>"}`.

Downstream, `anvil.agents.registry/resolve-label "<dynamic>"` finds
no entry in agents.edn, warns, and returns the default executor with
`:degraded? true :degrade-reason "no agents.edn entry for label
\"<dynamic>\""`. The dispatcher emits a `[:agent/degraded …]` effect
and **falls through to LocalShell on the host**. Host Maven is
3.8.7. Enforcer fires. Honest `:failure :step-nonzero-exit`.

So the failure IS honest — anvil correctly classified the build as
`:failure` because a shell step exited non-zero. The classifier
worked. The `:agent/degraded` effect is recorded so operators can see
that the agent shape wasn't fully honored.

## What this is NOT

- **Not a Maven plugin crash.** `maven-enforcer-plugin` is doing
  exactly what the apache-activemq parent POM tells it to do.
- **Not a chengis-core DockerBackend bug.** The backend wasn't
  reached because the agent shape didn't resolve to a docker
  executor in the first place.
- **Not a `--user $(id -u):$(id -g)` issue** (CC2-EX1c is unrelated;
  this build produced 1 jar on disk, host-readable, before the
  enforcer killed it).

## What this IS

A v0.4 translator gap: `agent { label { label EXPR } }` where EXPR
is a Groovy `params.X` reference. To honor it, anvil would need to
either:

1. **Static-first-choice fallback**: parse the `parameters { choice
   { name 'nodeLabel' choices: [...] } }` block and use the first
   choice as the static label. Activemq's parameters declare
   `nodeLabel` with choices `['ubuntu', 's390x', 'arm', 'Windows']`,
   so this would pick `"ubuntu"` → maven:3.9-eclipse-temurin-21 →
   enforcer passes. **Pragmatic but coupled to a specific
   declarative-pipeline shape.**

2. **Sandbox Groovy parameter evaluation**: spin a sandboxed Groovy
   shell at translation time with the `params` binding populated
   from the build's parameter values (defaulted to the first choice
   for `choice` params), then evaluate `params.nodeLabel`. **Clean
   but requires the runtime scripting layer to be active before the
   translator runs.**

3. **Emit `[:agent/degraded {:reason :param-driven-label}]`
   honestly** and let the build classify as `:unsupported`
   `:agent-unhonored` (the same shape as `agent { kubernetes … }`).
   **Smallest delta, matches the AN4-2 / AN5-1 honesty bar; the
   build never gets to fail with a confusing Maven enforcer error.**

Option (3) is the right v0.3.x move. Options (1) and (2) are v0.4.

For 0.3.3 ship, the recommendation is: **leave the current behavior
as-is** (honest `:failure :step-nonzero-exit` with a recorded
`:agent/degraded` effect, plus this receipt explaining the chain).
The classifier's "shell step exited non-zero" is technically the
truth of what happened in the dispatcher, even if the deeper truth
is "the wrong shell ran the step." Add a v0.4 board item for the
nested-label-params fix.

## Honest framing

The wild-corpus receipt's previous wording —
`MojoExecutionException — honest failure, root-cause in 0.3.3 AN5-7`
— is now stale. AN5-7's root-cause turned out to be **upstream**:
the build's `:failure` verdict is correct but the trigger was an
anvil translator gap, not a real build-environment problem. Updating
the receipt prose to reflect this is a follow-up.

## References

- Build log:
  `/tmp/anvil-fix/target/anvil-builds/wild-apache-activemq/logs/1.log`
- Translator: `src/anvil/compat/jenkins/translator.clj:701-744`
  (`translate-agent-block`)
- Registry: `src/anvil/agents/registry.clj:105-129`
  (`resolve-label`)
- Dispatcher degrade emit: `src/anvil/compat/jenkins/dispatcher.clj:1111-1115`
- Wild-corpus receipt:
  `docs/jenkins-compat/wild-corpus-honest-receipt.md` —
  2026-06-06 entry, "apache-activemq" row in the per-build table

[nodelabel]: https://plugins.jenkins.io/nodelabelparameter/
