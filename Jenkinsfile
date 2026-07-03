pipeline {
    agent any

    parameters {
        string(
            name: 'BUILD_ID',
            defaultValue: "${env.BUILD_NUMBER}",
            description: 'Docker image tag / build identifier'
        )
        choice(
            name: 'SPRING_PROFILE',
            choices: ['prod', 'dev'],
            description: 'Spring profile — selects spring-rag-<profile>.yml overrides from config-repo'
        )
        string(
            name: 'INSTANCE_COUNT',
            defaultValue: '3',
            description: 'Number of spring-rag containers to run (nginx upstream must list the same ports)'
        )
        string(
            name: 'BASE_PORT',
            defaultValue: '8585',
            description: 'Host port of instance 1; instance N gets BASE_PORT + N - 1'
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
                echo "PROFILE  : ${params.SPRING_PROFILE}"
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
                withCredentials([
                    string(credentialsId: 'WEAVIATE_API_KEY', variable: 'WEAVIATE_API_KEY'),
                    string(credentialsId: 'API_KEY',          variable: 'API_KEY')
                ]) {
                    sh """
                        # Remove the legacy single-instance container if present
                        docker stop myapp 2>/dev/null || true
                        docker rm   myapp 2>/dev/null || true

                        # Rolling deploy: replace one instance at a time so nginx
                        # always has healthy upstreams to fail over to.
                        i=1
                        while [ \$i -le ${params.INSTANCE_COUNT} ]; do
                            PORT=\$(( ${params.BASE_PORT} + i - 1 ))
                            NAME=spring-rag-\$i

                            docker stop \$NAME 2>/dev/null || true
                            docker rm   \$NAME 2>/dev/null || true
                            docker ps -q  --filter publish=\$PORT | xargs -r docker stop
                            docker ps -aq --filter publish=\$PORT | xargs -r docker rm

                            docker run -d \
                              --name \$NAME \
                              --hostname \$NAME \
                              -p \$PORT:8080 \
                              -e WEAVIATE_API_KEY=\$WEAVIATE_API_KEY \
                              -e RAG_WEAVIATE_API_KEY=\$WEAVIATE_API_KEY \
                              -e API_KEY=\$API_KEY \
                              -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4318 \
                              -e OTEL_RESOURCE_ATTRIBUTES="service.instance.id=\$NAME,service.namespace=spring-rag,host.name=\$NAME,service.version=${params.BUILD_ID}" \
                              -e CONFIG_SERVER_URL=http://host.docker.internal:8686 \
                              -e SPRING_PROFILES_ACTIVE=${params.SPRING_PROFILE} \
                              --add-host=host.docker.internal:host-gateway \
                              --add-host=ollama:host-gateway \
                              --add-host=weaviate:host-gateway \
                              -e BUILD_ID=${params.BUILD_ID} \
                              --restart unless-stopped \
                              myapp:${params.BUILD_ID}

                            # Wait until this instance is healthy before replacing the next
                            # one. Runs inside the app container (busybox wget) — the Jenkins
                            # agent is itself a container, so its localhost can't reach the
                            # host-published ports.
                            t=1
                            until docker exec \$NAME wget -qO /dev/null http://localhost:8080/actuator/health; do
                                if [ \$t -ge 30 ]; then
                                    echo "\$NAME failed to become healthy on port \$PORT"
                                    docker logs --tail 50 \$NAME || true
                                    exit 1
                                fi
                                t=\$(( t + 1 ))
                                sleep 2
                            done
                            echo "\$NAME healthy on port \$PORT"

                            i=\$(( i + 1 ))
                        done
                    """
                }
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
        success {
            echo "Deployed myapp:${params.BUILD_ID} successfully"
        }
        failure {
            echo "Build failed"
        }
    }
}
