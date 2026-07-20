// CI pipeline: build, test, package, bake the Docker image, then hand off
// to the 'spring-rag-cd' job (Jenkinsfile.cd) for the blue-green deploy.
pipeline {
    agent any

    options {
        // Makes checkout conditional (see the 'Checkout' stage below) — declarative
        // pipelines checkout SCM implicitly before the first stage otherwise, with
        // no way to skip it.
        skipDefaultCheckout()
    }

    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",
            description: 'Docker image tag / build identifier'
        )
        booleanParam(
            name: 'DO_CHECKOUT',
            defaultValue: true,
            description: 'Check out source from git before building. Uncheck to reuse whatever is already in the workspace (e.g. from a previous run) — build will fail if the workspace is empty.'
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

        stage('Checkout') {
            when { expression { params.DO_CHECKOUT } }
            steps {
                checkout scm
            }
        }

        stage('Validate') {
            steps {
                echo "BUILD_ID : ${params.BUILD_ID}"
                sh 'docker info'
                sh 'ls -la'
                sh 'git rev-parse HEAD 2>/dev/null || echo no-git-info'
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
