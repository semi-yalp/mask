# view 身份标记贯穿全链路（对齐 table）

日期：2026-09-17
状态：已评审（用户批准设计，spec 待确认）
路径：brainstorming → 本 spec → writing-plans

## 背景与动机

采集链路已支持 `--include-views`（视图与物化视图一起导出），三个 introspector 的
SQL 也查询了行类型（PG `relkind`、MySQL/Trino `TABLE_TYPE`），但 `assemble()` 组装
`IntrospectionResult.TableInfo` 时把该信息丢弃了。此后 YAML 生成、元数据服务存储与
管理 API 全部把视图当作无差别命名的表：管理端无法区分视图与表，view 对 table 的
「对齐」是以丢失身份为代价的。

本设计把视图身份（kind）从采集一路带到 YAML 契约、元数据库与管理 API，视图与表
真正可区分。

「基表策略穿透视图」（对基表列配置的脱敏策略经视图查询同样生效）是安全语义问题，
依赖视图定义采集与 lineage 展开，**另立项目**，不在本设计内。

## 已确认决策

| 决策点 | 结论 |
|---|---|
| 范围 | 仅身份标记贯穿全链路；策略穿透另立项目 |
| 取值 | 规范化小写枚举：`table` / `view` / `materialized_view` |
| YAML 表示 | 表条目**可选** `kind:` 属性，仅非 `table` 时输出；缺省 = `table` |
| 改写端 | 零改动：`YamlConfigLoader` 手工取键、未知键天然忽略；`TableMetadata` 不加字段 |
| 存量库迁移 | `metadata-schema.sql` 追加 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，启动幂等 |

被否决的替代方案：

- **恒输出 `kind:`**：契约更显式，但所有 golden fixture 与存量用户 YAML 逐字节全变，
  消费端被迫同步升级，收益不成比例；
- **独立 `metadata.views:` 列表**：改变 YAML 形状，「表」模型出现两种来源，而策略
  绑定仍按 `catalog.schema.table` 名字无差别匹配，没有收益。

## 目标 / 非目标

目标：

1. 三个 introspector（PG/MySQL/Trino）把行类型规范化为 kind 并随 `TableInfo` 传出；
2. `MetadataYamlGenerator` 对非 `table` 条目输出 `kind:` 行；无视图库的输出与旧版
   **逐字节一致**（既有 golden 不动）；
3. 元数据服务：importer 可选解析 + 枚举校验、collect 透传、`meta_table` 加列、
   `GET /api/instances/{name}` 的结构 JSON 自动带出 kind；
4. 向后兼容：旧 YAML（无 `kind`）、存量库（无列）均正常工作。

非目标（YAGNI，明确不做）：

- 基表策略穿透视图（lineage 展开到基表列）——另立项目；
- 采集视图 SQL 定义（`pg_get_viewdef` / `SHOW CREATE VIEW`）——那是策略穿透项目的
  前置，本设计不含；
- 改写端 `TableMetadata` / mask-lite 模型加 kind——改写行为不依赖视图身份；
- 管理前端结构化展示改造——mask-core `static/index.html` 只展示 YAML 文本与计数，
  mask-metadata 为纯 REST，kind 随 JSON 透出即可。

## 1. kind 规范化映射

新增 `io.sqlmask.introspect.TableKind` 小工具（常量字符串值 + 各引擎源值映射的归属
见下）。三个合法值：`table`、`view`、`materialized_view`。

| 引擎 | 源值 | kind |
|---|---|---|
| PG（`relkind`） | `'r'` 普通表、`'p'` 分区表 | `table` |
| PG | `'v'` 视图 | `view` |
| PG | `'m'` 物化视图 | `materialized_view` |
| MySQL（`TABLE_TYPE`） | `BASE TABLE` | `table` |
| MySQL | `VIEW`、`SYSTEM VIEW` | `view` |
| Trino（`TABLE_TYPE`） | `BASE TABLE` | `table` |
| Trino | `VIEW` | `view` |

各 introspector 的源值集合不同，映射逻辑分别放在各自 introspector 内（私有静态
方法），`TableKind` 只承载合法值常量与规范化校验，不强行统一入口。

查询 WHERE 子句已按已知源值过滤，正常不会出现未知值。防御性兜底：未知源值降级为
`table`，并向 `warnings` 追加一条 `unknown relkind 'x' for catalog.schema.name,
degraded to table` 风格的警告（不失败）。

## 2. 采集链路（mask-core）

- `IntrospectionResult.TableInfo` 增加 `String kind` 组件；保留现有四参构造器
  `TableInfo(catalog, schema, name, columns)` 委托 `kind = "table"`，既有调用点与
  测试不破。
- `PgMetadataIntrospector.assemble()` 读取已选出的 `relkind` 列；MySQL/Trino 读取
  已选出的 `TABLE_TYPE` 列；映射后传入 `TableInfo`。
- 三处 SQL 均已选出该列，无需改 SQL 文本。

## 3. YAML 契约（生成与解析）

**生成**（`MetadataYamlGenerator`）：仅当 `kind != table` 时，在该表条目的 `name:`
行之后、`columns:` 之前输出 `      kind: view`（或 `materialized_view`）。排序键与
行序不变；无视图库的输出与旧版逐字节一致。

**解析**两处：

- mask-core `YamlConfigLoader`：零改动。kind 对改写无意义，未知键被忽略。补一条
  测试锁定容忍契约（带 `kind:` 的 YAML 正常加载、行为不变）。
- mask-metadata `MetadataYamlImporter`：可选解析 `kind`；出现时必须是三个合法值
  之一，否则 `CONFIG_ERROR`，消息含合法值列表
  （`path + ".kind: unknown kind 'x' (expected table|view|materialized_view)"`）。
  与 `rowFilter` 的拒绝风格一致——不静默丢弃。

## 4. 元数据服务存储与 API（mask-metadata）

- `TableStructure` 增加 `String kind` 组件 + 四参兼容构造器（缺省 `"table"`）。
  `InstanceDetailResponse` 直接嵌 `List<TableStructure>`，kind 随 JSON 自动透出，
  DTO 无需改动。
- `CollectService.toStructures` 从 `TableInfo` 透传 kind。
- `metadata-schema.sql`：
  - `meta_table` 建表列清单加 `kind VARCHAR(32) NOT NULL DEFAULT 'table'`；
  - 文件末尾追加 `ALTER TABLE meta_table ADD COLUMN IF NOT EXISTS kind VARCHAR(32)
    NOT NULL DEFAULT 'table';`。`spring.sql.init.mode: always` 每次启动执行：新库上
    是无效果幂等，存量库补列。
- `JdbcMetaStore`：`replaceStructure` 的 INSERT 列清单加 kind；`loadStructure` 的
  SELECT 读出 kind。

## 5. 错误处理

- importer 非法 kind → `CONFIG_ERROR`，消息含合法值列表；
- introspector 未知源值 → 不失败，降级 `table` + warning；
- 其余沿用既有错误码与消息风格（YAML 路径定位）。

## 6. 测试

- kind 映射单测：PG 四个 relkind、MySQL 含 `SYSTEM VIEW`、Trino 两值、未知值降级
  + warning；
- 生成器：含视图库的输出含 `kind: view` / `kind: materialized_view` 行；无视图库的
  输出与旧版**逐字节一致**（直接用现有 golden 回归）；
- importer：缺省 kind → `table`、合法值通过、非法值 `CONFIG_ERROR`；
- store 往返：kind 写读一致；schema 幂等（先按旧结构建库插数据，再跑全量 DDL 不报
  错且补列）；
- `CollectService`：`TableInfo.kind` → `TableStructure.kind` 透传；
- `YamlConfigLoader`：带 `kind:` 的 YAML 加载成功且改写行为不变。

## 7. 实施影响面

生产代码约 11 个文件（含 1 个新文件）：

- mask-core：`IntrospectionResult`、`PgMetadataIntrospector`、
  `MysqlMetadataIntrospector`、`TrinoMetadataIntrospector`、`MetadataYamlGenerator`、
  新增 `TableKind`；
- mask-metadata：`TableStructure`、`MetadataYamlImporter`、`CollectService`、
  `JdbcMetaStore`、`metadata-schema.sql`。

测试：introspector 三套、生成器、importer、store、CollectService、
YamlConfigLoader 各若干。mask-core 的 golden 文件预期零改动。
