// CI pipeline: build, test, package, bake the Docker image, then hand off
// to the 'spring-rag-cd' job (Jenkinsfile.cd) for the blue-green deploy.
pipeline {
    agent any

    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",
            description: 'Docker image tag / build identifier'
        )
        booleanParam(
            name: 'TRIGGER_CD',
            defaultValue: true,
            description: 'Trigger the spring-rag-cd deploy job after a successful build'
        )
        choice(
            name: 'SPRING_PROFILE',
            choices: ['prod', 'dev'],
            description: 'Passed through to CD — selects spring-rag-<profile>.yml overrides'
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

        stage('Trigger CD') {
            when { expression { params.TRIGGER_CD } }
            steps {
                build job: 'spring-rag-cd',
                      wait: false,
                      parameters: [
                          string(name: 'IMAGE_TAG',      value: "${params.BUILD_ID}"),
                          string(name: 'TARGET_COLOR',   value: 'auto'),
                          string(name: 'SPRING_PROFILE', value: "${params.SPRING_PROFILE}")
                      ]
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
        success {
            echo "Built myapp:${params.BUILD_ID} — CD ${params.TRIGGER_CD ? 'triggered' : 'skipped'}"
        }
        failure {
            echo "Build failed"
        }
    }
}
