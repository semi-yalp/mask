# 元数据采集多引擎扩展：MySQL 与 Trino

日期：2026-09-06
状态：设计定稿（用户授权流程后推进；上承 2026-09-06-metadata-introspection-design.md）
路径：brainstorming → 本 spec → writing-plans

## 背景与动机

上一版交付了 PostgreSQL 的元数据采集（`io.sqlmask.introspect`：`--pull-metadata` CLI +
`POST /api/metadata/pull` + 页面导入）。同时仓库已落地 MySQL/Trino 改写方言
（`DialectRegistry`、`MysqlTypeResolver`、`TrinoTypeResolver`，YAML 类型校验已按
dialect 分发）。本设计把采集扩展到 **MySQL 与 Trino**：拉元数据 → 生成按方言类型的
YAML 骨架 → 用对应方言改写 → 打到对应引擎实测，形成完整闭环。

## 已确认决策与代定决策

| 决策点 | 结论 | 来源 |
|---|---|---|
| 引擎范围 | MySQL + Trino 都做，提取引擎抽象 | 用户问句为复数；分支两方言已落地 |
| 抽象形态 | 独立 `MetadataIntrospector` 接口 + 注册表，**不**挂到 DialectAdapter | DialectAdapter 是 Calcite 改写边界 |
| Trino 真实环境验收 | 远程主机 1.6G 内存跑不齐三容器：**分时起停**（MySQL 验完停掉再起 Trino） | 内存硬约束 |

## 目标 / 非目标

目标：

1. `--pull-metadata --engine mysql|trino` 与 Web 请求体 `engine` 字段；
2. `MetadataIntrospector` 接口 + 按引擎注册表（模式对齐 `DialectRegistry`）；
3. MySQL/Trino 类型文本 → YAML 声明映射，**硬保证：每个生成的 type 都能通过
   `DialectProfiles.byName(engine).typeResolver().parseColumn()`**（round-trip 防线）；
4. golden 字节锁定与确定性输出机制全部复用。

非目标（延续上一版）：

- 不做 ClickHouse/StarRocks 等其他引擎；
- 不做跨库/跨 catalog 一次采集（MySQL 只采连接的默认库；Trino 只采 URL 指定的
  catalog；多库多 catalog 跑多次）；
- 不改 `DialectAdapter` 及改写管线；
- 策略/rowFilter 仍纯骨架人工后补。

## 1. 抽象与注册

```java
package io.sqlmask.introspect;

public interface MetadataIntrospector {
  IntrospectionResult introspect(ConnectionSpec spec);
}
```

- `MetadataIntrospectors`（静态注册表，模式对齐 `DialectProfiles`）：
  `byEngine(String engine)` → `MetadataIntrospector`；合法值 `postgresql`/`mysql`/`trino`
  （大小写不敏感），未知值抛 `CONFIG_ERROR`（消息列支持列表）。
- 现有 `PgMetadataIntrospector` 实现该接口（类签名加 implements，行为不变）。
- 三个实现共享的结构约定保持：`protected Connection open(ConnectionSpec)` 测试覆盖点、
  采集失败 → `SqlMaskException(INTROSPECT_ERROR)`、`sanitize()` 剥 URL、
  0 表 → 「未找到任何表，请检查 schema 过滤条件」警告、降级警告文案
  `column <catalog>.<schema>.<table>.<column>: PG type <original> is not representable, degraded to varchar`
  ——原文里的 "PG type" 字样改为 `<engine> type`（MySQL 输出 "mysql type"、Trino 输出
  "trino type"；PG 保持 "PG type"）。

## 2. ConnectionSpec 泛化

record 增加首位字段 `engine`（`postgresql`/`mysql`/`trino`，构造时小写规范化；
未知值 `IllegalArgumentException`）：

```
ConnectionSpec(engine, host, port, database, user, password, schemas,
               includeViews, strict, sslmode, connectTimeoutSeconds)
```

`toJdbcUrl()` 按 engine 分支（URL 拼装仍是"连接参数"职责，留在本类）：

| engine | URL 形态 | 要点 |
|---|---|---|
| postgresql | 现状不变（`?sslmode=&connectTimeout=&socketTimeout=60&readOnly=true`） | — |
| mysql | `jdbc:mysql://host:port/database?connectTimeout=N&socketTimeout=60` | sslmode disable → `&sslMode=DISABLED`，require → `&sslMode=REQUIRED&verifyServerCertificate=false`（内网近似；verify-full 明确不支持，传了报用法错误）；MySQL 驱动无 readOnly URL 参数，靠「采集 SQL 只 SELECT information_schema」保证只读 |
| trino | `jdbc:trino://host:port/database` | `--database` 语义=catalog 名；密码走 `Properties`（`user`/`password`），**不进 URL**；sslmode disable → `?SSL=false`，require → 默认（trino-jdbc 默认 TLS）；connectTimeout 以秒数转时长写法拼入 `connectTimeout=Ns`（trino-jdbc 接受 `10s` 形态） |

实现时以驱动文档/实证为准修 URL 细节（执行任务含 `mvn dependency:tree` 与一次真实
连通性冒烟），spec 钉的是「密码不进 URL、密码不进日志」两条硬线。

`database` 字段语义：PG/MySQL=库名，Trino=catalog 名（CLI/Web 帮助文本写明）。

## 3. 三段名落位（YAML 生成）

- **PG**：现状（catalog=连接库名，schema=pg_namespace 原文）。
- **MySQL**：`catalog = 连接库名`，`schema = information_schema.TABLE_SCHEMA 原文`
  （即库名本身——连接默认库时 catalog 与 schema 同值，与现有
  `mysql-integration.yaml` 的 `crm/public` 惯例不同，但这是物理结构的忠实投影；
  改写侧两段名 `db.table` 的搜索路径解析已由 `MysqlSchemaPathPinningTest` 钉死，
  catalog=schema=库名 时 `db.table` 与三段名 `db.db.table` 均可解析）。
- **Trino**：`catalog = URL 指定的 catalog`，`schema = information_schema.TABLE_SCHEMA
  原文`，与引擎三段名完全对齐。

## 4. 引擎类型文本映射（MysqlTypeMapper / TrinoTypeMapper）

接口对齐 `PgTypeMapper`：`Mapped map(String engineTypeText)` → `(yamlType, degraded)`，
never-throw，不识别形态降级 `varchar`。**降级警告文案统一 "degraded to varchar"。**

### MySQL（输入 `information_schema.COLUMNS.COLUMN_TYPE` 原文）

- 照抄：`tinyint[(n)]`、`smallint[(n)]`、`mediumint`、`int`/`integer`、`bigint`、
  `decimal`/`dec`/`numeric[(p[,s])]`、`float`、`double`、`char[(n)]`（无 n 补
  `char(1)`）、`varchar[(n)]`、`tinytext`/`text`/`mediumtext`/`longtext`、
  `binary[(n)]`、`varbinary[(n)]`、`date`、`datetime[(p)]`、`time[(p)]`、
  `timestamp[(p)]`；
- **`unsigned`/`signed` 后缀剥离**：`int unsigned` → `int` + `degraded=true`
  （警告注明 unsigned 范围语义未保留）；
- 降级 varchar：`json`、`enum(...)`、`set(...)`、`bit[(n)]`、`year[(n)]`、`geometry`
  及一切不识别形态（含 `blob` 族、`point` 等）——resolver 明确不支持，见
  MysqlTypeResolver 错误消息清单。

### Trino（输入 `information_schema.COLUMNS.data_type` 原文）

- 照抄：`boolean`、`tinyint`、`smallint`、`integer`/`int`、`bigint`、
  `real`、`double`、`decimal[(p[,s])]`、`char`/`character[(n)]`（无 n 补 1）、
  `varchar`/`character varying[(n)]`、`varbinary`、`date`、`time[(p)]`、
  `time(p) with time zone`、`timestamp[(p)]`、`timestamp(p) with time zone`；
- 降级 varchar：`json`、`ipaddress`、`hyperloglog`、`P4HyperLogLog`、`qdigest(...)`、
  `array(...)`、`map(...)`、`row(...)` 及一切不识别形态。

### round-trip 防线（硬验收）

每个 mapper 的测试末尾统一断言：对全部精确映射用例，
`DialectProfiles.byName(engine).typeResolver().parseColumn("t", yamlType)` 不抛异常；
对全部降级用例，断言 `yamlType.equals("varchar")`。

## 5. 采集查询

| engine | 库名/标题查询 | 表/列查询 | 表类型过滤 |
|---|---|---|---|
| postgresql | 现状不变 | 现状不变 | relkind（r,p[/v,m]） |
| mysql | `SELECT DATABASE()` | `information_schema.columns`：`TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, COLUMN_NAME, COLUMN_TYPE, ORDINAL_POSITION`，`WHERE TABLE_SCHEMA = DATABASE()`（--schema 多值时 `TABLE_SCHEMA IN (...)`），`ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION` | `TABLE_TYPE = 'BASE TABLE'`；includeViews 加 `'VIEW'` |
| trino | 无（catalog 取 URL 指定值回显） | `information_schema.columns`：`TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, data_type, ordinal_position`（当前 catalog 上下文），WHERE/ORDER 同构 | 连 `information_schema.tables` 取 `table_type = 'BASE TABLE'`/`'VIEW'` |

includeViews/`--schema`/排序/列序语义与 PG 版完全同构。

## 6. CLI 与 Web

- CLI：`SqlMaskApplication` 新增 `--engine`（默认 `postgresql`，与 `--pull-metadata`
  配套；与改写模式的 `--dialect` 无关互不影响）；未知 engine → 用法错误退出 2。
  `executePullMetadata` 经 `MetadataIntrospectors.byEngine(engine)` 取实现。
- Web：`MetadataPullRequest` 增加可选 `engine` 字段（缺省 postgresql；未知值 400
  CONFIG_ERROR）；`MetadataController` 按请求 engine 取实现。
- 页面导入弹窗加「引擎」下拉（postgresql/mysql/trino，默认 postgresql），host/port
  placeholder 文案不变（MySQL 3306/Trino 8080 由用户自填）。

## 7. 依赖

- `com.mysql:mysql-connector-j`——spring-boot-dependencies BOM 管理，不写版本；
- `io.trino:trino-jdbc:446`——BOM 不管，**显式版本必须与既有 test-scope 的
  `io.trino:trino-parser:446` 对齐**；执行时 `mvn dependency:tree` 核对 guava 等
  传递依赖与 Calcite 的冲突（多方言设计 §10.2 已提醒）；shade 无 minimizeJar，
  驱动自动进 fat jar（JDBC service 文件由既有 ServicesResourceTransformer 处理）。

## 8. 错误处理

延续 PG 版矩阵：采集失败 `INTROSPECT_ERROR`（CLI 退出 1 / Web 400）；strict 命中
降级 `STRICT_DEGRADED`（仅 CLI，退出 1）；0 表空骨架 + 警告退出 0；未知 engine
（CLI 退出 2 / Web 400 CONFIG_ERROR）；失败不创建/覆盖输出文件。

## 9. 测试策略

- `MysqlTypeMapperTest` / `TrinoTypeMapperTest`：全矩阵参数化 + round-trip 防线 +
  降级代表 + null/空串 + never-throw（对齐 PgTypeMapperTest 现状，含溢出保护）；
- `MysqlMetadataIntrospectorTest` / `TrinoMetadataIntrospectorTest`：Mockito mock
  JDBC 三件套（复用 `PgMetadataIntrospectorTest` 模式：匿名子类覆写 `open()`，
  `prepareStatement(anyString())` 依序返回 mock），覆盖：正常采集+映射、
  schema 过滤、includeViews、空库警告、连接失败→INTROSPECT_ERROR 且不泄 URL；
- `ConnectionSpecTest` 扩展：engine 字段规范化、未知值拒绝、三种 URL 形态；
- `MetadataIntrospectorsTest`：注册表 byEngine 大小写不敏感、未知值 CONFIG_ERROR；
- golden：`introspect-mysql.yaml`、`introspect-trino.yaml`（确定性 + eol=lf 已有属性锁）；
- CLI/Web：`--engine` 未知值退出 2；请求体 engine 未知值 400；
- 手动端到端验收：远程主机 47.100.166.158 **分时**起容器（1.6G 内存约束）——
  ① MySQL 8 容器（mysql:8.0，~500M）：建 shop 库小表（含 `datetime(3)`、
  `decimal(10,2)`、`varchar`、`enum` 降级列），`--engine mysql` 导出、逐字节重跑、
  round-trip 实证、降级警告、strict；验完 `docker stop`；② Trino 单机容器
  （trinodb/trino，JVM Xmx1G）：用 memory catalog 建表，同套验收。若内存导致
  Trino 起不来：如实记录、验收延后（不阻塞合并，代码层 mock 测试已覆盖）。

## 验收标准

1. `--engine mysql` 对真实 MySQL 导出的 YAML 每个类型都通过
   `MysqlTypeResolver.parseColumn`（round-trip 实证），重跑逐字节一致；
2. `--engine trino` 同上（环境可用时）；
3. `enum`/`array` 等不支持类型降级 varchar + 警告，`--strict` 退出 1；
4. Web 请求体 `engine: "mysql"` 走 MySQL 采集，未知 engine 400；
5. `mvn test` 全绿（相对 baseline 零新增失败）。
