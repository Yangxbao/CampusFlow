#!/usr/bin/env bash
# ============================================================
#  jcmd_diagnose.sh - 用 jcmd 一次性采集 JVM 多维度诊断信息
#
#  用法:
#    ./jcmd_diagnose.sh <pid> [输出目录] [--with-heap-dump]
#  示例:
#    ./jcmd_diagnose.sh 12345 ./diagnostics
#    ./jcmd_diagnose.sh 12345 ./diagnostics --with-heap-dump
#
#  采集内容:
#    VM.uptime / VM.flags / VM.command_line / Thread.print /
#    GC.class_histogram / GC.heap_info (可选 GC.heap_dump)
#
#  说明: jcmd 是 JDK7+ 推荐的一站式诊断入口，与目标 JVM 同版本时优先用它；
#        class_histogram 与 heap_dump 会触发 Full GC，生产大堆服务上需评估影响。
# ============================================================
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "[ERROR] 用法: $0 <pid> [输出目录] [--with-heap-dump]"
  exit 1
fi

PID="$1"
OUTDIR="${2:-./diagnostics}"
WITHDUMP="${3:-}"
mkdir -p "$OUTDIR"

# ---- 定位 jcmd ----
JCMD_CMD=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jcmd" ]]; then
  JCMD_CMD="$JAVA_HOME/bin/jcmd"
elif command -v jcmd >/dev/null 2>&1; then
  JCMD_CMD="$(command -v jcmd)"
else
  echo "[ERROR] 找不到 jcmd，请设置 JAVA_HOME 或将 JDK bin 加入 PATH"
  exit 2
fi

TS="$(date +%Y%m%d_%H%M%S)"
PREFIX="$OUTDIR/jcmd_${PID}_${TS}"

echo "[INFO] pid=$PID jcmd=$JCMD_CMD"
echo "[INFO] 输出前缀: $PREFIX"

run_cmd() {
  local cmd="$1"
  echo "[INFO] >>> $cmd"
  if ! "$JCMD_CMD" "$PID" "$cmd" > "${PREFIX}_${cmd//./_}.txt" 2>&1; then
    echo "[WARN] $cmd 失败，输出如下:"
    cat "${PREFIX}_${cmd//./_}.txt" || true
  fi
}

run_cmd "VM.uptime"
run_cmd "VM.flags"
run_cmd "VM.command_line"
run_cmd "Thread.print"
run_cmd "GC.class_histogram"
run_cmd "GC.heap_info"

if [[ "$WITHDUMP" == "--with-heap-dump" ]]; then
  echo "[INFO] 采集 GC.heap_dump（可能较慢）..."
  "$JCMD_CMD" "$PID" GC.heap_dump "${PREFIX}_heap.hprof" \
    || echo "[WARN] GC.heap_dump 失败，进程可能无权限或磁盘不足"
fi

echo ""
echo "[INFO] 完成。生成文件:"
ls -l "$OUTDIR"/jcmd_"$PID"_"$TS"* 2>/dev/null || true
echo "[INFO] 快速判读: VM.flags 看参数是否生效; Thread.print 尾部找死锁; class_histogram 看谁占内存最多"
