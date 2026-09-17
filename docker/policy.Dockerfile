FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-policy-server/target/mask-policy-server-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
