#!/usr/bin/env bash
# 查看服务日志：./deploy/scripts/logs.sh [backend|frontend|mysql|redis|broker|namesrv]
SERVICE=${1:-backend}
cd "$(dirname "$0")/../.."
docker compose -f deploy/docker-compose.yml logs -f --tail=200 "$SERVICE"
