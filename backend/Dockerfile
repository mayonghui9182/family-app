# 基础镜像：使用 Eclipse Temurin JRE 21 Alpine 版本，体积小适合家庭NAS/树莓派部署
FROM eclipse-temurin:21-jre-alpine

# 设置工作目录
WORKDIR /app

# 复制构建好的 jar 包到容器中
COPY target/*.jar app.jar

# 暴露应用端口
EXPOSE 8080

# 设置时区为上海
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
