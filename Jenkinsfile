pipeline {
  agent any
  stages {
    stage('build') {
      steps {
        sh './gradlew build'
      }
    }
  }
  post {
    always {
      junit 'build/scalatest-report-xml/**/*.xml'
      archiveArtifacts artifacts: 'output/test/**/ITERS/it.*/*.actor_messages_*.csv.gz', allowEmptyArchive: true
    }
  }
}