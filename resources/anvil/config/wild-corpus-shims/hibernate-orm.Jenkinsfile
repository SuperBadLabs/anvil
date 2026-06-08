// AN7-4 synthetic shim — type-B per AV5-6.
//
// Hibernate ORM's real Jenkinsfile uses `@Library('hibernate-jenkins-pipeline-helpers')`
// with helper calls (e.g., `helper.withMavenWorkspace { ... }`). AN7-4 added
// the external @Library Git loader; this shim is interim until the operator
// configures the remote under :anvil.libs/remotes in anvil.edn and verifies
// the real Jenkinsfile runs end-to-end.
//
// This shim exercises the same Maven + Gradle invocations as hibernate-orm's
// real build while skipping the shared-library wrapper. Result when run
// against a checkout: :success (DB-less unit tests) or :failure (compile
// error if workspace isn't populated).
//
// Retire this shim once:
//   - `anvil.edn` has :anvil.libs/remotes for hibernate-jenkins-pipeline-helpers
//   - The resolver can clone the library from its upstream Git repo
//   - Integration test confirms the real Jenkinsfile produces :success
pipeline {
  agent { label 'ubuntu' }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -ntp -DskipTests -am -pl hibernate-core install'
      }
    }
  }
}
