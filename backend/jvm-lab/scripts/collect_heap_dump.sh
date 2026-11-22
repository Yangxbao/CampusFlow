#!/usr/bin/env bash
# ============================================================
#  collect_heap_dump.sh - 采集 Java 进程堆转储(Heap Dump / .hprof)
#
#  用法:
#    ./collect_heap_dump.sh <pid> [输出目录] [live|all]
#  示例:
#    ./collect_heap_dump.sh 12345 ./diagnostics live
#
#  jmap -dump 写法注意(重要):
#    1. 标准写法是长选项形式:
#         jmap -dump:live,format=b,file=heap.hprof <pid>
#    2. :live 只转储存活对象，但会先触发一次 Full GC ——
#       大堆/高负载生产服务可能秒级 STW，抓 dump 前先评估业务影响；
#       要含未回收垃圾对象用 all 模式（文件更大）；
#    3. 推荐优先 jcmd <pid> GC.heap_dump <path>（等价且更稳）；
#    4. 确保磁盘余量 > 进程堆占用；分析用 Eclipse MAT / VisualVM。
# ============================================================
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "[ERROR] 用法: $0 <pid> [输出目录] [live|all]"
  exit 1
fi

PID="$1"
OUTDIR="${2:-./diagnostics}"
MODE="${3:-live}"
mkdir -p "$OUTDIR"

# ---- 定位 jmap ----
JMAP_CMD=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jmap" ]]; then
  JMAP_CMD="$JAVA_HOME/bin/jmap"
elif command -v jmap >/dev/null 2>&1; then
  JMAP_CMD="$(command -v jmap)"
else
  echo "[ERROR] 找不到 jmap，请设置 JAVA_HOME 或将 JDK bin 加入 PATH"
  exit 2
fi

TS="$(date +%Y%m%d_%H%M%S)"
FILE="$OUTDIR/heap_${PID}_${MODE}_${TS}.hprof"

echo "[INFO] pid=$PID mode=$MODE jmap=$JMAP_CMD"
echo "[INFO] 目标文件: $FILE"

if [[ "$MODE" == "live" ]]; then
  "$JMAP_CMD" -dump:live,format=b,file="$FILE" "$PID"
else
  "$JMAP_CMD" -dump:format=b,file="$FILE" "$PID"
fi

echo "[INFO] 完成: $FILE"
echo "[INFO] 后续分析: Eclipse MAT 打开该 .hprof，看 Leak Suspects / Dominator Tree"
