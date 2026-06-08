// AN7-4 synthetic shim — type-B per AV5-6.
//
// Hibernate Search's real Jenkinsfile uses `@Library('hibernate-jenkins-pipeline-helpers')`
// with helper calls (e.g., `helper.withMavenWorkspace { ... }`,
// `helper.withBuildEnvironment { ... }`). AN7-4 added the external
// @Library Git loader; this shim is interim until the operator configures
// the remote under :anvil.libs/remotes and verifies the real Jenkinsfile.
//
// Retire this shim when:
//   - :anvil.libs/remotes includes hibernate-jenkins-pipeline-helpers
//   - The resolver clones it + registers vars/*.groovy step adapters
//   - The real Jenkinsfile run produces :success on the CI fleet
pipeline {
  agent { label 'ubuntu' }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -ntp -DskipTests install -pl mapper-orm -am'
      }
    }
  }
}
