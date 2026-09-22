# TPC-DS 语料

- `queries/qNN.sql`：清洗后的 vanilla PostgreSQL 方言查询（本基准的实际输入）。
- `normalize.awk`：清洗器。输入为 dbt 形态的 TPC-DS 查询，输出为 `queries/`。

## 原始语料获取

TPC-DS 官方只发布 qgen 模板，成品查询取自开源仓库（随机种子已固定，内容与
TPC-DS v3 官方查询文本一致）：

```bash
curl -sL -o /tmp/duckdb_tpcds.tar.gz \
  "https://api.github.com/repos/datamindedbe/blog-tpcds-dbt-duckdb/tarball/main"
mkdir -p /tmp/duckdb_tpcds && tar xzf /tmp/duckdb_tpcds.tar.gz -C /tmp/duckdb_tpcds --strip-components=1
SRC=/tmp/duckdb_tpcds/dbt/dbt_duckdb_tpcds/models/normal

# 清洗（本目录与上级）
for f in $SRC/tpcds_q*.sql; do
  n=$(basename "$f" .sql); n=${n#tpcds_q}
  awk -f normalize.awk "$f" > "queries/q$n.sql"
done
```

表结构 DDL（生成 `../config/tpcds-*.yaml` 用）取自
[binbjz/tpcds_pg](https://github.com/binbjz/tpcds_pg) 的 `tools/tpcds.sql`
（TPC-DS dsdgen 官方 DDL 的 PostgreSQL 移植）。

查询文本版权归 TPC（Transaction Processing Performance Council），
此处按基准测试惯例随仓库分发并注明来源。
