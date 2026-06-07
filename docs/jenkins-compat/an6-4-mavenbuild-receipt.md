# AN6-4 — `mavenBuild` shared-library step receipt

**Date**: 2026-06-07
**Build**: `wild-apache-maven` (v0.3.3 wild-corpus rerun)
**Classification**: `:unsupported step.mavenBuild`
**One-line answer**: **`mavenBuild()` is a shared-library function
defined in Jenkinsci's `pipeline-library`, not a built-in step.**
Anvil's compat shim returns `:unsupported` honestly rather than
synthesizing fake build steps.

## What the wild-corpus receipt asked

> `apache-maven → :unsupported step.mavenBuild (apache-maven's
> Jenkinsfile calls mavenBuild() from a custom shared lib)`

The v0.3.3 receipt left the implementation status as "v0.4 if the
corpus broadens." AN6-4 closes that decision.

## What `mavenBuild()` is

apache-maven's Jenkinsfile uses Jenkins's internal pipeline-library
(github.com/jenkinsci/pipeline-library), which defines:

```groovy
// vars/mavenBuild.groovy in jenkinsci/pipeline-library
def call(Map params = [:]) {
    String jdk           = params.jdk ?: '17'
    String maven         = params.maven ?: '3.9.x'
    List<String> goals   = params.goals ?: ['clean', 'verify']
    Boolean publishersUsed = params.publishers ?: false
    // ... 80+ more lines configuring the build ...
}
```

It's invoked as a step:

```groovy
mavenBuild jdk: '17', maven: '3.9.x', goals: ['clean','package']
```

But it's NOT a Jenkins built-in. It's a Groovy function defined in a
shared library that gets pulled in via `@Library('pipeline-library')`
at the top of the Jenkinsfile.

## Decision: receipt-only at v0.4.0

The board (v0.4-board.md, AN6-4) gave two options:

> (a) implement the shared-libs mavenBuild adapter to route to mvn
> directly with the build-step's params translated, or
> (b) honest `:unsupported step.mavenBuild` with a receipt explaining
>     the shared-lib step contract gap. Decision goes to the AN6-4 PR
>     description; default to (b) if the impl > 2 days of work.

Option (a) is **not just an mvn-step translation**. The full
`mavenBuild()` body in jenkinsci/pipeline-library handles:

- JDK selection through Jenkins's tool-installer matrix
- Maven version selection through the same
- Settings-file injection
- Per-build artifact archival hooks (`archiveDirs`)
- Publisher integration (junit, jacoco, gh-status)
- Failure-output post-processing (compressing logs, surfacing problems)

Implementing it as an anvil-side adapter would mean re-implementing
~10 different Jenkins integrations. **That's bigger than 2 days**, and
the result would either drift from jenkinsci/pipeline-library's
behavior or pin to a snapshot.

The honest path is **(b)**: tell operators that `mavenBuild()` from
this specific shared library isn't shimmed at v0.4.0, and recommend
the workaround:

```groovy
// Replace mavenBuild() with an inline sh step:
sh 'mvn -B clean package'
// + add explicit junit step if mavenBuild was publishing tests
junit 'target/surefire-reports/*.xml'
```

## What is NOT this

- Not the *general* "shared-libs are broken in anvil" claim. anvil's
  `anvil.compat.jenkins.shared-libs` namespace handles @Library
  resolution + shim. The gap is specifically the
  jenkinsci/pipeline-library's `mavenBuild` step.
- Not a Maven-execution problem. apache-maven (the upstream Maven
  project) builds fine on anvil via plain `sh 'mvn …'` — it's just
  that *its own Jenkinsfile* calls a shared-lib helper anvil doesn't
  shim.

## What v0.4.x might add

- A registry of "known shared-lib steps with documented workarounds"
  in `anvil.compat.jenkins.shared-libs` — when one's invoked, log
  `[:shared-libs/known-unsupported {:step "mavenBuild" :workaround
  "sh 'mvn …'"}]` so the build console points the operator at the
  fix.
- A community contribution path: a YAML in
  `resources/shared-libs/known-unsupported.yml` so the receipt-list
  stays operator-editable.

Both are v0.4.x territory, not v0.4.0.

## References

- jenkinsci/pipeline-library — https://github.com/jenkinsci/pipeline-library
- Build log: `target/anvil-builds/wild-apache-maven/logs/1.log`
- Wild-corpus receipt: `docs/jenkins-compat/wild-corpus-honest-receipt.md`
  — 2026-06-06 entry, `apache-maven` row
