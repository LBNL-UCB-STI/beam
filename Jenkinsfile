pipeline {
  agent {
    node {
      label 'ec2'
    }
  }
  stages {
    stage('build') {
      steps {
        sh './gradlew build'
      }
    }
  }
}
