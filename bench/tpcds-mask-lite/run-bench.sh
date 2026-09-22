#!/usr/bin/env bash
# run-bench.sh — TPC-DS × mask-lite/mask-core 基准主入口
# 矩阵: 99 查询 × 3 模式(仅脱敏 A / 仅行过滤 B / 脱敏+行过滤 C) × 2 引擎(lite/core)
# 并行度可传参: ./run-bench.sh [jobs]
set -u
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
JOBS="${1:-4}"
cd "$BENCH_DIR"

# 产物清空重建（raw 目录保持可重入）
rm -rf results/raw
mkdir -p results/raw

# 任务列表: engine mode qfile
: > /tmp/bench-jobs.txt
for qfile in corpus/queries/q*.sql; do
  for mode in mask rowfilter both; do
    for engine in lite core; do
      echo "$engine $mode $qfile" >> /tmp/bench-jobs.txt
    done
  done
done

total=$(wc -l < /tmp/bench-jobs.txt)
echo "running $total cases with $JOBS jobs ..."
xargs -a /tmp/bench-jobs.txt -L 1 -P "$JOBS" "$BENCH_DIR/run-case.sh"

echo "done: $(find results/raw -name '*.code' | wc -l) cases"
