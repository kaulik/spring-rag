pipeline {
    agent any

    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",
            description: 'Docker image tag / build identifier'
        )
    }

    environment {
        FULL_IMAGE = "myapp:${params.BUILD_ID}"
    }

    tools {
        maven 'Maven3'
    }

    stages {

        stage('Validate') {
            steps {
                echo "BUILD_ID : ${params.BUILD_ID}"
                sh 'docker info'
                sh 'ls -la'
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B clean compile -DskipTests'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B test'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Package') {
            steps {
                sh 'mvn -B package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                    docker build \
                      --build-arg BUILD_ID=${params.BUILD_ID} \
                      -t myapp:${params.BUILD_ID} \
                      -t myapp:latest \
                      .
                """
            }
        }

        stage('Deploy') {
            steps {
                sh """
                    docker stop myapp || true
                    docker rm   myapp || true
                    docker run -d \
                      --name myapp \
                      -p 8585:8080 \
                      -e BUILD_ID=${params.BUILD_ID} \
                      --restart unless-stopped \
                      myapp:${params.BUILD_ID}
                """
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
        success {
            echo "Deployed myapp:${params.BUILD_ID} successfully ✅"
        }
        failure {
            echo "Build failed ❌"
        }
    }
}
