# ===== 阶段 1：构建静态产物 =====
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# ===== 阶段 2：nginx 提供静态文件 =====
FROM nginx:alpine
# 移除默认配置，使用自定义 SPA fallback 配置
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx-spa.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ > /dev/null || exit 1
