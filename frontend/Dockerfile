# =====================================
# 阶段1：构建阶段 - 使用 Node.js 20 Alpine 构建前端
# =====================================
FROM node:20-alpine AS builder

# 设置工作目录
WORKDIR /app

# 安装 pnpm
RUN npm install -g pnpm

# 复制 package.json 和 pnpm-lock.yaml（利用 Docker 缓存）
COPY package.json pnpm-lock.yaml ./

# 安装依赖
RUN pnpm install

# 复制所有源代码
COPY . .

# 执行构建
RUN pnpm build

# =====================================
# 阶段2：运行阶段 - 使用 Nginx Alpine 提供静态文件
# =====================================
FROM nginx:alpine

# 设置时区为上海
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 复制构建产物到 Nginx 静态资源目录
COPY --from=builder /app/dist /usr/share/nginx/html

# 复制 Nginx 配置文件
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 暴露 80 端口
EXPOSE 80

# 启动 Nginx
CMD ["nginx", "-g", "daemon off;"]
