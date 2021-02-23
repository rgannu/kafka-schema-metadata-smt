@Library('unifly-jenkins-common') _

pipeline {

    agent any

    options {
        buildDiscarder logRotator(artifactNumToKeepStr: '5', numToKeepStr: '10')
        disableConcurrentBuilds()
    }

    environment {
        GIT_REPO = 'https://github.com/unifly-aero/unifly-analytics.git'
        CREDENTIALS_ID = 'unifly-jenkins'
        JAVA_HOME = "${tool 'openjdk-11'}"
        PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
        UNIFLY_ARTIFACTORY = credentials('unifly-artifactory')
        ORG_GRADLE_PROJECT_artifactoryUser = "$UNIFLY_ARTIFACTORY_USR"
        ORG_GRADLE_PROJECT_artifactoryPassword = "$UNIFLY_ARTIFACTORY_PSW"
    }

    stages {
        stage('Environment - Tag') {
            when {
                buildingTag()
            }
            steps {
              script {
                env.CALC_VERSION = sh (
                  script: "git describe --exact-match --tags",
                  returnStdout: true
                ).trim()
                env.DOCKER_IMAGE_SUFFIX = ''
              }
            }
        }

        stage('Environment - Branch') {
            when {
                expression { env.BRANCH_NAME ==~ /^v(0|[1-9]*)\.(0|[1-9]*)$/ }
            }
            steps {
              script {
                env.CALC_VERSION = env.BRANCH_NAME
                env.DOCKER_IMAGE_SUFFIX = '-x'
              }
            }
        }

        stage('Build') {
            steps {
                sh "./gradlew -PuniflyVersionTargetBranch=${env.CALC_VERSION} --info clean build"
            }
        }

        stage('Test') {
            steps {
                sh "./gradlew -PuniflyVersionTargetBranch=${env.CALC_VERSION} --info check"
            }
        }

        stage('Publish') {
            steps {
              script {
                println "CALC_VERSION:${env.CALC_VERSION}"
                sh "./gradlew -PuniflyVersionTargetBranch=${env.CALC_VERSION} -Dorg.gradle.internal.publish.checksums.insecure=true -Dorg.gradle.daemon=true -PuniflyUsername=${env.ORG_GRADLE_PROJECT_artifactoryUser} -PuniflyPassword=${env.ORG_GRADLE_PROJECT_artifactoryPassword} --parallel --info publish"
              }
            }
        }

        stage ('Docker') {
            steps {
                sh "./gradlew -PuniflyVersionTargetBranch=${env.CALC_VERSION} --info unifly-analytics-tools:copyArtifactForDockerImage"
                echo "Generate Docker image"
                withCredentials([azureServicePrincipal('azure-acr-jenkins')]) {
                  sh '''
                    az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET -t $AZURE_TENANT_ID
                    az account set -s $AZURE_SUBSCRIPTION_ID
                    az acr login --name unifly
                  '''
                }
                script {
                    IMAGE_VERSION = sh (
                        script: "echo $CALC_VERSION | awk 'match(\$0, /[0-9]+.[0-9]+.*/) { print substr(\$0, RSTART, RLENGTH)}'",
                        returnStdout: true
                    ).trim() + env.DOCKER_IMAGE_SUFFIX
                    println "Uploading docker image to Azure with the IMAGE_VERSION:${IMAGE_VERSION}"
                    def image = docker.build("unifly.azurecr.io/unifly/unifly-analytics/connect:${IMAGE_VERSION}", "./docker/connect")
                    image.push();
                }
            }
        }
    }

    post {
        failure {
            sendSummary('#product-team')
        }
        fixed {
            sendSummary('#product-team')
        }

        always {
            archiveArtifacts artifacts: 'build/**/*.zip, build/libs/*.jar', onlyIfSuccessful:true, fingerprint: true
            junit 'build/test-results/**/*.xml'
        }
    }
}
