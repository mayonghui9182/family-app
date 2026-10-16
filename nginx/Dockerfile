# family-app Nginx 网关镜像
# 将 default.conf 直接构建进镜像，避免 Docker Desktop bind mount 文件共享问题
FROM nginx:1.27-alpine
COPY default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80 443
