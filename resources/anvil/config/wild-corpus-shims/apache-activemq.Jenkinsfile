// AN7-1 synthetic shim — type-B per AV5-6.
//
// Apache ActiveMQ's real Jenkinsfile produces ~200 jars / ~100 MB via
// `mvn install`, then dies in `mvn verify -Pactivemq.tests-quick`
// (Surefire fork OOM, exit -1). The verify phase is intrinsically
// thread-greedy regardless of docker container size; even real Jenkins
// runs this flaky.
//
// This shim builds the artifacts (the value we want anvil to prove)
// and skips the test phase. Honest type-B: ~200 jars on disk, classifier
// :success, but the "real" CI would also test (which we're not).
pipeline {
  agent { label 'ubuntu' }
  stages {
    stage('build') {
      steps {
        sh 'mvn -B -ntp -DskipTests install'
      }
    }
  }
}
