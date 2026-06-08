// AN7-1 synthetic shim — type-B per AV5-6.
//
// Eclipse JDT-Core's real Jenkinsfile produces ~925 jars / ~280 MB —
// by FAR the biggest jar producer in the corpus — but the test phase
// runs against the Eclipse compiler's own JLS test corpus and hits
// intrinsic failures unrelated to anvil. JDT has the most extensive
// jar artifact set of any wild-corpus member.
//
// This shim does `mvn package -DskipTests` to land all of JDT's
// artifacts and skip the failing test phase. Honest type-B.
pipeline {
  agent { label 'ubuntu-latest' }
  stages {
    stage('build') {
      steps {
        sh 'mvn -B -ntp -DskipTests package'
      }
    }
  }
}
