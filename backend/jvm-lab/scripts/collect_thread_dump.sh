#!/usr/bin/env bash
# ============================================================
#  collect_thread_dump.sh - 采集 Java 进程线程转储(Thread Dump)
#
#  用法:
#    ./collect_thread_dump.sh <pid> [输出目录]
#  示例:
#    ./collect_thread_dump.sh 12345 ./diagnostics
#
#  说明:
#    1. 连续采集 3 份、间隔 2 秒（对比多份才能确认线程长期 BLOCKED/WAITING）;
#    2. 依赖 JDK 的 jstack；优先 $JAVA_HOME/bin/jstack，否则用 PATH 中的 jstack，
#       jstack 版本须与目标 JVM 主版本一致（跨版本连接会报错）;
#    3. 无权限时: 确认与目标进程同用户（生产常为 app 用户），或 sudo -u <user> 运行;
#    4. 替代方案: jcmd <pid> Thread.print（推荐）或 kill -3 <pid>(输出到目标进程 stdout)。
# ============================================================
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "[ERROR] 用法: $0 <pid> [输出目录]"
  echo "        先用 jps -l 或 ps -ef | grep java 找到目标 Java 进程 pid"
  exit 1
fi

PID="$1"
OUTDIR="${2:-./diagnostics}"
mkdir -p "$OUTDIR"

# ---- 定位 jstack ----
JSTACK_CMD=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jstack" ]]; then
  JSTACK_CMD="$JAVA_HOME/bin/jstack"
elif command -v jstack >/dev/null 2>&1; then
  JSTACK_CMD="$(command -v jstack)"
else
  echo "[ERROR] 找不到 jstack，请设置 JAVA_HOME 或将 JDK bin 加入 PATH"
  exit 2
fi

TS="$(date +%Y%m%d_%H%M%S)"
echo "[INFO] pid=$PID  jstack=$JSTACK_CMD"
echo "[INFO] 输出目录: $OUTDIR"

for n in 1 2 3; do
  FILE="$OUTDIR/threaddump_${PID}_${TS}_${n}.txt"
  echo "[INFO] 采集第 $n 份: $FILE"
  if ! "$JSTACK_CMD" -l "$PID" > "$FILE" 2>&1; then
    echo "[ERROR] jstack 执行失败，请检查 pid 是否存在/权限是否足够"
    cat "$FILE"
    exit 3
  fi
  [[ $n -lt 3 ]] && sleep 2
done

echo "[INFO] 完成。3 份线程转储已保存到 $OUTDIR"
echo "[INFO] 死锁检查: grep -A5 'Found one Java-level deadlock' $OUTDIR/threaddump_${PID}_${TS}_*.txt"
