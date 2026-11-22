#!/usr/bin/env bash
# 彻底清理测试环境（容器 + 数据卷，谨慎执行！）
cd "$(dirname "$0")/../.."
read -rp "即将删除全部容器与数据卷（数据库数据将丢失），确认？[y/N] " confirm
if [ "$confirm" = "y" ]; then
  docker compose -f deploy/docker-compose.yml down -v --rmi local
  echo ">> 已彻底清理"
else
  echo ">> 已取消"
fi
