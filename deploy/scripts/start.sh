#!/usr/bin/env bash
# CampusFlow 一键启动（Docker Compose）
set -e
cd "$(dirname "$0")/../.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo ">> 已从 .env.example 生成 .env，请按需修改密码后重新执行"
fi

docker compose -f deploy/docker-compose.yml up -d --build
echo ">> 等待后端健康检查..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:${BACKEND_PORT:-8080}/actuator/health | grep -q UP; then
    echo ">> 后端已就绪: http://localhost:${BACKEND_PORT:-8080}"
    echo ">> 前端地址:   http://localhost:${FRONTEND_PORT:-80}"
    echo ">> 接口文档:   http://localhost:${BACKEND_PORT:-8080}/doc.html"
    exit 0
  fi
  sleep 3
done
echo "!! 后端 3 分钟内未就绪，请查看日志: docker compose -f deploy/docker-compose.yml logs backend"
exit 1
