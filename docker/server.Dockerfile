# sqlmask 单体镜像（arch-v2）：前端静态资源打进 jar，一个容器 = API + 控制台
#
# 构建：docker build -f docker/server.Dockerfile -t sqlmask/server .
# 运行：见根目录 docker-compose.yml（默认零配置：内嵌 H2 文件库 + 无认证）

# ---- stage 1: 前端构建（vue-tsc + vite） ----
FROM node:20-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- stage 2: 后端构建（把 dist 放进 resources/static 后打包） ----
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY pom.xml .
COPY mask-build-tools/pom.xml mask-build-tools/
COPY mask-sqlparser/pom.xml mask-sqlparser/
COPY mask-policy/pom.xml mask-policy/
COPY mask-engine/pom.xml mask-engine/
COPY mask-audit/pom.xml mask-audit/
COPY mask-common/pom.xml mask-common/
COPY mask-auth/pom.xml mask-auth/
COPY mask-metadata/pom.xml mask-metadata/
COPY mask-policy-admin/pom.xml mask-policy-admin/
COPY mask-query/pom.xml mask-query/
COPY mask-risk/pom.xml mask-risk/
COPY mask-server/pom.xml mask-server/
# 预拉依赖（pom 层缓存）
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
COPY --from=frontend /build/dist/ mask-server/src/main/resources/static/
RUN mvn -B -q package -DskipTests && cp mask-server/target/sqlmask-server.jar /app.jar

# ---- stage 3: 运行时 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/data && useradd -r -u 10001 sqlmask && chown sqlmask /app/data
COPY --from=backend /app.jar /app/app.jar
USER sqlmask
EXPOSE 8080
VOLUME ["/app/data"]
ENV JAVA_OPTS="-Xms256m -Xmx768m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
