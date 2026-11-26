# ==================== 构建阶段 ====================
# 使用 Maven + JDK21 镜像编译（JDK21 向下兼容项目所需的 Java 17）
FROM maven:3.9.9-eclipse-temurin-21-jammy AS build
WORKDIR /app
COPY settings.xml ./settings.xml
COPY pom.xml .
COPY src ./src
# 跳过测试快速打包，产出 Spring Boot fat-jar
# -s settings.xml：走阿里云 Maven 镜像，避免云托管构建机连不上 Maven Central 导致 parent POM 拉不下来
RUN mvn -B -s settings.xml clean package -DskipTests

# ==================== 运行阶段 ====================
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai

# 微信云托管会向容器注入 PORT 环境变量（默认 80），
# 应用通过 application.properties 中的 server.port=${PORT:8080} 监听该端口。
COPY --from=build /app/target/silver-care-ai-2.0.jar /app/app.jar

# 仅作声明，实际监听端口由 PORT 环境变量决定
EXPOSE 80

ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
