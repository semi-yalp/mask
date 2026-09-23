# TPC-DS 脱敏改写测试用例说明

用 `tpcds/metadata.yaml`（TPC-DS 表结构子集 + 5 个列脱敏策略）配合本目录 SQL 运行：

```bash
mvn -q package -DskipTests

# 主用例（5 条，全部应成功）
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_masking_test.sql --output tpcds/out/tpcds_masked.sql

# 预期失败用例（应非零退出并给出清晰诊断，不产出结果）
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_unsupported.sql
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_unsupported_setops.sql
```

## 脱敏策略（tpcds/metadata.yaml）

| 列 | 策略 | UDF 调用形态 |
| --- | --- | --- |
| customer.c_email_address | mask_email | `mask_email(x)` |
| customer.c_phone | mask_phone | `mask_phone(x, 3, 4)`（整数参数） |
| customer.c_last_name | mask_name | `mask_name(x)` |
| customer.c_customer_id | mask_hash | `mask_hash(x, 'sha256')`（字符串参数） |
| customer_address.ca_street_name | mask_text | `mask_text(x)` |

## 用例清单与验证结论（2026-09-05）

| # | 来源 | 结构要点 | 期望 | 实测 |
| --- | --- | --- | --- | --- |
| S1 | q79（适配） | 派生子查询 + 4 表 JOIN + 内层 GROUP BY + ORDER BY + LIMIT | 仅 `c_last_name` 包 `mask_name(r.c_last_name)`，其余原样，内层语义完整 | 通过 |
| S2 | q4（原结构） | CTE + UNION ALL + CTE 自连接 ×4 + 外层 CASE WHEN | `customer_id` 包 `mask_hash(r.customer_id, 'sha256')`、`last_name` 包 `mask_name(r.last_name)`（UNION 两分支血缘均命中）；`first_name`/`preferred_cust_flag` 原样；WITH 整体留在内层 | 通过 |
| S3 | q10（原结构） | EXISTS 子查询 + GROUP BY | 输出列均无策略 → 整条原样透传 | 通过 |
| S4 | q21（原结构） | 聚合上开窗 + SELECT * 展开 + 外层 CASE 过滤 | 输出列均无策略 → 原样透传 | 通过 |
| S6 | q79 风格变体 | 输出 email/phone，GROUP BY 含脱敏列 | `mask_email(...)`、`mask_phone(..., 3, 4)` 各包一次；`COUNT(*)` 无来源原样；别名 `order_cnt` 保留 | 通过 |
| 递归 CTE | q14 风格 | `WITH RECURSIVE` 自引用 | 非零退出，诊断 `cannot trace lineage through recursive CTE 'cust_total'` | 通过（预期失败） |
| 根级 INTERSECT | q38 变体 | 根级集合操作 | 非零退出，诊断 `unsupported statement kind INTERSECT` | 通过（预期失败） |
| 根级 EXCEPT | TPC-DS 风格变体 | 根级集合操作 | 非零退出，诊断 `unsupported statement kind EXCEPT` | 通过（预期失败） |
| CTE 无策略 | 补充 | WITH + LIMIT | 原样透传 | 通过 |
| 失败原子性 | 补充 | 失败时指定 `--output` | 不创建/覆盖输出文件，非零退出 | 通过 |

## 测试中发现并修复的缺陷

1. **`CteExpander.rewriteCall` 使用 `clone + setOperand` 重建调用节点**：Calcite 中并非所有 `SqlCall` 子类都支持 `setOperand`，q4 的 `FROM year_total AS t1, year_total AS t2 ...`（`SqlJoin` 树）触发 `UnsupportedOperationException`（CLI 层表现为 `unexpected error: null`）。修复：按 Calcite `SqlShuttle` 的官方做法改为 `call.getOperator().createCall(quantifier, pos, operands)` 重建。
2. **CTE 引用嵌在 JOIN/AS 子树内无法展开**：`rewriteFrom` 只处理 FROM 顶层的 `IDENTIFIER`，`SqlJoin` 的左/右子树和 `AS` 包裹的表名走通用表达式路径，识别符永远替换不到，q4 校验报 `Object 'year_total' not found`。修复：`rewriteFrom` 增加 `AS`/`JOIN` 分支，join 左右两侧与 AS 首操作数保持表表达式上下文。
3. **无策略透传语句输出重复 `ORDER BY ... FETCH ...`**：Calcite 校验器会原地修改解析树（把顶层 `SqlOrderBy` 的 ORDER BY/FETCH 复制进内层 `SqlSelect`），透传路径重新 unparse 已被污染的树导致子句重复（S3/S4）。修复：透传与包装内层统一使用校验前的 SQL 快照 `ValidatedSql.originalSql()`。

## 第二轮补充用例（2026-09-05）

运行方式：

```bash
# 常见 SQL 形态（15 条，全部应成功）
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_common_cases.sql --output tpcds/out/tpcds_common_masked.sql

# 边界/错误场景（逐条 --sql 运行；整体运行会因多语句原子性在首条失败处中止）
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_edge_cases.sql   # 预期 exit=1
```

### tpcds_common_cases.sql（C1–C15，全部通过）

| # | 结构 | 实测结论 |
| --- | --- | --- |
| C1 | 单表直查脱敏列 | `mask_email(r.c_email_address)`，内层保留 LIMIT |
| C2 | 全限定 `tpcds.public.customer` 三段名 | `mask_hash(r.c_customer_id, 'sha256')` |
| C3 | 双引号标识符 `"c_email_address"` | 命中声明列并脱敏 |
| C4 | `SELECT *`（表含 4 个脱敏列） | 按表声明顺序展开，4 个脱敏列各包一次 UDF，其余原样 |
| C5 | WHERE 引用脱敏列（LIKE） | WHERE 明文留在内层，仅外层脱敏 |
| C6 | 单来源派生列 `LOWER(c_email_address)` | `mask_email(r.low_email) AS low_email` |
| C7 | 多来源派生列 `c_email_address \|\| c_phone` | 按 ColumnKey 字典序选 `mask_email`（符合 §3.3） |
| C8 | `COUNT(c_email_address)` 聚合 | 按设计（§3.2）对外层聚合结果套 `mask_email` |
| C9 | GROUP BY + HAVING + ORDER BY | HAVING/ORDER 留在内层，`cnt` 原样 |
| C10 | DISTINCT | `mask_name(r.c_last_name)` |
| C11 | `IN (SELECT ...)` 子查询 | 子查询明文在内层，外层脱敏 |
| C12 | customer 自连接（c1/c2） | 两个输出列各包一次 `mask_email` |
| C13 | 多层 CTE 链（q2 风格） | 两个 CTE 血缘正确落到基础列，两个脱敏列命中 |
| C14 | 常量列 + 脱敏列混排 | 常量 `r.flag` 原样（无来源），email 脱敏 |
| C15 | customer_address 的 mask_text | `mask_text(r.ca_street_name)` |

### tpcds_edge_cases.sql（E1–E7）

| # | 场景 | 实测 |
| --- | --- | --- |
| E1 | 重复输出别名 | ✅ exit=1，`REWRITE_ERROR: cannot wrap a query whose output contains duplicate column name 'v'` |
| E2 | 未声明表 | ✅ exit=1，`VALIDATION_ERROR: Object 'nosuch_table' not found` |
| E3 | 未声明列 | ✅ exit=1，`VALIDATION_ERROR: Column 'c_nosuch' not found in any table` |
| E4 | 歧义列引用（customer/store_sales 同名 `c_customer_sk`） | ⚠️ exit=0，Calcite 静默解析（见下"已知偏差"） |
| E5–E7 | INSERT / UPDATE / DELETE | ✅ exit=1，`UNSUPPORTED_STATEMENT`，诊断含语句种类 |

整体运行 edge_cases 文件：exit=1（多语句原子性，首条失败处中止，不输出部分结果）。

### 已知偏差（第一版范围外，记录为待办）

- **E4 歧义列引用未失败**：Calcite 1.42 校验器对跨表同名的未限定列引用静默解析（PostgreSQL 会在执行时报 `column reference is ambiguous`）。风险已评估为低：工具生成的 SQL 完整保留原始引用——透传时引擎执行报错；包装时歧义引用留在内层原样传给引擎，不会产生"错误脱敏"。严格的歧义检测需要自建作用域级校验（新功能，列入待办）。

## 第三轮：深层结构与健壮性用例（2026-09-05）

```bash
java -jar target/sql-mask.jar --metadata tpcds/metadata.yaml \
  --input tpcds/queries/tpcds_deep_cases.sql --output tpcds/out/tpcds_deep_masked.sql   # exit=0
# open_cases / robustness / crlf / oneline / atomic_fail 见下表逐条说明
```

### tpcds_deep_cases.sql（D1/D3/D5–D12，整文件 exit=0，全部通过）

| # | 结构 | 实测结论 |
| --- | --- | --- |
| D1 | 三层嵌套派生表 + 逐层列重命名 | 血缘穿三层，`mask_email(r.email_address)`（注：首版用例外层引用写错列名导致失败，属用例笔误非工具缺陷） |
| D3 | CASE WHEN 输出（来源 c_phone / 纯字面量） | 来源分支命中策略 → 整列套 `mask_phone`；字面量 CASE 无来源原样 |
| D5 | CTE 列名列表 `WITH t(x, y) AS` | 血缘按序号映射，`x`→mask_email、`y`→mask_phone |
| D6 | FROM 子查询内 `WITH` | 内层 WITH 完整保留，`mask_email(r.e)` |
| D7 | USING 自连接 | 两个输出列各包一次对应 UDF |
| D8 | LEFT JOIN | `mask_email` + 无策略列原样 |
| D9 | `GROUP BY ROLLUP` + `NULLS LAST` | 保留内层，脱敏列套 UDF |
| D10 | 引擎侧未知函数 `mask_custom(...)` | 内层透传未知函数，外层按输出列血缘套 `mask_email`（工具不校验 UDF，符合 §3.2） |
| D11 | ORDER BY 输出别名 + OFFSET + FETCH | 子句全部留在内层，别名保留 |
| D12 | 全引号限定名 `tpcds."public"."customer"` | 正常解析与脱敏 |

### tpcds_open_cases.sql（行为分类）

| # | 结构 | 实测 |
| --- | --- | --- |
| D2 | SELECT 列表中的相关标量子查询 | ✅ 清晰失败：`LINEAGE_UNKNOWN: output column 1 ('order_cnt') has no safely traceable origin`（符合规格 UNKNOWN→失败，不猜测来源） |
| D4 | 窗口函数 `ROW_NUMBER/RANK OVER` | ✅ `rn`（无操作数）无来源原样；`rnk`（按 c_email_address 分区）按血缘套 `mask_email(r.rnk)`（符合 §3.2 整列套 UDF 语义） |

### 健壮性用例

| # | 场景 | 实测 |
| --- | --- | --- |
| R1 | 混合多语句（包装/透传/CTE/聚合） | ✅ exit=0，输出语句顺序与输入一致 |
| R2 | 行注释 + 跨行块注释 | ✅ 正常解析 |
| R3 | CRLF（Windows 换行）输入文件 | ✅ 正常切分 |
| R4 | 单行多条语句（分号分隔） | ✅ 正常切分 |
| R5 | 3 语句中第 2 条为 INSERT | ✅ exit=1，且 `--output` 文件未被创建（无部分输出） |
| R6 | 引号大小写敏感列 `"Phone"`（未声明） | ✅ exit=1，`VALIDATION_ERROR: Column 'Phone' not found in any table`（符合 §8.2 不猜测大小写） |
| R7 | `WITH RECURSIVE` 关键字但非递归体 | ✅ 按普通 CTE 展开并正常改写（无自引用即无环） |

## 第四轮：写入语句用例（2026-09-05，针对并行实现新增的 INSERT / CTAS 能力）

> 注：本轮测试期间实现被并行任务更新（`SqlMaskRunner` 重构为 `RewriteEngine`，`PostgresqlDialectAdapter` 新增 INSERT / CREATE TABLE...AS 支持，pom 增加依赖）。以下结论基于 08:36 构建的 jar。用例文件：`tpcds/queries/tpcds_write_cases.sql`（W1/W2/W3/W5/W6）及逐条 `--sql` 运行的补充场景。

| # | 场景 | 实测 |
| --- | --- | --- |
| W1 | `INSERT INTO customer (列名列表) SELECT ...` 含脱敏列 | ✅ 源查询按血缘包装后重组为 `INSERT INTO customer (...) SELECT mask_email(r.c_email_address) AS ... FROM (<orig>) AS r` |
| W2 | `INSERT INTO customer SELECT * FROM customer`（无列名列表） | ✅ 按表列序展开，4 个脱敏列各包一次 UDF |
| W3 | `INSERT ... VALUES` 纯字面量 | ✅ 原样透传（无基础列来源） |
| W4 | `INSERT ... VALUES` 内藏子查询 | ✅ 清晰拒绝：`UNSUPPORTED_STATEMENT: queries hidden inside INSERT ... VALUES are not supported`（不会漏脱敏） |
| W5 | `CREATE TABLE ... AS SELECT` 含脱敏列 | ✅ `CREATE TABLE contact_backup AS SELECT mask_email(...) ... FROM (<orig>) AS r` |
| W6 | `CREATE TABLE IF NOT EXISTS ... AS SELECT` | ✅ 保留 IF NOT EXISTS 并正确改写 |
| W7 | `CREATE VOLATILE TABLE ... AS SELECT` | ✅ 清晰拒绝：`unsupported CREATE TABLE variant (REPLACE / VOLATILE / SET / MULTISET)` |
| W8 | `INSERT INTO nosuch_table SELECT ...`（目标表未声明） | ❌ **缺陷**：exit=0 静默成功。新 INSERT 路径只校验了源查询，未校验目标表是否在 metadata 中声明，违反规格 §11"表未在 YAML 中声明必须失败" |
| W9 | UPDATE / DELETE | ✅ 仍清晰拒绝 `UNSUPPORTED_STATEMENT` |

### 第四轮其他观察（非缺陷）

- 测试中途出现一次 `NoClassDefFoundError: AbstractFuture$Failure$1`：为并行任务同时构建、CLI 读到半写入状态 jar 的瞬态假象；重跑三次均正常。并发构建期间 jar 内容以构建完成时间为准。
- 既有单测 2 个失败（`SqlMaskRunnerTest.failsAtomicallyOnUnsupportedStatement`、`SqlMaskIntegrationTest` 中 1 个）：均断言旧版"INSERT 必须失败"的行为，与新增的 INSERT 支持冲突，需实现方更新断言。
- 原有查询用例（S/C/D/E 系列）在新构建上回归全部保持通过；E5（INSERT 预期失败用例）按新行为改为成功，该文件中其余错误场景不受影响。

## 已知范围外行为（第一版设计如此）

- 根级 `UNION`/`INTERSECT`/`EXCEPT` 语句：给出 `UNSUPPORTED_STATEMENT` 清晰诊断（CTE 内部的 `UNION ALL` 支持，见 S2）。
- `WITH RECURSIVE` 自引用：给出 `LINEAGE_UNKNOWN` 清晰诊断（非自引用的 `WITH RECURSIVE` 关键字查询按普通 CTE 展开处理）。
- 输出格式为 Calcite 重新渲染的 SQL（`LIMIT` 渲染为 PostgreSQL 兼容的 `FETCH NEXT n ROWS ONLY`，`BETWEEN` 渲染为显式 `BETWEEN ASYMMETRIC`，比较字面量可能补 `CAST`），不保留原始排版与注释。



- 根级 `UNION`/`INTERSECT`/`EXCEPT` 语句：给出 `UNSUPPORTED_STATEMENT` 清晰诊断（CTE 内部的 `UNION ALL` 支持，见 S2）。
- `WITH RECURSIVE` 自引用：给出 `LINEAGE_UNKNOWN` 清晰诊断（非自引用的 `WITH RECURSIVE` 关键字查询按普通 CTE 展开处理）。
- 输出格式为 Calcite 重新渲染的 SQL（`LIMIT` 渲染为 PostgreSQL 兼容的 `FETCH NEXT n ROWS ONLY`，`BETWEEN` 渲染为显式 `BETWEEN ASYMMETRIC`，比较字面量可能补 `CAST`），不保留原始排版与注释。

