FROM eclipse-temurin:17-jre

WORKDIR /app

# CI packages the module before building the image; the fat jar is named after the artifactId.
COPY target/server.jar /app/app.jar

EXPOSE 8080

# Container-aware JVM: size the heap from the container memory limit (not the host).
# Override at runtime by setting JAVA_OPTS in compose / docker run.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
