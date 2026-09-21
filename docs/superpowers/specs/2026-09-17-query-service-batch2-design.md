# 统一查询服务批 2 设计方案（Hive / SparkSQL 方言与执行）

日期：2026-09-17
状态：已经评审通过（批 2 设计两决策点确认：执行优先不采集 + hive-jdbc 单驱动）

## 1. 背景与目标

批 1（`docs/superpowers/specs/2026-09-17-query-service-design.md`）已交付四引擎
（PostgreSQL / MySQL / Trino / StarRocks）的统一查询数据面。本 spec 是其「引
言决策表」中声明的**批 2**：把 Hive 与 Spark/SparkSQL 纳入同一查询服务。

已确认的关键决策：

| 决策点 | 结论 |
|---|---|
| 元数据采集 | **不实现**——Hive/Spark 实例的表结构走 YAML 导入（mask-metadata 已支持，`ConnectionInfo` 可缺省）；采集端点对这两方言明确拒绝 |
| JDBC 驱动 | **org.apache.hive:hive-jdbc 单驱动**覆盖两引擎（Spark ThriftServer 用同一 Hive 协议）；依赖瘦身 exclude |
| 方言 profile | mask-core 新增 `hive` / `sparksql` 两个（对齐既有 `AbstractCalciteDialectAdapter` 体系） |
| 继承（零改动） | 护栏链（并发/只读/超时/maxRows+1 截断/流式/断连取消）、错误模型与透传、QUERY 审计、实例模型结构、`GET /api/effective` 主体策略、StarRocks 经验（复刻「兼容子集以 golden 清单为准」口径） |

## 2. 范围与非目标

### 范围

1. mask-core：`hive` / `sparksql` 方言 profile（标识符策略、类型解析器、渲染方言、
   引擎特有写语句校验）+ 注册表/白名单扩展；
2. mask-metadata：方言白名单扩两个值；采集对两方言**明确拒绝**（fail-closed 带
   清晰消息）；
3. mask-query：`QueryEngine` 加两引擎（hive-jdbc 驱动 URL、缺省端口 10000）；
4. 测试与文档：方言单测（类型/标识符/包装渲染 golden）、metadata 拒绝与白名单测试、
   URL 单测、golden 验收清单两节、README 更新。

### 非目标（显式不做）

- Hive/Spark 元数据采集（pull-metadata）；
- Kerberos / LDAP 认证与 ZooKeeper 服务发现连接串（遇现实需求另立）；
- Hive `INSERT OVERWRITE` 等专有写语句的**专有解析**（Calcite 能解析则按批 1
  写语句语义处理，不能解析自然落 `PARSE_ERROR`，不为此写解析器扩展）；
- 跨引擎转写（口径同批 1：输入与输出同为该实例方言）；
- Hive/Spark 真库 IT 入默认测试套件（docker 镜像重，维持人工验收，与批 1 非 PG 引擎同口径）。

## 3. 方言 profile（mask-core）

沿用批 1 的 `AbstractCalciteDialectAdapter` 体系，新两组（每组按既有文件名模式：
`Hive`/`SparkSql` 前缀的 Adapter / IdentifierPolicy / TypeResolver / UnparseDialect），
注册进 `DialectRegistry` 与 `DialectProfiles.byName`。

| 维度 | Hive | Spark SQL |
|---|---|---|
| 标识符引号 | 反引号（包装层/按需一律反引号，同 MySQL 口径） | 同左 |
| 未引号标识符 | 折叠小写（Hive 存储语义） | 折叠小写（Spark 未引号折叠小写） |
| 大小写匹配 | 大小写不敏感（同 MySQL 口径） | 同左 |
| 两段名 `db.table` | 支持（db = 声明的 schema；多 catalog 同名歧义 → `VALIDATION_ERROR`） | 同左 |
| 三段名 `catalog.db.table` | 支持 | 支持（Spark 实际是 `catalog.db.table` 或 `db.table`；catalog 常为 `spark_catalog`，以声明为准） |
| 非限定名同名冲突 | 按 schema 名字母序静默首匹配（同批 1 口径） | 同左 |
| 渲染基底 | Calcite `HiveSqlDialect` | Calcite `SparkSqlDialect`（若该版本 calcite-core 无，fallback 自定义 Conformance，实现时确认） |
| 需要 UDF 自解析 | 是（不透明标量函数路径，同批 1） | 同左 |

类型集（TypeResolver，声明于 metadata 的 `type:`）：

- **Hive**：`tinyint`、`smallint`、`int`、`bigint`、`float`、`double`、`decimal(p,s)`、
  `string`、`varchar(n)`、`char(n)`、`boolean`、`date`、`timestamp`、
  `binary`；
- **Spark SQL**：`tinyint`、`smallint`、`int`、`bigint`、`float`、`double`、
  `decimal(p,s)`、`string`、`varchar(n)`、`char(n)`、`boolean`、`date`、
  `timestamp`、`binary`（Spark 3.4+ 的 `timestamp_ntz` 等清单外类型不支持，
  声明确认支持清单外的类型 fail-closed 拒绝——**不做**批 1 里的降级 varchar 路径，
  两方言的复杂类型 `array`/`map`/`struct` 一律拒绝声明）；
- 错误消息带方言名前缀，与既有 TypeResolver 一致。

引擎特有安全失败（写语句分支，对齐批 1 的方言校验方法）：

- **Hive**：带表属性/分区/`STORED AS`/`ROW FORMAT` 的 CTAS 变体 → `PARSE_ERROR`
  （解析器语法不收这些形态，只有裸 `CREATE TABLE [IF NOT EXISTS] t AS SELECT` 可用）；
- **Spark**：`USING` 子句 / 表属性 / `PARTITIONED BY` 的 CTAS 变体 → `PARSE_ERROR`；
- 读语句失败清单沿批 1 通用规则（`WITH RECURSIVE`、输出关联标量子查询等）。

> 裁决注记（2026-09-18 全分支评审）：上述形态不进解析器语法，错误码为 `PARSE_ERROR`；
> REPLACE/VOLATILE/SET/MULTISET（babel 可解析）保持 `UNSUPPORTED_STATEMENT`。

包装与行过滤：**零改动复用**——外层形态 `SELECT udf(r.col, args) AS out FROM (原始查询) AS r`
两引擎原生支持；行过滤注入形态 `(SELECT * FROM t WHERE ...) AS r` 同样原生；错误与
fail-closed 路径全部继承。

## 4. 实例模型（mask-metadata）

- `MetadataService.normalizeDialect` 白名单扩 `hive` / `sparksql`（`DialectProfiles.byName`
  是实际校验方，错误消息同步列出支持值）；
- `engine` 规则不变：engine 与 dialect 同值（hive↔hive、sparksql↔sparksql），走
  批 1 的派生路径，无需显式 engine；`starrocks` 规则不受影响；
- **采集拒绝**：`CollectController`/introspector 注册表对 `hive`/`sparksql` 报
  `CONFIG_ERROR`，消息明确：「collection is not supported for hive/sparksql;
  import table structures via instance YAML import instead」——「执行优先不采集」
  决策的硬边界，防运维误以为采集可用；
- YAML 导入路径不变（`/api/instances/import`），表结构照常按方言 TypeResolver 校验。

## 5. 执行（mask-query）

### 引擎目录

`QueryEngine` 增两值（复用批 1 的枚举结构）：

| engine id | 改写方言（dialect） | 缺省端口 | JDBC URL 形态 |
|---|---|---|---|
| `hive` | `hive` | 10000 | `jdbc:hive2://H:P/DB`（binary transport）；`sslmode=require` → 追加 `;ssl=true`（http transport 等实现细节按驱动实测补，以 golden 验收为准） |
| `sparksql` | `sparksql` | 10000 | 同左（Spark ThriftServer 同协议；`DB` 可为空，为空时 URL 为 `jdbc:hive2://H:P/`） |

- 驱动：`org.apache.hive:hive-jdbc`（3.1.3，兼容 HiveServer2 2.x-4.x 与 Spark
  ThriftServer），pom 带瘦身 exclude（hadoop/servlet/log4j 等传递依赖按
  「能连接 HS2 的最小可运行集」裁剪，实现时以实测为准）；
- 凭据：`ConnectionView.dbUser/passwordRef` 复用批 1 的 `CredentialSource` 红线；
- 流式：HS2 结果集服务端分页，`setFetchSize` 语义天然成立，`maxRows+1` 截断判定不变。

### 已知差异（写入文档，不列为缺陷）

- **语句超时**：hive-jdbc `Statement.setQueryTimeout` 行为随驱动版本（部分实现
  忽略或抛 `SQLFeatureNotSupportedException`）。护栏语义以「容器兜底超时
  （WebAsyncTask）+ 断连取消」为主路径；驱动支持时 `QUERY_TIMEOUT` 分类照常生效；
  文档注明 Hive/Spark 的时间护栏强度低于批 1 四引擎。

## 6. 测试与验收

### 单测（默认套件）

- **方言**（mask-core）：`HiveDialectProfileTest` / `SparkSqlDialectProfileTest`
  对齐既有方言测试模式——类型解析（支持清单 + 拒绝清单）、标识符折叠与反引号渲染、
  两段名解析、包装渲染 golden 串（手写一条含脱敏 + 行过滤的完整语句逐字断言）、
  CTAS 变体拒绝路径；
- **metadata**：`MetadataServiceTest` 补 normalizeDialect 接受两值；采集拒绝路径
  测试（`CollectControllerTest` 风格）；
- **mask-query**：`QueryEngineTest` URL 表（含 `sslmode=require` 分支与空 DB 形态），
  engine→dialect 映射断言。

### 人工验收（docker compose profile）

- compose profile 加 `apache/hive:4.0`（HiveServer2）与 Spark ThriftServer 镜像，
  mask-query 直连两引擎；
- `docs/query-acceptance/golden-queries.md` 扩两节：基础脱敏、行过滤叠加、LIMIT、
  **CTAS 变体拒绝**（`STORED AS` / `USING` 各一条）、两段名查询；每节三列表
  （原始 SQL → 改写后 SQL → 期望脱敏结果）；
- UDF 部署前提：Hive `CREATE [TEMPORARY] FUNCTION <name> AS '<class>' USING JAR
  '<path>'`，Spark `CREATE [OR REPLACE] FUNCTION`（JAR 或 SQL 表达式函数）；
- 两引擎协议同为 HS2——同一个 `apache/hive` 镜像已验证的查询集可复跑于 Spark
  ThriftServer（记录差异：Spark 若启动时声明 spark.catalog，catalog 名在 golden
  里不一致时按实例声明处理）。

## 7. 文档

- README：方言支持表补两行（引号/折叠/两段名/类型集）、引擎表补两行（驱动/端口）、
  「采集边界」注明 Hive/Spark 不支持 pull-metadata、UDF 部署前提表补两行、
  超时护栏差异说明；
- 本 spec 与批 1 spec、批 1 计划同目录归档。

## 8. 风险与依赖

- `SparkSqlDialect` 在所用 calcite-core 版本的存在性：若缺失则按现有
  `TrinoUnparseDialect` 模式自建轻量 UnparseDialect（实现时第一件事验证）；
- hive-jdbc 传递依赖瘦身：以「连接 127.0.0.1 的 HS2 + 执行 SELECT + 取回结果」
  的冒烟单测为准绳，防过度裁剪；
- 并行会话推进 main 的合并冲突：本批与批 1 类似从 main 分叉实施，冲突面预计
  限于 `DialectProfiles`/`DialectRegistry`/`MetadataService` 三个文件。

## 9. 批次边界（验收口径）

本批完成的标志：两方言单测全绿入默认套件；metadata 拒绝与白名单测试全绿；
compose profile 起 Hive + Spark 两引擎跑通 golden 清单（人工验收，记录输出）；
README 更新。Hive/Spark 的采集与 Kerberos/ZooKeeper 连接不在本批。