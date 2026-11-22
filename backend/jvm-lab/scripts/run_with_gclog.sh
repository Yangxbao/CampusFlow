#!/usr/bin/env bash
# ============================================================
#  run_with_gclog.sh - 带 GC 日志运行任意 main 类的脚本模板
#
#  用法:
#    ./run_with_gclog.sh <完整类名> [cp=类路径] [xmx=堆上限] [collector=g1|zgc] [args=主类参数]
#  示例:
#    ./run_with_gclog.sh com.campusflow.lab.jvm.gc.GcLogLab
#    ./run_with_gclog.sh com.campusflow.lab.jvm.gc.GcLogLab cp=target/classes xmx=256m
#    ./run_with_gclog.sh com.campusflow.lab.jvm.gc.GcCompareLab collector=zgc args=zgc
#
#  输出:
#    gc_YYYYMMDD_HHMMSS.log（-Xlog:gc* 全量 GC 日志）
#
#  JDK17 统一日志说明（-Xlog 语法）:
#    -Xlog:gc*:file=gc.log:time,uptime,level,tags
#           ^tag集    ^输出      ^装饰字段
#    tag 组合: gc+heap+exit; 级别: trace/debug/info/warning/error;
#    轮转: filecount=5,filesize=20m;
#    JDK8 的 -XX:+PrintGCDetails -XX:+PrintGCDateStamps 在 JDK9+ 已移除，
#    统一由 -Xlog 接管（gc.log_tools 仍可解析新格式）。
# ============================================================
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "[ERROR] 用法: $0 <完整类名> [cp=类路径] [xmx=堆上限] [collector=g1|zgc] [args=主类参数]"
  exit 1
fi

MAIN_CLASS="$1"
CP="target/classes"
XMX="256m"
COLLECTOR="g1"
APP_ARGS=""
shift || true
for kv in "$@"; do
  case "$kv" in
    cp=*)        CP="${kv#cp=}" ;;
    xmx=*)       XMX="${kv#xmx=}" ;;
    collector=*) COLLECTOR="${kv#collector=}" ;;
    args=*)      APP_ARGS="${kv#args=}" ;;
    *)           APP_ARGS="$APP_ARGS $kv" ;;
  esac
done

# ---- 定位 java ----
JAVA_CMD="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi

TS="$(date +%Y%m%d_%H%M%S)"
GCLOG="gc_${TS}.log"

COLLECTOR_FLAGS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
if [[ "$COLLECTOR" == "zgc" ]]; then
  COLLECTOR_FLAGS="-XX:+UseZGC"
fi

echo "[INFO] 类: $MAIN_CLASS  cp: $CP  xmx: $XMX  collector: $COLLECTOR"
echo "[INFO] GC 日志: $GCLOG  主类参数: $APP_ARGS"

"$JAVA_CMD" \
  -Xms"$XMX" -Xmx"$XMX" \
  $COLLECTOR_FLAGS \
  -Xlog:gc*,gc+heap=debug,gc+humongous=debug:"$GCLOG":time,uptime,level,tags \
  -Dfile.encoding=UTF-8 \
  -cp "$CP" \
  "$MAIN_CLASS" $APP_ARGS

echo ""
echo "[INFO] 运行完成。查看 GC 日志:"
echo "        cat $GCLOG"
echo "[INFO] 统计暂停: grep 'Pause' $GCLOG"
