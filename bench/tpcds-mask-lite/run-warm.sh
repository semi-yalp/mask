#!/usr/bin/env bash
# run-warm.sh — in-JVM 热身基准：绕开 JVM 启动与进程开销,测纯改写耗时
# 用法: ./run-warm.sh [iterations]   (默认 20;另含 3 轮全量 JIT 预热)
# 产物: results/warm-<mode>.tsv  (qid ok errcode meanMs p50Ms minMs maxMs iterations)
# 说明: 三种模式在同一个 JVM 内按轮次交错测量,共享 JIT 状态,横向可比
set -eu
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BENCH_DIR"
ITERS="${1:-20}"
LITE_JAR="$BENCH_DIR/../../mask-lite/target/mask-lite.jar"

mkdir -p results
if [ ! -f warm/classes/WarmBench.class ] || [ warm/WarmBench.java -nt warm/classes/WarmBench.class ]; then
  javac -encoding UTF-8 -cp "$LITE_JAR" -d warm/classes warm/WarmBench.java
fi

# Windows JVM needs ';' separators and Windows-style paths (Git Bash's MSYS
# conversion does not handle 'path:path' compound arguments)
CP="$(cygpath -m "$LITE_JAR");warm/classes"
java -cp "$CP" WarmBench config corpus/queries "$ITERS" 3 results

for mode in mask rowfilter both; do
  awk -F'\t' 'FNR==1{next} {n++; if($2=="false") f++; v[++c]=$6}
      END{asort(v); printf "%-24s queries=%d median-of-p50=%.2fms failures=%d\n", FILENAME, n, v[int(c/2)], f+0}' \
      "results/warm-$mode.tsv"
done
