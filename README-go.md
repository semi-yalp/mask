# sql-mask Go 版(M1:解析器与 AST)

Java 版(sql-mask,基于 Apache Calcite)的 Go 语言移植,Phase 1 按里程碑推进。
本仓库根的 Go module(`io.sqlmask/go`)当前完成 **M1**:语句拆分器、五方言
词法器、SQL AST、手写递归下降解析器、classify 移植,以及与 Java 版的
**解析期差分护栏**(110 条契约全对齐)。

- 设计 spec:`docs/superpowers/specs/2026-09-27-go-port-design.md`
- M1 计划:`docs/superpowers/plans/2026-09-27-go-port-m1-parser.md`
- 分支:`go`(主线,纯 Go 手写解析器);`go-antlr`(ANTLR4 独立全套,待启动)

## M1 范围

| 包 | 职责 |
|---|---|
| `maskerr` | 错误码(与 Java `SqlMaskException.Code` 14 值逐字对齐) |
| `dialect` | 五方言 Profile(postgresql/trino/mysql/hive/sparksql):引号、大小写折算、conformance(Default/MySQL5/Lenient)、INSERT OVERWRITE/TOP 开关、能力位 |
| `split` | 顶层分号语句拆分(逐语义移植 Java `SqlStatementSplitter`) |
| `lexer` | 方言感知词法器(JavaCC 实测口径:Tab 8 制表位、不嵌套块注释、`//` 行注释、数字最长匹配、前缀串 N'/x'/U&/_charset 单 token) |
| `ast` | SQL AST 全量节点(语句/查询/表达式/表引用,`Pos` 携带源位置) |
| `parser` | 手写递归下降解析器:表达式优先级链、SELECT/FROM 全覆盖、集合运算、ORDER BY/LIMIT/OFFSET/FETCH(conformance 门控)、WITH/INSERT/CTAS/INSERT OVERWRITE、TOP(n) 按方言拒绝 |
| `engine` | `Classify`(逐字镜像 Java `AbstractCalciteDialectAdapter.classify`);`CheckCreateTableVariantForCompose`(M3 compose 钩子) |
| `contract` | 差分护栏:Java CLI runner、语料装载、判定、比对;`testdata/contract/parse-verdicts.json` 为提交的参考契约 |

M2(校验器+血缘)、M3(行过滤+改写输出)、M4(YAML 配置+CLI 全集)、
M5(HTTP 服务+instance 模式)另行出计划。

## 兼容标准(语义等价)

- Java 接受并改写的查询,Go 解析期同样接受;Java 拒绝的,Go 以**相同错误码**
  在对应阶段拒绝(文案可异)。
- 验收 = 契约差分:`testdata/contract/parse-verdicts.json` 记录 Java 版对
  全语料(63 条 mask-engine 场景语料 + 47 条 Go 侧构造语料)的判定,Go 每次
  `go test` 现算比对,零 Mismatch。

## 差分护栏用法

```bash
go test ./...                       # 离线比对提交的契约(默认门禁)
CONTRACT_LIVE_JAVA=1 go test ./contract/   # 另加实时对 jar 比对(~86s)
# 语料或解析器变更后重新生成契约(需要 java 与已构建的 mask-core jar):
mvn -pl mask-core -am package -DskipTests -q   # 若 jar 不存在
go run ./cmd/contractgen
```

## 调试入口(M1)

```bash
go run ./cmd/sqlmask --parse <file.sql> --dialect postgresql
# 每条语句输出 "#N ok *ast.Select" / "#N PARSE_ERROR: ..." / classify 错误
```

## 已知口径与 watchlist(后续里程碑复核)

以下为 jar 实测确认的已知差异/留档项(错误码一致或语料零命中;逐差异
档案见 `docs/superpowers/plans/m1-differential-notes.md`,各任务报告详见
`.superpowers/sdd/2026-09-27-go-port-m1-parser/`,包内注释同口径):

- 词法层直接报 PARSE_ERROR 而 Java 词法放行、解析期报错(错误码同):
  未闭合字符串、引号标识符内裸换行、BackTick 方言下 `"`、部分白名单外字符。
- Java 接受但 M1 未实现(spec 决策 5 兜底条款,语料零命中):`FROM LATERAL`、
  `FROM UNNEST`/`ARRAY[...]`、`GROUPING SETS`、无条件 JOIN/CROSS+NATURAL 带
  条件、`DESCRIBE` kind 命名差异、CTAS 垃圾类型跳读、残片 `COMMIT x` 族已
  对齐为 PARSE_ERROR(良构形态 UNSUPPORTED)。
- `N'…'`/`x'…'` 等前缀串:M3 unparse 需按原文重写前缀(Java 折算
  `N'x'` → `_ISO-8859-1'x'`)。
- gofmt 对注释内 `''` 的 Unicode 引号规范化告警(注释级,不影响代码)。

## 构建

Go 1.27+,零第三方依赖(M1):

```bash
go build ./... && go vet ./... && go test ./...
```
