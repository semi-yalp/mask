# sql-mask Go 版移植设计(Phase 1:内核)

- 日期:2026-09-27
- 分支:`go`(主线,纯 Go 手写解析器)/ `go-antlr`(ANTLR4 独立全套)
- 状态:设计已通过评审,待产出实施计划

## 1. 背景与目标

本仓库(`io.sqlmask` / sql-mask)是 Java 11 + Spring Boot 的多模块 Maven 项目:基于
Apache Calcite 的多引擎 SQL 脱敏改写服务,支持 PostgreSQL / Trino / MySQL / Hive /
Spark 五种方言。目标是在同一仓库内实现一份 **Go 语言版**,最终能力与 Java 版对齐,
采取"内核优先、分期对齐"的策略:

- **Phase 1(本 spec 范围)**:SQL 解析器 + 改写引擎内核(mask-engine 等价物)+
  mask-core 等价物(改写 API、CLI、instance 模式、--pull-metadata)。
- **Phase 2(展望,另行 spec)**:policy-server(8081)、metadata(8082)、query(8083)、
  audit / auth / common 全量对齐、risk-server、内置管理页面。

### 1.1 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| Phase 1 范围 | 解析器 + 引擎内核 + mask-core(rewrite API / CLI / instance / pull-metadata) |
| 解析器技术路线 | 双分支并行:纯 Go 手写(`go` 分支,主线)与 ANTLR4 Go runtime(`go-antlr` 分支)各自独立实现 |
| 分支组织 | **两分支独立全套**:各自维护自己的 AST 与引擎,不共享实现代码;共享测试语料与验收契约文件(纯数据) |
| 行为兼容标准 | **语义等价**:产物 SQL 语义等价即可,文本形式不求逐字节一致;错误码一致,文案可异 |
| 校验器深度 | **接受/拒绝边界对齐**:Java 接受并改写的查询 Go 必须接受(产物语义等价);Java 拒绝的 Go 也拒绝(错误码一致) |
| instance 模式 | Phase 1 包含:HTTP 调现有 Java policy-server 拉编译后配置 + LRU 缓存 + 轮询 + stale-but-available + admin 刷新端点 |

### 1.2 Java 版架构事实(移植的"契约源")

以下事实经代码核实,是 Go 版需要对齐的行为契约:

- **改写主流程**:`RewriteEngine.rewrite(metadataYaml, sql, dialect)` → 逐语句:
  ① `SqlStatementSplitter` 拆分;② 解析(`DialectAdapter.parse`,只放行
  SELECT / WITH..SELECT / INSERT..SELECT / CTAS;Hive/Spark 另放行 INSERT OVERWRITE,
  kind 保持 INSERT);③ 行过滤注入 `RowFilterRewriter.apply`(校验之前);
  ④ 校验 + 转 Rel(unparse 快照 → `CteExpander` 内联 CTE → SqlValidator +
  SqlToRelConverter → `ValidatedSql`);⑤ 血缘 `LineageAnalyzer.analyze`;
  ⑥ `RewritePlan.of(lineage, PdpMaskSelector(PolicyEngine))`;
  ⑦ `SqlRewriteService.rewrite` 输出。
- **改写产物结构 = 字符串模板拼接**,非 AST 操作:
  `SELECT udf(r.col, args) AS col ... FROM (\n<校验前 unparse 快照>\n) AS r [(列清单)]`。
  无列命中策略时原样返回;无名列(EXPR$N)在包裹别名表中改名 `mask_col_N`;
  Hive/Spark 不支持 `FROM (...) AS r (a,b)` 别名清单语法即拒绝
  (`DialectCapabilities.STRICT_NO_ALIAS_LIST`);血缘 UNKNOWN 整句 fail-closed;
  多来源列按规范化列 key 字典序取唯一命中,否则拒绝;标量子查询出现在投影中
  → 全部输出列判 UNKNOWN 并拒绝;空集 = 常量列放行。
- **UDF 名完全来自 YAML**(`policies.<名>.udf`),无内置注册表;参数经
  `SqlLiteral.toSqlString(方言)` 渲染防注入;标识符引号风格由各方言
  IdentifierPolicy 决定。
- **行过滤 = 子查询包裹注入**:把受控表的 FROM 引用替换为
  `(SELECT * FROM <表> WHERE <条件>) AS <原别名>`;对 AST 递归改写
  (SELECT/WITH/集合运算/JOIN/AS/表达式内子查询,镜像 SQL 作用域);CTE 名遮蔽
  基表;注入点重新 parse 避免共享子树;注入后拒绝 4 段限定列引用;未知 FROM
  形态 fail-closed。条件合法性由 `RowFilterRegistry` 预检:按
  `SELECT * FROM t WHERE <cond>` 解析 → 白名单(仅 AND/OR/比较/IS NULL/IN 等
  布尔构造,禁子查询/函数/动态参数)→ 再过校验器;缓存模板,失败统一 CONFIG_ERROR。
- **方言差异**(共用一个语法,差异只在配置):
  - 引号:PG/Trino `"`(double quote);MySQL/Hive/Spark `` ` ``(back tick)。
  - unquoted 大小写:PG/Trino/Hive/Spark 转 lowercase;MySQL 保持原样。
  - caseSensitive:PG/Trino true;其余 false。
  - conformance:PG/Trino = DEFAULT;MySQL = MYSQL_5;Hive/Spark = LENIENT,
    且 `allowInsertOverwrite=true`(其余方言 false);五方言 `allowTopN` 均为 false
    (TOP(n) 语法 fork 中存在但被 conformance 关闭)。
  - 校验器侧差异:schemaPathStyle(MySQL/Hive/Spark 额外支持 2 段名)、
    TypeResolver、IdentifierPolicy、函数表(PG 表 / MySQL 表 / 大小写不敏感算子表)。
  - unparse 方言:PG 用 Calcite 原生;Trino/MySQL/Hive/Spark 为 fork 自定义,
    共同点是拦掉 `BETWEEN ASYMMETRIC`。
- **血缘机制**:Java 用 Calcite `RelMetadataQuery.getColumnOrigins(relRoot, ordinal)`
  (依赖 SqlToRelConverter)。前置:CTE 已内联为派生表。`null` → UNKNOWN;
  空集 → 常量;投影含标量子查询(`RexSubQuery`)→ 全部 UNKNOWN。
- **API 契约**(mask-core):
  - `POST /api/rewrite`:请求 `{metadataYaml, policyYaml, instance, sql, dialect,
    user, groups}`(metadataYaml 与 instance 二选一);响应
    `{statements: [{ordinal, originalSql, rewrittenSql, masked, rowFiltered, kind
    (SELECT|INSERT_SELECT|CTAS), inheritedColumns, inheritedTables}], rewrittenSql}`。
  - 其余端点:`/api/rewrite/instances/{name}`、`/api/config/parse`、
    `/api/policies/parse`、`/api/metadata/pull`、`/api/audit/events`(Phase 2)、
    `/admin/cache/refresh`(需 `X-Api-Key: $SQLMASK_ADMIN_API_KEY`,未配置默认拒绝)。
  - instance 模式:`POLICY_SERVICE_URL` + `POLICY_SERVICE_API_KEY` 接入;进程内
    LRU 256 按主体缓存;每 `POLICY_SERVICE_POLL_INTERVAL_MS`(默认 30000)轮询;
    策略服务不可用时继续用缓存(stale-but-available),无缓存时 fail closed。
  - CLI 参数全集:`--metadata --pull-metadata --engine --host --port --database
    --user --policies --groups --password --schema --include-views --strict
    --sslmode --connect-timeout --sql --input --output --dialect --instance
    --policy-service`(rewrite 与 --pull-metadata 互斥);无参数时启动 HTTP 服务。
  - 统一错误体 `ApiError{code, message, details}`;统一 ApiKeyFilter(mask-common)。
- **配置格式**:
  - 旧格式:`metadata.yaml` 顶层 `metadata.tables[]`(columns[{name,type}]、
    rowFilter?)、`policies:`(名→{udf, arguments[]})、`columns:`(绑定列表
    {catalog, schema, table, column, policy, inheritOnCopy?})。
  - 新格式:Ranger 式 `policies.yaml` 顶层 `policies[]`:{name, enabled, priority,
    resources[{catalog,schema,table,column 支持 glob}],
    dataMaskItems[{users/groups, udf, arguments}] | rowFilterItems[{users/groups,
    filterExpr}]};与 metadata 内嵌 sections 互斥(同时非空显式报错)。
  - 元数据采集:只读系统目录(PG `pg_catalog`,MySQL/Trino `information_schema`),
    仅 PG/MySQL/Trino 三引擎;`NetworkGuard` 拦 link-local 出网。
- **测试形态参考**:Java 侧约 345 个 @Test,golden 输入语料含 TPC-DS 12 查询
  (`mask-engine/tpcds/`)与 `mask-engine/src/test/resources/golden/` 22 个 .sql;
  rowfilter 独立 55 用例;解析器差分测试(BabelEquivalenceTest)28 用例。

## 2. 总体布局与里程碑

- 同仓库承载:根目录新增 `go.mod` 与 Go 包树,与 Java 模块目录并存。
  模块路径建议 `io.sqlmask/go`(以实施计划定稿为准),包结构按内核分层:
  `parser`(解析前端)、`ast`、`validate`、`lineage`、`rowfilter`、`rewrite`、
  `dialect`、`config`、`policy`(Ranger 式加载编译)、`introspect`(采集)、
  `server`(HTTP)、`cli`;入口 `cmd/sqlmask`。
- `go-antlr` 分支:在 Go 骨架建立后分叉,独立实现自己的 AST 与引擎;两分支共享
  测试语料与验收契约文件(数据文件,非代码)。

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 | 词法 + 语法分析器(四形态语句子集)+ AST + 五方言开关 | 解析单测;与 Java 版差分:接受集一致 |
| M2 | 校验器(名称解析/类型/函数表)+ AST 级血缘 + fail-closed | 接受/拒绝边界对齐用例全绿 |
| M3 | 行过滤注入 + 外层包裹改写 + 五方言 unparse | 语料语义等价校验全绿 |
| M4 | YAML 配置(旧格式 + Ranger 式)+ CLI(含 --pull-metadata) | CLI 端到端 |
| M5 | HTTP 服务(/api/rewrite 等 + instance 模式) | 与 Java 服务互换验证 |

每个里程碑以可运行 + 测试通过收口;M1 起即建立与 Java 版的差分护栏。

## 3. 解析器(`go` 分支:手写递归下降)

- 移植 fork 语法的**语义**而非代码:语法核心一份,方言差异全部表达为解析配置
  (引号符、unquoted casing、caseSensitive、conformance 开关:
  INSERT OVERWRITE 仅 Hive/Spark 放行,TOP(n) 全方言关闭但语法需识别并给出
  一致的拒绝行为)。
- 语句级覆盖聚焦引擎放行的四形态(SELECT / WITH..SELECT / INSERT..SELECT / CTAS,
  Hive/Spark 加 INSERT OVERWRITE);其余语句在解析层即产出与 Java 一致的错误码。
  表达式语法按 TPC-DS 语料全覆盖(窗口函数、集合运算、CASE、CAST、EXISTS、
  IN、BETWEEN、子查询等)。
- JavaCC LOOKAHEAD(2) 的歧义边界用与 Java 版的差分测试兜住;识别不了的边界
  记录到契约文件。
- fork 自定义语法仅两处,按语义重写:`INSERT OVERWRITE [TABLE]`(拒绝
  PARTITION/DIRECTORY)与 `SELECT TOP (n)`(映射 fetch,拒绝 PERCENT/WITH TIES)。
- 解析错误携带源位置(行/列),错误码与 Java 一致(PARSE_ERROR),文案可异。

## 4. AST 与方言输出

- AST:Go 结构体 + kind 标签的节点接口,携带源位置;方言无关。节点集合 =
  四形态语句 + RowFilter 条件白名单构造 + TPC-DS 语料出现的表达式构造。
- unparse:按方言实现(标识符引号策略、字面量按类型渲染、关键字、`BETWEEN`
  不带 ASYMMETRIC),**只求可回解析、方言合法**,不求与 Calcite 文本一致。
- 改写内层快照 = 校验前对 AST 的 unparse(对齐 Java 流程位置,使行过滤注入的
  语义与 Java 一致)。

## 5. 校验器(接受/拒绝边界对齐)

- 自建轻量校验器:作用域与名称解析(FROM 别名、关联子查询、CTE 名遮蔽基表——
  行过滤注入依赖此语义)、每方言大小写策略、2 段/3 段名解析
  (MySQL/Hive/Spark 支持 catalog+schema 与 schema+table 两种 2 段形式)。
- 类型推断:足以驱动函数/算子签名匹配与血缘类型;函数表移植的是**成员集合**
  (每方言哪些函数存在、参数个数/类型兼容规则),对齐 Java 的
  `PostgresqlFunctions.TABLE` / `MysqlFunctions.TABLE` / `CaseInsensitiveOperatorTable`。
- 边界用例从 Java 测试套件反推 + 差分语料生成;错误码一致(CONFIG_ERROR 等),
  文案可异。

## 6. 血缘(AST 级,替代 RelMetadataQuery)

- CTE 内联后,按输出列逐列穿过 SELECT 层推导来源表列
  (catalog/schema/table/column + isDerived):
  - 列引用 → 直接来源;表达式 → 底层列来源(derived 标记);
  - 字面量/常量 → 空集(NO_ORIGIN,放行);
  - JOIN(含 USING/NATURAL)、集合运算(来源并集)、聚合取参数来源;
  - 投影含标量子查询 → 该语句所有输出列 UNKNOWN,整句拒绝;
  - 任何未知构造 → UNKNOWN fail-closed(LINEAGE_UNKNOWN)。
- 多来源列按规范化列 key 字典序取唯一命中(PdpMaskSelector 语义),否则拒绝。
- **这是全项目正确性风险最高的一块**,必须与 Java 版差分测试(§10)托底。

## 7. 行过滤注入与改写输出

- `RowFilterRewriter`:AST 级把受控表的 FROM 引用替换为
  `(SELECT * FROM t WHERE <条件>) AS <原别名>`;镜像 SQL 作用域递归处理
  SELECT/WITH/集合运算/JOIN/AS/表达式内子查询;CTE 名遮蔽基表;注入条件
  先解析为独立节点(不共享子树);注入后拒绝 4 段限定列引用;未知 FROM
  形态 fail-closed;不修改输入树,未命中语句保持原样。
- `RowFilterRegistry`:`SELECT * FROM t WHERE <cond>` 模板解析 → AST 白名单
  预检(仅 AND/OR/比较/IS NULL/IN 等布尔构造;禁子查询/函数/动态参数)→
  过校验器;缓存模板;失败统一 CONFIG_ERROR。
- `SqlRewriteService`:模板拼接外层 `SELECT udf(r.col, args) AS col ... FROM
  (<快照>) AS r [(列清单)]`;无名列改名 `mask_col_N`;Hive/Spark 拒绝别名清单
  语法(STRICT_NO_ALIAS_LIST);无列命中策略原样返回;参数字面量按方言渲染。
- 输出契约:`{ordinal, originalSql, rewrittenSql, masked, rowFiltered, kind,
  inheritedColumns, inheritedTables}` 与 Java 一致。

## 8. 配置、策略与 instance 模式

- 旧格式 metadata.yaml:yaml.v3 解析,模型对齐 `MaskingConfig` /
  `TableMetadata`(列类型 sqlTypeName/precision/scale)/ `MaskingPolicy`;
  与 SnakeYAML 的类型转换差异用测试用例固定。
- 新格式 policies.yaml:移植 mask-policy 的加载与编译(主体 users/groups 过滤、
  资源 glob、priority、dataMaskItems/rowFilterItems → 编译后 effective 视图);
  inline 通道需要它;与 metadata 内嵌 sections 互斥。
- instance 模式:按同一 HTTP 契约调 Java policy-server 拉编译后配置;LRU 256
  按主体缓存;轮询刷新(POLICY_SERVICE_POLL_INTERVAL_MS,默认 30000);
  stale-but-available;无缓存 fail closed;`POST /admin/cache/refresh`
  (可带 `{"instance": "..."}`,需 admin key,未配置默认拒绝)。

## 9. CLI 与 HTTP 服务

- CLI:标准库 `flag`(单命令,无子命令),参数全集与互斥校验对齐 Java
  (`SqlMaskApplication`);无参数时启动 HTTP 服务(默认 8080)。
- HTTP:标准库 `net/http`(Go 1.22+ 路由);端点:`/api/rewrite`、
  `/api/rewrite/instances/{name}`、`/api/config/parse`、`/api/policies/parse`、
  `/api/metadata/pull`、`/admin/cache/refresh`;统一 ApiKeyFilter(X-Api-Key)
  与 `ApiError{code,message,details}`;`/api/audit/events` 与内置页面属 Phase 2。
- 元数据采集(`--pull-metadata` / `/api/metadata/pull`):驱动用
  `jackc/pgx`(PG)、`go-sql-driver/mysql`、`trinodb/trino-go-client`;
  只读系统目录,只读连接;`NetworkGuard` 等价实现(拦截 link-local 出网,
  防云凭证外泄);产出 YAML 骨架(表/列/类型,策略人工后补)。

## 10. 测试与语义等价验收

- **语料**:复制 Java 测试输入(单测 SQL 输入、rowfilter 用例、TPC-DS 12 查询、
  golden 输入)到 Go 侧作为测试数据——只复制数据,不复用 Java 断言。
- **验收契约文件**:用 Java 版(现 jar CLI)对全语料生成
  `输入 → (接受且产物 SQL | 拒绝且错误码)` 的参考契约,以数据文件形式提交,
  两分支共用;这是"语义等价 + 边界对齐"的基准。
- **等价判定**:产物 SQL 做令牌规范化比较(空白/引号风格/大小写归一后序列
  一致);抽样在真实 PostgreSQL(内置 mask UDF)上执行 Java 产物与 Go 产物,
  结果集一致。
- **差分护栏**:从 M1 起,对语料逐条比较 Go 与 Java 的接受/拒绝判定;
  拒绝路径(CONFIG_ERROR / LINEAGE_UNKNOWN / UNSUPPORTED / PARSE_ERROR)
  逐一镜像,错误码一致。
- Go 侧测试用 `testing` 标准库,表驱动;golden 生成/校验脚本用 Go 写
  (`go test` 驱动,契约文件只读)。

## 11. go-antlr 分支与 bake-off

- `go-antlr` 独立全套:ANTLR4 语法(合并语法 + 方言开关;action 构建自有 AST,
  不直接使用 parse tree),引擎按同一设计(§4–§9)重写。
- 跑同一语料与契约文件,bake-off 评估维度:方言覆盖率、解析错误质量、
  解析性能(tokens/s)、内存、语法可维护性(改一处语法的成本)、代码量。
- 结论出来前 `go`(手写)为主线;bake-off 报告另行文档。

## 12. 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 血缘与 Calcite `getColumnOrigins` 行为对齐 | 高 | 差分语料全覆盖 + fail-closed 默认;边界用例从 Java 行为反推 |
| 校验器接受/拒绝边界 quirk(Calcite 校验器历史行为) | 高 | 契约文件 + 边界差分;不追求错误文案一致 |
| JavaCC LOOKAHEAD(2) 歧义边界导致接受集不一致 | 中 | 差分测试兜住;不一致处显式记录并裁决 |
| yaml.v3 与 SnakeYAML 行为差异(类型转换/重复键) | 中 | 差异用例固定;语义等价标准下可接受 |
| MySQL/Hive/Spark 方言 quirk(2 段名、大小写、别名清单拒绝) | 中 | profile 级测试 + 方言语料 |
| yaml.v3 等第三方依赖引入的行为漂移 | 低 | 锁版本;语料回归 |

## 13. 明确不做(Phase 1)

- policy-server / metadata / query / risk-server 服务的 Go 版(Phase 2)。
- `/api/audit/events` 与审计存储、内置管理页面(Phase 2)。
- mask-auth 的认证体系(Phase 2;Phase 1 仅保留统一 ApiKeyFilter 语义)。
- 与 Calcite 产物逐字节一致(兼容标准已定为语义等价)。
- Hive/Spark 的元数据采集(Java 版也仅支持 PG/MySQL/Trino)。
