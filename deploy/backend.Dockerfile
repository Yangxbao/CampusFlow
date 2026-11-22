# ===== CampusFlow 后端镜像（多阶段：Maven 构建 -> JRE 运行） =====
# 构建（在项目根目录执行）：docker build -f deploy/backend.Dockerfile -t campusflow-backend .
# 注意：构建阶段使用 aliyun Maven 镜像加速；JDK17

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml backend/campusflow-common/pom.xml backend/campusflow-infrastructure/pom.xml \
     backend/campusflow-business/pom.xml backend/campusflow-server/pom.xml \
     backend/concurrency-lab/pom.xml backend/jvm-lab/pom.xml pom.xml ./
COPY backend/campusflow-common backend/campusflow-common
COPY backend/campusflow-infrastructure backend/campusflow-infrastructure
COPY backend/campusflow-business backend/campusflow-business
COPY backend/campusflow-server backend/campusflow-server
COPY backend/concurrency-lab backend/concurrency-lab
COPY backend/jvm-lab backend/jvm-lab
RUN mvn -q -pl backend/campusflow-server -am package -DskipTests \
    -Dmaven.repo.remote=https://maven.aliyun.com/repository/public

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/backend/campusflow-server/target/campusflow-server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
