FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-metadata/target/mask-metadata-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
