# TPC-DS 语料（mask-lite 测试资源）

- `queries/q01..q99.sql`：vanilla PostgreSQL 方言清洗版 TPC-DS 查询，提取自旧仓库
  semi-yalp/mask 提交 `7208bc3`（bench/tpcds-mask-lite/corpus/queries），原始语料
  为 datamindedbe/blog-tpcds-dbt-duckdb 的 dbt 模型（TPC-DS v3 官方查询文本）。
- `configs/tpcds-{mask,rowfilter,both}.yaml`：由 TPC-DS PostgreSQL DDL（v3.2，
  binbjz/tpcds_pg）生成的元数据与策略：25 表全列；`both` 含 5 个脱敏 UDF 策略、
  7 个列绑定与 3 个行过滤表（customer: c_birth_year >= 1930、
  customer_address: ca_country = 'United States'、date_dim: d_year <= 2002）。

查询文本版权归 TPC（Transaction Processing Performance Council），
按基准测试惯例随仓库分发并注明来源。
