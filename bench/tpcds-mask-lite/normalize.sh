#!/usr/bin/env bash
# normalize.sh — 拉取 dbt 形态 TPC-DS 99 条查询并清洗为 vanilla PG 方言
# 详见 corpus/README.md
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${TPCDS_SRC:-/tmp/duckdb_tpcds/dbt/dbt_duckdb_tpcds/models/normal}"

if [ ! -d "$SRC" ]; then
  echo "fetching tpcds queries ..."
  curl -sL -o /tmp/duckdb_tpcds.tar.gz \
    "https://api.github.com/repos/datamindedbe/blog-tpcds-dbt-duckdb/tarball/main"
  mkdir -p /tmp/duckdb_tpcds
  tar xzf /tmp/duckdb_tpcds.tar.gz -C /tmp/duckdb_tpcds --strip-components=1
fi

mkdir -p "$DIR/corpus/queries"
ok=0
for f in "$SRC"/tpcds_q*.sql; do
  n=$(basename "$f" .sql); n=${n#tpcds_q}
  if awk -f "$DIR/corpus/normalize.awk" "$f" > "$DIR/corpus/queries/q$n.sql" 2>/dev/null; then
    ok=$((ok+1))
  else
    echo "normalize failed: $f" >&2
  fi
done
echo "normalized $ok queries into corpus/queries/"
