FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-mcp-server/target/mask-mcp-server-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--transport", "http", "--port", "8084"]
