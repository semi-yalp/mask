FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-query/target/mask-query-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
