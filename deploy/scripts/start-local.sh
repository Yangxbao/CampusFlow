#!/usr/bin/env bash
# =====================================================================
# 本地（非 Docker）一键启动：复用 VMware 虚拟机中的 Redis/RocketMQ
# 前置：本机 MySQL 8 已运行（root/1234）、虚拟机 192.168.100.128 已开机
# =====================================================================
set -e
cd "$(dirname "$0")/../.."

export JAVA_HOME=${JAVA_HOME:-"D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"}
JAR=backend/campusflow-server/target/campusflow-server.jar

if [ ! -f "$JAR" ]; then
  echo ">> 未找到打包产物，执行 mvn package..."
  (cd . && mvn -q -pl backend/campusflow-server -am package -DskipTests)
fi

echo ">> 启动后端（local 档案：MySQL 本机 3306 / Redis+MQ 虚拟机）"
nohup "$JAVA_HOME/bin/java" -jar "$JAR" --spring.profiles.active=local > backend/campusflow-server/app-startup.log 2>&1 &
echo ">> 启动前端开发服务器（5173）"
cd frontend && nohup npm run dev > dev-server.log 2>&1 &
cd ..
echo ">> 稍后访问: http://localhost:5173 （后端日志: backend/campusflow-server/app-startup.log）"
