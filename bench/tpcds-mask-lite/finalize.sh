#!/usr/bin/env bash
# finalize.sh — 冷矩阵跑完后的一键收尾:汇总 → round-trip → 语义对拍 → 报告生成
# 前置: run-bench.sh(矩阵)与 run-warm.sh(热身)已跑完
set -eu
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BENCH_DIR"
LITE_JAR="$BENCH_DIR/../../mask-lite/target/mask-lite.jar"
CP="$(cygpath -m "$LITE_JAR");$(cygpath -m warm/lib/duckdb_jdbc.jar 2>/dev/null || true);warm/classes"

echo "=== 1/4 summarize (cold matrix) ==="
bash summarize.sh

echo "=== 2/4 round-trip parse check ==="
rm -f results/roundtrip.txt
for engine in lite core; do
  for mode in mask rowfilter both; do
    echo "--- $engine/$mode" >> results/roundtrip.txt
    java -cp "$CP" RoundTrip "results/raw/$engine/$mode" >> results/roundtrip.txt 2>&1
  done
done
grep -c "OK$" results/roundtrip.txt || true
grep "FAIL" results/roundtrip.txt || echo "no round-trip failures"

echo "=== 3/4 semantic check (DuckDB sf=0.01, lite) ==="
rm -f results/semantic-lite-*.tsv
java -cp "$CP" SemanticCheck lite results/raw mask,rowfilter,both

echo "=== 4/4 generate report pages ==="
java -cp "$CP" GenReport results/raw report lite
echo "done"
