#!/usr/bin/env bash
# 停止 CampusFlow（保留数据卷）
cd "$(dirname "$0")/../.."
docker compose -f deploy/docker-compose.yml stop
echo ">> 已停止。彻底清理（含数据卷）请执行 deploy/scripts/clean.sh"
