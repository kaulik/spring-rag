// CI pipeline: build, test, package, bake the Docker image, then hand off
// to the 'spring-rag-cd' job (Jenkinsfile.cd) for the blue-green deploy.
pipeline {
    agent any

    options {
        // We do our own checkout (see the 'Checkout' stage below) so it can pin to
        // COMMIT_ID — declarative pipelines checkout SCM implicitly before the first
        // stage otherwise, which only ever checks out the branch head, not a pinned commit.
        skipDefaultCheckout()
    }

    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",
            description: 'Docker image tag / build identifier'
        )
        string(
            name: 'COMMIT_ID',
            defaultValue: '',
            description: 'Exact commit SHA to build. Leave blank to build the branch head (the branch selected for this job).'
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
        // Referenced via $COMMIT_ID (shell env var) in the Checkout stage, never
        // Groovy-interpolated into a sh string — params.COMMIT_ID is free-text
        // build input, and string-interpolating untrusted input into a shell
        // command is a command-injection risk.
        COMMIT_ID = "${params.COMMIT_ID}"
    }

    tools {
        maven 'Maven3'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                script {
                    if (params.COMMIT_ID?.trim()) {
                        // Validate before it ever reaches a shell command — COMMIT_ID
                        // is free-text build input.
                        if (!(params.COMMIT_ID ==~ /[0-9a-fA-F]{7,40}/)) {
                            error "COMMIT_ID '${params.COMMIT_ID}' doesn't look like a git commit SHA (7-40 hex chars)."
                        }
                    }
                }
                // --unshallow guards against a shallow-clone job config not having the
                // pinned commit's history available; falls back to a plain fetch --all
                // if the repo is already a full clone (--unshallow errors on those,
                // which isn't a real failure). $COMMIT_ID is a shell env var here (see
                // the environment block), never Groovy-interpolated into this string.
                sh '''
                    if [ -n "$COMMIT_ID" ]; then
                        git fetch --unshallow --tags -q || git fetch --all --tags -q
                        git checkout "$COMMIT_ID"
                        echo "Pinned to commit: $(git rev-parse HEAD)"
                    else
                        echo "Building branch head: $(git rev-parse HEAD)"
                    fi
                '''
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
                      -f DockerfileSpringRag \
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
