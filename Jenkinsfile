@Library('unifly-jenkins-common') _

pipeline {

    agent any

    options {
        buildDiscarder logRotator(artifactNumToKeepStr: '5', numToKeepStr: '10')
        disableConcurrentBuilds()
    }

    environment {
        GIT_REPO = 'https://github.com/rgannu/kafka-schema-metadata-smt.git'
        CREDENTIALS_ID = 'unifly-jenkins'
        JAVA_HOME = "${tool 'openjdk-11'}"
        PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
        UNIFLY_ARTIFACTORY = credentials('unifly-artifactory')
        ORG_GRADLE_PROJECT_artifactoryUser = "$UNIFLY_ARTIFACTORY_USR"
        ORG_GRADLE_PROJECT_artifactoryPassword = "$UNIFLY_ARTIFACTORY_PSW"
    }

    stages {
        stage('Build') {
            steps {
                sh "./gradlew --info clean build"
            }
        }

        stage('Test') {
            steps {
                sh "./gradlew --info check"
            }
        }

        stage('Publish') {
            steps {
              script {
                sh "./gradlew -Dorg.gradle.internal.publish.checksums.insecure=true -Dorg.gradle.daemon=true -PuniflyUsername=${env.ORG_GRADLE_PROJECT_artifactoryUser} -PuniflyPassword=${env.ORG_GRADLE_PROJECT_artifactoryPassword} --parallel --info publish"
              }
            }
        }

    }

    post {
        failure {
            sendSummary('#thirdparty')
        }
        fixed {
            sendSummary('#thirdparty')
        }

        always {
            archiveArtifacts artifacts: 'build/**/*.zip, build/libs/*.jar', onlyIfSuccessful:true, fingerprint: true
            // junit 'build/test-results/**/*.xml'
        }
    }
}
