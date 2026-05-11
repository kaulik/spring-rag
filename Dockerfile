FROM eclipse-temurin:17-jre-alpine

#LABEL maintainer="your-team@example.com"
LABEL description="JRE application image"

ARG BUILD_ID=local
ENV BUILD_ID=${BUILD_ID}

WORKDIR /app

COPY target/*.jar app.jar

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
