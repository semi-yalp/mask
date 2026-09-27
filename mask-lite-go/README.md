# mask-lite-go

Java `mask-lite` 模块（PG-only 脱敏 + 行过滤改写内核）的 **Go 独立重实现**：
自包含 Go module（`io.masklite/go`），不 import `io.sqlmask/go` 的任何包，
唯一第三方依赖 `gopkg.in/yaml.v3`。设计文档见 [docs/design.md](docs/design.md)，
与 Java 版的差分证据见 [docs/differential-notes.md](docs/differential-notes.md)。

## 能力范围（与 Java mask-lite 逐项对齐）

- **列脱敏**：输出列按策略用 PG UDF 包装
  （`SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( <原始查询> ) AS r`），
  血缘分析保证只包装有安全出处的列；常量/纯标量子查询列透传；
  无法溯源的输出列整语句 fail-closed（`LINEAGE_UNKNOWN`）。
- **行过滤**：声明的表在 FROM 中被替换为
  `(SELECT * FROM t WHERE <条件>) AS t`（派生表注入，覆盖裸表/别名/JOIN
  操作数/子查询/集合分支/CTE），条件经白名单校验（仅 AND/OR/NOT、比较、
  IS [NOT] [DISTINCT] NULL、常量 IN、算术；禁函数/CAST/子查询/参数）。
- **只读**：只接受 SELECT / WITH…SELECT（可带顶层 ORDER BY/LIMIT/OFFSET/
  FETCH，裸 WITH 也接受）；其余一律 `UNSUPPORTED_STATEMENT`。
- **interval**：裸 `INTERVAL '1 day'`、`'str'::interval` 与
  `CAST('str' AS INTERVAL)` 在解析期规范化为等值限定词形式；跨族混合、
  分数月、@/ago 装饰、前导字段 >2 位与 typmod/字段范围形态 fail-closed 拒绝。
- **策略来源**：legacy 元数据 YAML 内嵌策略（`columns` 绑定 / `rowFilter` /
  `policies` UDF 声明），对所有人无条件生效；无主体（Subject）维度。

裁剪掉的部分（相对 mask-engine）：MySQL/Trino/Hive/SparkSQL 方言、JDBC 元数据
采集、写语句改写与复制继承、Ranger 式 `policies.yaml` 与主体编译。渲染器对齐
Calcite PG unparse（`LIMIT→FETCH NEXT`、`BETWEEN ASYMMETRIC`、规范大写函数名、
简单 CASE 归一 searched 形态等），TPC-DS 99 条产物与 Java 版**逐文件 diff 一致**。

## 使用

```go
import "io.masklite/go/masklite"

mask, err := masklite.FromYamlFile("metadata.yaml")
rewritten, err := mask.Rewrite("SELECT c_email_address FROM customer")
stmts, err := mask.RewriteStatements(sqlText) // []StatementRewrite{Ordinal, OriginalSQL, RewrittenSQL, Masked, RowFiltered}
```

CLI（退出码 0/1/2）：

```bash
go build ./cmd/masklite
./masklite --metadata metadata.yaml --sql 'SELECT c_email_address FROM customer'
./masklite --metadata metadata.yaml --input queries.sql
```

YAML 形态（与历史 mask-core legacy 格式兼容）：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"
      columns:
        - { name: id, type: bigint }
        - { name: phone, type: varchar(20) }
        - { name: status, type: varchar(10) }
policies:
  mask_phone:
    udf: mask_phone
    arguments: [3, 4]
columns:
  - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_phone }
```

## 包结构

| 包 | 职责 |
|---|---|
| `maskerr` | 统一错误码（对齐 Java `SqlMaskException.Code`） |
| `dialect` | PG 方言 Profile 与 `ByName` 拒绝 |
| `split` | 顶层分号语句拆分（逐语义移植 `SqlStatementSplitter`） |
| `lexer` | PG 词法（E''/dollar 引用/嵌套块注释/`::`） |
| `ast` | 只读语句面 AST |
| `parser` | 递归下降解析（TPC-DS 语料覆盖面：ROLLUP/窗口/集合运算/括号集合运算） |
| `render` | Calcite PG unparse 风格渲染 |
| `metadata` | 表/列模型、规范化列键、类型词表 |
| `config` | legacy YAML 加载与校验（路径级报错、重复键检测） |
| `policy` | 列绑定决策与多来源 tie-break（对齐 `PdpMaskSelector`） |
| `rowfilter` | 白名单 registry + 派生表注入 rewriter |
| `lineage` | CTE 内联、作用域解析、输出列 origins、标量子查询两阶段 |
| `rewrite` | 管线编排与外层包装器 |
| `masklite` | 门面；`cmd/masklite` 为 CLI |

## 测试与验收

```bash
go build ./... && go vet ./... && go test ./...
```

- Java `MaskLiteTest` 5 用例逐字移植（`masklite/masklite_test.go`）。
- TPC-DS 99 条离线回归硬断言：99/99 success、15 masked、91 rowFiltered
  （与 Java 打印值一致；语料自包含于 `testdata/tpcds/`）。
- 各包表驱动单测：拆分/词法/解析渲染 golden/白名单拒绝族/血缘 fail-closed/
  CTE 作用域/配置错误路径与类型词表。
