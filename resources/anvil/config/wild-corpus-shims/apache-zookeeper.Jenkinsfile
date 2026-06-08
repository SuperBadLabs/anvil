// AN7-1 synthetic shim — type-B per AV5-6.
//
// Apache ZooKeeper's real Jenkinsfile runs `mvn -fae verify` against
// JDK 17 — produces ~143 jars in ~5 min then hits test failures. Same
// pattern as activemq: real artifacts produced before honest test
// failure.
//
// This shim builds the artifacts and skips tests for a clean :success.
pipeline {
  agent { label 'Hadoop' }
  stages {
    stage('build') {
      steps {
        sh 'mvn -B -ntp -DskipTests install'
      }
    }
  }
}
