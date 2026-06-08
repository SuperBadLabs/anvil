// AN7-3 eclipse-jkube shim — type-B per AV5-6.
//
// Eclipse JKube's real Jenkinsfile signs release artifacts with a GPG
// subkey loaded from a `file` credential.  Before AN7-3, anvil reported
// :failure :credential-unresolved for this credential; now it mounts
// the file read-only into the docker step as /anvil-creds/jkube-gpg-key.
//
// Prerequisite: register the credential before running the wild-corpus:
//   anvil secrets add jkube-gpg-key \
//     --type file \
//     --path /run/secrets/jkube-secret-subkeys.asc \
//     --description "JKube GPG signing subkey (AN7-3)"
//
// If the credential is absent the build honestly falls back to
// :failure :credential-unresolved (same as pre-AN7-3 — no regression).
//
// Type-B verdict: this shim skips the full release verify phase.
// The GPG import + build-without-tests path is the scope of AN7-3;
// the full release signing is a type-A target for the v0.6 roadmap.
//
// Single-line `sh '...'` (NOT `sh '''…'''`) — anvil's Groovy heredoc
// parse strips leading lines from triple-quoted blocks, dispatching
// an empty command to docker. The v0.5.0 dogfood verification rerun
// caught this: docker run logged with an empty payload and the build
// classified `:failure :step-nonzero-exit, last exit: 2` in 1.6s.
// Same workaround the PR #75 cassandra shim applies; single-line bash
// chained with `&&` works.
// Known v0.5.0 gap (verified via the v0.5.0 dogfood provision-and-trigger
// receipt at docs/jenkins-compat/an7-1c-jkube-credential-receipt.md):
// anvil's withCredentials translator/dispatcher resolves :file
// credentials (no more :credential-unresolved verdict) but does NOT
// propagate the file mount or env binding to the docker invocation.
// `file_credentials_test.clj` exercises `build-docker-args` directly with
// well-formed file-mounts, but the translator-side `withCredentials([file(…)])`
// path doesn't end up putting `:file-mounts` on the dispatch ctx.
// Filed as v0.5.x follow-up. Until that lands, this shim still classifies
// :failure :step-nonzero-exit honestly (the build dispatches real shell,
// gpg can't find the unmounted file at /anvil-creds/jkube-gpg-key, exits 2).
//
// The shim keeps the env-var form per AN7-3's intent — once the wiring
// bug is fixed it works without further edits and the verdict flips to
// :success (type-B).
pipeline {
  agent {
    docker {
      image 'eclipse-temurin:21-jdk'
    }
  }
  stages {
    stage('import-and-build') {
      steps {
        withCredentials([file(credentialsId: 'jkube-gpg-key', variable: 'GPG_KEY_FILE')]) {
          sh 'gpg --batch --import "$GPG_KEY_FILE" && gpg --list-secret-keys --keyid-format=long && mvn -B -ntp -DskipTests package'
        }
      }
    }
  }
}
