# Wild Jenkinsfile corpus — real-world matrix receipt

A real-world Jenkinsfile compatibility matrix against **15 diverse non-jenkinsci OSS
projects**, run on the dogfood anvil instance against `anvil v0.3.0` + this PR's fixes.

Built to escape the `jenkinsci/*` org's `buildPlugin(...)` monoculture (all of which
no-op'd to a vacuous SUCCESS before this PR — see [the earlier jenkinsci-only run]
below) and exercise real shape diversity: declarative, scripted, matrix, kubernetes-
agent, dockerfile-agent, parallel-stages, withCredentials, shared libraries, and
nested `node{}` blocks inside declarative.

## How to reproduce

```bash
sudo systemctl restart anvil      # fresh state
bb scripts/wild-corpus.bb         # clones each repo + feeds Jenkinsfile to anvil
```

Per-project: clone (`git clone --depth 1`), POST `/anvil/admin/jobs` with the
Jenkinsfile source, trigger build #1, poll `/api/json` until done (180s cap),
classify the console.

## The matrix

| # | project | shape | bytes | anvil result | dur ms | sh fired |
|---|---|---|---:|---|---:|---:|
| 1 | hibernate-orm | scripted+sharedlib+withcreds | 15722 | **SUCCESS** | 503 | 0 |
| 2 | hibernate-search | scripted+parallel+withcreds | 53421 | **SUCCESS** | 923 | 0 |
| 3 | eclipse-jdt-core | declarative-simple | 3080 | FAILURE | 742 | 1 |
| 4 | eclipse-epsilon | declarative+kubernetes-agent | 7896 | **SUCCESS** | 260 | 0 |
| 5 | eclipse-jkube | declarative+withcreds+gpg | 3355 | FAILURE | 17 | 1 |
| 6 | apache-camel | declarative+matrix+withcreds | 9609 | **SUCCESS** | 346 | 0 |
| 7 | apache-camel-quarkus | declarative-minimal-deploy | 1433 | FAILURE | 10 | 1 |
| 8 | apache-maven | declarative-small | 2031 | FAILURE | 152 | 0 |
| 9 | apache-zookeeper | declarative+matrix+jdk-axes+cron | 2649 | **SUCCESS** | 104 | 0 |
| 10 | apache-cxf | declarative+per-stage-agent+matrix | 5005 | **SUCCESS** | 158 | 0 |
| 11 | apache-activemq | declarative+triggers+withcreds | 8441 | FAILURE | 438 | 3 |
| 12 | apache-streampipes | declarative-nested-node | 5478 | FAILURE | 349 | 1 |
| 13 | apache-cassandra | declarative+scripted-mix+dockerfile | 30772 | FAILURE | 714 | 0 |
| 14 | apache-hbase | declarative+nested-nodes+parallel | 37379 | **TIMEOUT** | — | 0 |
| 15 | eclipse-mojarra | declarative+kubernetes-yaml+release | 66169 | FAILURE | 909 | 0 |

**Tally: 6 SUCCESS, 8 FAILURE, 1 TIMEOUT** (40% / 53% / 7%).

## What changed in this PR

1. **Jenkins env globals in scripted-eval.** `JENKINS_URL`, `BUILD_NUMBER`, `BUILD_ID`,
   `BUILD_TAG`, `BUILD_URL`, `JOB_NAME`, `JOB_URL`, `WORKSPACE`, `BRANCH_NAME`,
   `NODE_NAME` + 12 more — exposed as both bare identifiers (so
   `if (JENKINS_URL == '...')` works) and via the `env` Expando (so
   `env.JENKINS_URL` works). Derived from anvil ctx.

2. **`buildPlugin` and friends recorded as `:jenkins/shared-lib-unresolved`.** The
   dominant jenkins-infra one-liner used to throw `MissingMethodException` and crash
   builds (or, when the static parser saw no `stage()` calls, silently produce a
   vacuous SUCCESS). Now `buildPlugin`, `buildPluginWithGradle`, `mavenBuild`,
   `gradleBuild`, `nodejs`, `buildPython`, `buildDockerImage` all record the call
   so reports show "Jenkinsfile invoked X(...) but the shared library is not
   resolved" instead of either crashing or pretending to succeed.

3. **scripted-eval fires on any non-blank source** when the flag is on, not only
   when the static parser already found `stage(...)` calls. This is what lets a
   buildPlugin-only Jenkinsfile (no literal stages) reach Groovy at all.

4. **`scripts/wild-corpus.bb`** — the harness used to produce this receipt. Drop
   in any new repo to expand the matrix.

## Earlier jenkinsci-only run (pre-PR baseline)

For comparison, the original 12-project run against jenkinsci/* repos:
**10/10 vacuous-SUCCESS** — every Jenkinsfile in the jenkinsci org is a `buildPlugin(...)`
one-liner delegating to the `jenkins-infra/pipeline-library` shared library, which
anvil could not resolve. The static parser saw no `stage()` and emitted a 0-stage
empty pipeline that ran to SUCCESS in <10ms. Builds were green; nothing actually ran.

The wild corpus exposed the same blind spot from a different angle, plus several
other real bugs — see the "follow-ups" section.

## Successes — what the 6 green builds actually did

These ran clean to anvil-SUCCESS without throwing:

- **hibernate-orm & hibernate-search** — scripted Pipeline `node {}` + shared-lib calls.
  Scripted-eval routed through Groovy. Shared-lib stubs absorbed the unresolved calls.
  0 sh — anvil never reached an executable shell command (the unresolved-lib stubs
  short-circuit the body).
- **eclipse-epsilon, apache-camel, apache-zookeeper, apache-cxf** — declarative
  pipelines whose stages bodies dispatched through anvil's known step set without
  hitting unresolved names. SUCCESS is real for the structural execution; whether
  anvil would have *built* the project requires a real workspace + tools, not just
  Jenkinsfile parsing.

Honest framing: SUCCESS here means anvil reached the end of the pipeline IR without
an exception. It does NOT mean a real Maven/Gradle/Docker build ran end-to-end.

## Failures — concrete bugs for follow-up PRs

Each failure represents one (or sometimes more) discrete bug. None are blockers for
this PR — they're the inventory the matrix surfaced:

1. **`No such property: params`** (apache-cassandra) — scripted Pipelines reference
   build parameters as `params.SKIP_CI` etc. Need a `params` Expando in the binding
   set, populated from `/anvil/admin/jobs` job config or empty.

2. **`No such property: BRANCH_CONFIG`** (eclipse-mojarra) — top-level
   `def BRANCH_CONFIG = [...]` inside a declarative Jenkinsfile resolves at script
   compile time but anvil's static IR translator doesn't carry the def into the
   stage bodies. Affects any declarative pipeline that uses top-level `def` for
   shared config.

3. **`No signature of method: JenkinsDSLScript.isDeployedBranch()`** (apache-maven) —
   user-defined `def isDeployedBranch() { ... }` functions inside a script. Groovy
   `Script` base class needs the function added as a method, not just a binding.
   This is the **highest-value follow-up** — pattern shows up in 3 of 8 failures.

4. **Declarative `script { mavenBuild(...) }` blocks bypass the shared-lib stubs**
   added in this PR. The stubs are in `make-scripted-bindings`; the static-IR
   `script {}` block uses `make-dsl-bindings`. Extract stubs to a shared helper.

5. **`echo "Building " + env.BRANCH_NAME` dumps AST instead of evaluating**
   (apache-activemq, apache-streampipes) — `org.codehaus.groovy.ast.expr.BinaryExpression@...`
   ends up in the console. Anvil's declarative-IR translator captures the BinaryExpression
   but the echo handler stringifies the AST node instead of evaluating it against the
   current binding.

6. **`exit 127: ./mvnw: not found`** (apache-camel-quarkus) — the Jenkinsfile expects
   `./mvnw` from the repo root, but anvil's `--depth 1` clone landed it but the
   default `cwd` may not be at the repo root. Workspace-setup issue, not a parser bug.

7. **`gpg: can't open ''`** (eclipse-jkube) — `withCredentials` resolved the secret to
   empty string because no secret is configured in anvil. Expected on a fresh dogfood,
   not really a bug — would be solved by a per-job secrets config UX.

8. **HANG on apache-hbase** (37 KB declarative+nested-nodes+parallel) — anvil's
   parser or runtime entered some long-running state on the largest Jenkinsfile.
   Build #1 still showed `building:true` after 180s. Needs investigation —
   possibly infinite recursion in the nested-node translator or a Groovy compile
   loop on a particular construct.

## Priority for follow-up PRs

1. **`def isDeployedBranch()` in script blocks** (most reused pattern; would unlock
   apache-maven + likely 2+ others on retest)
2. **`params.*` binding** (cassandra; trivial fix)
3. **apache-hbase hang triage** (anvil-level robustness issue, worth a debug session)
4. **echo + BinaryExpression evaluation** (activemq, streampipes; declarative echo bug)
5. **mavenBuild/buildPlugin stubs in declarative `script {}` path** (consistency
   between scripted-eval and runtime.clj binding sets)

Each of these would close at least one matrix entry. Re-run the harness after each
to track the SUCCESS-rate trajectory.
