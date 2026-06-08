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
pipeline {
  agent {
    docker {
      image 'eclipse-temurin:21-jdk'
    }
  }
  stages {
    stage('import-signing-key') {
      steps {
        withCredentials([file(credentialsId: 'jkube-gpg-key', variable: 'GPG_KEY_FILE')]) {
          sh '''
            set -e
            gpg --batch --import "$GPG_KEY_FILE"
            echo "GPG key imported successfully"
            gpg --list-secret-keys --keyid-format=long
          '''
        }
      }
    }
    stage('build') {
      steps {
        sh 'mvn -B -ntp -DskipTests package'
      }
    }
  }
}
