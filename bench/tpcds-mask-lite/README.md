# bench/tpcds-mask-lite — TPC-DS 全量 99 查询 × mask-lite 基准

TPC-DS 全部 99 条查询,分别在「仅脱敏 / 仅行过滤 / 脱敏+行过滤」三种策略模式下
测量 mask-lite(以及作为参照的 mask-core)的 SQL 改写行为:改写成功率、注入/命中
覆盖、产物可解析性(round-trip)、DuckDB 实测语义对拍、冷启动与纯改写耗时。

**报告入口:[report/REPORT.md](report/REPORT.md)**
(结论、方法、问题清单 P1–P5、已知限制);逐查询的原始 SQL、三模式改写 SQL、
判定与解决思路见 [report/CASES.md](report/CASES.md) 索引 → [report/cases/](report/cases/)。

## 目录

| 路径 | 内容 |
|---|---|
| `corpus/queries/` | 99 条 TPC-DS 查询(vanilla PostgreSQL 方言) |
| `corpus/normalize.awk` + `normalize.sh` | 语料拉取与归一化(dbt-jinja → 三段名,删除包装 CTE) |
| `config/tpcds-{mask,rowfilter,both}.yaml` | 三种策略模式(7 个脱敏列 / 3 张行过滤表 / 叠加) |
| `run-bench.sh` | 冷启动改写矩阵:99 查询 × 3 模式 × 2 引擎(lite/core) |
| `run-warm.sh` | 纯改写耗时:三模式同 JVM 交错,20 轮取 p50 |
| `summarize.sh` | 汇总 `results/raw/` → `results/matrix.tsv` + `summary.txt` |
| `finalize.sh` | 一键收尾:summarize → round-trip → 语义对拍 → 报告生成 |
| `warm/` | 基准工具源码(WarmBench / RoundTrip / SemanticCheck / GenReport) |
| `results/` | TSV 结果(matrix / warm / semantic / roundtrip);`raw/` 可再生不入库 |
| `report/` | 本轮报告(REPORT.md、CASES.md、cases/qNN.md × 99) |

## 复现

```bash
cd mask-lite && mvn package                 # 构建 fat jar
cd bench/tpcds-mask-lite
bash run-bench.sh 1                         # 串行冷启动矩阵(计时有效)
bash run-warm.sh 20                         # 热身基准
bash finalize.sh                            # 汇总 + round-trip + 语义对拍 + 报告
```

语义对拍需要 DuckDB JDBC 驱动(放入 `warm/lib/duckdb_jdbc.jar`,Maven Central
`org.duckdb:duckdb_jdbc`),运行期自动 `INSTALL tpcds` 并生成 sf=0.01 数据。
