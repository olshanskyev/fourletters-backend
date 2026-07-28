FROM eclipse-temurin:17-jre

WORKDIR /app

# CI packages the module before building the image; the fat jar is named after the artifactId.
COPY target/server.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
