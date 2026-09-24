#!/usr/bin/env bash
# run-case.sh <engine:lite|core> <mode:mask|rowfilter|both> <query-file>
# 运行单个 (引擎 × 模式 × 查询) 用例，产物落盘:
#   results/raw/<engine>/<mode>/<qid>.out   改写产物 stdout
#   results/raw/<engine>/<mode>/<qid>.err   诊断 stderr
#   results/raw/<engine>/<mode>/<qid>.code  退出码
#   results/raw/<engine>/<mode>/<qid>.ms    耗时(毫秒)
set -u
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
ENGINE="$1"; MODE="$2"; QFILE="$3"
QID=$(basename "$QFILE" .sql)
OUT_DIR="$BENCH_DIR/results/raw/$ENGINE/$MODE"
mkdir -p "$OUT_DIR"

LITE_JAR="$BENCH_DIR/../../mask-lite/target/mask-lite.jar"
CORE_JAR="$BENCH_DIR/../../mask-core/target/sql-mask.jar"
CFG="$BENCH_DIR/config/tpcds-$MODE.yaml"

start=$(date +%s%N)
if [ "$ENGINE" = "lite" ]; then
  java -jar "$LITE_JAR" --metadata "$CFG" < "$QFILE" \
      > "$OUT_DIR/$QID.out" 2> "$OUT_DIR/$QID.err"
else
  java -jar "$CORE_JAR" --metadata "$CFG" --input "$QFILE" \
      > "$OUT_DIR/$QID.out" 2> "$OUT_DIR/$QID.err"
fi
code=$?
end=$(date +%s%N)
echo "$code" > "$OUT_DIR/$QID.code"
echo $(( (end - start) / 1000000 )) > "$OUT_DIR/$QID.ms"
exit 0
