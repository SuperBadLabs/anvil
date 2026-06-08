// AN7-1 synthetic shim — type-B per AV5-6.
//
// Apache Maven's real Jenkinsfile uses pipeline-library's `mavenBuild()`
// shared step (an `@Library` form anvil v0.5 can't honor without AN7-4).
// Until AN7-4 ships an external @Library loader, this shim runs vanilla
// `mvn install -DskipTests` against the same repo — exercises maven's
// own build (proves anvil dispatches + the install graph runs) without
// requiring shared-lib resolution or the test phase.
//
// When AN7-4 lands, retire this shim and the real Jenkinsfile takes over.
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
