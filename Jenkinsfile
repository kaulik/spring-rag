pipeline {
    agent any
    
    // ── Build parameter exposed in Jenkins UI ──────────────────────────────────
    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",   // Auto-populated from Jenkins build number
            description: 'Docker image tag / build identifier'
        )
    }

    environment {
        FULL_IMAGE = "myapp:${params.BUILD_ID}"
    }
    
    tools {
        // Names must match what's configured in Jenkins → Manage Jenkins → Tools
        maven 'Maven3'
        jdk   'JDK17'
    }

    stages {
         stage('Validate') {
            steps {
                echo "BUILD_ID : ${params.BUILD_ID}"
                sh 'docker info'   // verify socket access from inside Jenkins container
            }
        }
        
        stage('Checkout') {
            steps {
                checkout scm
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
                // Stop existing app container if running, then start new one
                // Jenkins talks to HOST Docker via the mounted socket
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

        stage('Package') {
            steps {
                sh 'mvn -B package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }
    }

    post {
        success { echo 'Build succeeded ✅' }
        failure { echo 'Build failed ❌' }
    }
}
