pipeline {
  agent any
  stages {
    stage('Clone Jenkins') {
      steps {
        sh '''
          set -e
          rm -rf jenkins-src
          git clone --depth 1 --branch master \
            https://github.com/jenkinsci/jenkins.git jenkins-src
          echo "Cloned at: $(cd jenkins-src && git rev-parse --short HEAD)"
        '''
      }
    }
    stage('Build CLI module') {
      steps {
        sh '''
          set -e
          cd jenkins-src
          # Tier 1: smallest viable subset — cli + its dependencies,
          # no tests, no docs, no Javadoc. ~3-5 min on a warm Maven cache.
          mvn -B -ntp \
              -pl cli -am \
              -DskipTests \
              -Dmaven.javadoc.skip=true \
              -Dspotbugs.skip=true \
              -Dcheckstyle.skip \
              -Denforcer.skip=true \
              install
        '''
      }
    }
    stage('Receipt') {
      steps {
        sh '''
          set -e
          cd jenkins-src
          jar=$(ls cli/target/cli-*.jar 2>/dev/null | grep -v sources | head -1)
          if [ -z "$jar" ]; then
            echo "FAIL: no cli jar built"
            exit 1
          fi
          echo "=== anvil → jenkinsci/jenkins Tier-1 receipt ==="
          echo "  built: $jar"
          echo "  size:  $(du -h "$jar" | cut -f1)"
          echo "  sha:   $(sha256sum "$jar" | cut -d' ' -f1)"
        '''
      }
    }
  }
}
