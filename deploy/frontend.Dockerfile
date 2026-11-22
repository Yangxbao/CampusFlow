# ===== CampusFlow 前端镜像（Node 构建 -> Nginx 托管 + API 反代） =====
FROM node:20-alpine AS build
WORKDIR /build
COPY frontend/package.json ./
RUN npm install --registry=https://registry.npmmirror.com --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:1.25-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
