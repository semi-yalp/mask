# 元数据采集：从 PG 拉取库表结构生成 metadata YAML

日期：2026-09-06
状态：已评审（用户批准设计，spec 待确认）
路径：brainstorming → 本 spec → writing-plans

## 背景与动机

sql-mask 的改写输入是手工编写的 metadata YAML（表结构 + 列策略 + rowFilter）。
真实测试/落地场景中，目标库（如远程 PG 实测环境：`crm`、`tpcds` 两个库、十余张表）
的表结构已经存在，手写 YAML 既繁琐又容易与库中实际列/类型不一致。

本设计新增「连接 PG 读取元数据 → 生成本地 metadata YAML」能力：给出连接参数，
工具从 `pg_catalog` 拉取非系统 schema 下的表与列，映射为 YAML 声明所支持的类型，
确定性输出到本地文件（CLI）或返回给页面（Web），策略与 rowFilter 由人工后补。

## 已确认决策

| 决策点 | 结论 |
|---|---|
| 入口形态 | CLI 与 Web 两端同时交付，共用同一采集服务 |
| 策略生成 | 纯骨架：只生成表结构 + `policies: {}`，策略/rowFilter 人工后补 |
| 不可映射类型 | 降级 `varchar` + 逐列警告；`--strict` 时降级即错误 |
| 实现架构 | 自建 JDBC 采集模块（方案 A），新增 `org.postgresql:postgresql` 依赖 |

被否决的替代方案：Calcite `JdbcSchema`（类型映射受 Calcite 规则约束，降级警告要在
外围再包一层，得不偿失）；外部 `psql` 进程（要求装 psql，Web 后端不可用）。

## 目标 / 非目标

目标：

1. CLI：`--pull-metadata` 参数组，从 PG 拉元数据写 YAML 文件；
2. Web：`POST /api/metadata/pull` + 页面「从数据库导入」，导入结果合并进结构编辑器；
3. 类型映射规则确定、可测：精确映射优先，不可映射降级 `varchar` 并警告；
4. 输出确定性：同一库两次导出逐字节一致（可 diff、可 golden 测试）。

非目标（YAGNI，明确不做）：

- 不按列名猜测脱敏策略或 rowFilter（未来如做，另行设计）；
- 不做多库一次导出/多库合并写文件：CLI 单连接单库（catalog = 连接的库名），
  多库场景跑多次得到多个文件再人工合并；Web 端仅做「按 catalog 追加合并」；
- 不做交互式密码输入、`~/.pgpass`、SSH 隧道内建（隧道由外部 ssh -L 提供）；
- 不为未来 MySQL/Trino 预建 DialectIntrospector 抽象——现在只有
  `PgMetadataIntrospector` 具体类，出现第二个引擎时再提接口；
- 抓取不含主键/外键/索引/分区键等结构信息，只取 表→列→类型。

## 1. 总体架构与数据流

```
PG ──JDBC──▶ PgMetadataIntrospector ──▶ IntrospectionResult{tables, warnings}
             （单连接，只读查询）                │
                                    PgTypeMapper（format_type → YAML 类型 + 降级警告）
                                                 │
                          CLI ──写文件──▶ MetadataYamlGenerator（snakeyaml）
                          Web ──JSON──▶
```

新包 `io.sqlmask.introspect`，四个类：

- `ConnectionSpec`：host、port、database、user、password、sslmode、connectTimeoutSeconds、
  schemas（过滤集合，空=全部非系统 schema）、includeViews。提供 `toJdbcUrl()`；
- `PgMetadataIntrospector`：执行采集，产出 `IntrospectionResult`；
- `PgTypeMapper`：`format_type` 输出字符串 → YAML 类型字符串（+是否降级）；
- `MetadataYamlGenerator`：`IntrospectionResult` → YAML 文本（snakeyaml DumperOptions）。

`IntrospectionResult` 携带 `List<TableInfo>`（catalog/schema/name/columns）与
`List<String> warnings`（逐列降级警告）。`TableInfo.Column` 含 name、yamlType、
originalPgType、degraded 标记——警告与 Web 端展示共用该标记。

依赖：`pom.xml` 新增 `org.postgresql:postgresql`，版本交给 spring-boot-dependencies
BOM 管理；shade 插件自动打进 fat jar。

## 2. 抓取范围与 SQL

单连接执行两条只读语句：

```sql
SELECT current_database();
```

```sql
SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind,
       a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS pg_type,
       a.attnum
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND c.relkind IN ('r', 'p')            -- includeViews 时改为 ('r','p','v','m')
ORDER BY n.nspname, c.relname, a.attnum;
```

- relkind 语义：`r` 普通表、`p` 分区表（含分区父表与子表，都按普通表导出）、
  `v` 视图、`m` 物化视图；外部表（`f`）与外部表包装一律不抓；
- `attnum > 0` 排除系统列，`NOT attisdropped` 排除已删除列；
- catalog 取 `current_database()` 的返回值；
- 连接 URL 附加 `readOnly=true`（防御纵深）与 `connectTimeout`、`socketTimeout=60`；
  sslmode 透传 `ConnectionSpec.sslmode`，默认 `disable`（可传 require/prefer/verify-full）。

## 3. 类型映射（PgTypeMapper）

输入是 `format_type(atttypid, atttypmod)` 的原文（如 `character varying(50)`、
`numeric(10,2)`、`timestamp(3) with time zone`）。解析出 base 名与 typmod 后按下表
输出。目标类型集合以 `PostgresqlTypeResolver` 实际接受集合为准（已核对源码：
裸 `varchar`、无精度 `numeric`、`timestamptz` 均合法；`char` 无长度默认 1）。

| PG（format_type 原文形态） | YAML 输出 | 说明 |
|---|---|---|
| `boolean` | `boolean` | |
| `smallint` | `smallint` | |
| `integer` | `integer` | |
| `bigint` | `bigint` | |
| `real` | `real` | |
| `double precision` | `double precision` | |
| `numeric(p,s)` / `numeric` | 原样 `numeric(p,s)` / `numeric` | 无精度合法，精确映射 |
| `character(n)` | `char(n)` | |
| `character`（无 typmod） | `char(1)` | PG 语义即 char(1) |
| `character varying(n)` | `varchar(n)` | |
| `character varying`（无 typmod） | `varchar` | |
| `date` | `date` | |
| `timestamp(p) without time zone` / `timestamp without time zone` | `timestamp(p)` / `timestamp` | |
| `timestamp(p) with time zone` / `timestamp with time zone` | `timestamptz(p)` / `timestamptz` | |
| `time(p) without time zone` / `time without time zone` | `time(p)` / `time` | |
| `time(p) with time zone` / `time with time zone` | `timetz(p)` / `timetz` | |

降级规则：上表之外的一切类型（`jsonb`、`json`、`uuid`、`bytea`、数组 `xxx[]`、
enum、range、`money`、`interval`、`inet`、`xml`、`tsvector`、几何类型等）输出
`varchar`，标记 `degraded=true`，并生成警告：

```
column crm.public.customer.tags: PG type jsonb is not representable, degraded to varchar
```

`--strict` 模式下任何 degraded 列使导出整体失败。解析 `format_type` 之外无法
识别的形态同样按降级处理并警告（绝不静默）。

## 4. YAML 生成（MetadataYamlGenerator）

纯骨架形态（策略、绑定、rowFilter 一概不生成）：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - name: id
          type: bigint
        - name: phone
          type: varchar(20)
        - name: created_at
          type: timestamp
policies: {}
```

确定性规则：

- 表排序：`catalog` → `schema` → `name` 字典序；列保持 `attnum` 原序；
- 只配置了行过滤、无策略时顶层为 `policies: {}`（YAML 必填键，恒输出）；
- 顶层 `columns:`（策略绑定段）不输出；
- `rowFilter` 不输出；
- snakeyaml DumperOptions：block 风格、缩进 2、宽 100、UTF-8；
- 0 张表不算错误：输出 `metadata: {tables: []} + policies: {}`，并发出警告
  「未找到任何表，请检查 schema 过滤条件」——CLI 写 stderr，Web 追加进 warnings
  数组（该骨架导入改写器时会被校验器拒绝，属预期行为——文件用于人工修正）。

## 5. CLI

沿用现有单命令形态（`SqlMaskApplication`/picocli），新增与改写参数互斥的导出参数组：

```bash
java -jar sql-mask.jar --pull-metadata \
  --host 127.0.0.1 --port 5432 --database crm \
  --user postgres --password '...' \
  [--schema public ...] [--include-views] [--strict] \
  [--sslmode disable] [--connect-timeout 10] \
  --output crm.yaml
```

- 互斥：`--pull-metadata` 与 `--sql`/`--input` 同时出现 → 用法错误，退出码 2；
- 必填：`--database`、`--user`、`--output`；`--host` 默认 `127.0.0.1`，`--port` 默认 `5432`；
- 密码：`--password` 或环境变量 `PGPASSWORD`，同时存在时 `--password` 优先；
  两者都没有 → 用法错误退出码 2（不做交互式输入）；
- stdout/stderr：摘要 `introspected 9 tables / 87 columns / 3 warnings` 到 stdout，
  逐条降级警告到 stderr；
- `--output` 文件已存在则直接覆盖（与改写模式「失败不触碰输出文件」的语义区分开：
  导出失败时同样不创建/不覆盖输出文件）；
- 退出码：0 成功（含带警告成功）、1 失败（连接/认证/strict 降级/写文件）、2 用法错误。

## 6. Web API 与页面

### POST /api/metadata/pull

请求：

```json
{
  "host": "127.0.0.1", "port": 5432, "database": "crm",
  "user": "postgres", "password": "...",
  "schemas": [], "includeViews": false
}
```

成功 200：

```json
{
  "yaml": "metadata:\n  tables:\n  ...",
  "tableCount": 9, "columnCount": 87, "warnings": ["column ... degraded to varchar"],
  "catalog": "crm"
}
```

失败 400：`{"code": "INTROSPECT_ERROR", "message": "..."}`（连接失败/认证失败/查询失败）；
strict 语义不适用于 Web（页面不提供 strict 开关，警告全部透传展示）。

安全约束：密码仅存在于当次请求处理过程，不写日志（复用 `logback` 现状，新增代码
不得打印 ConnectionSpec）；响应不回传密码；无会话状态。

### 页面（src/main/resources/static/index.html）

「表结构」页签新增「从数据库导入」按钮 → 弹出连接表单（host 默认 127.0.0.1、
port 默认 5432，密码为 password 输入框）→ 成功后：

1. 返回的表按 catalog **追加合并**进结构列表：同 `catalog.schema.name` 的表整体覆盖，
   其它 catalog 的表保留（多库场景：改 database 再导一次即可共存于同一 YAML）；
2. warnings 以可关闭列表展示（含降级列明细）；
3. YAML 源码视图与列策略下拉同步刷新（复用现有「校验并应用」链路）。

导入失败在表单内展示错误码与原因，不改动现有编辑器状态。

## 7. 错误处理

| 场景 | CLI | Web |
|---|---|---|
| 连接失败/超时/认证失败 | stderr `INTROSPECT_ERROR: <原因>`，退出 1 | 400 `INTROSPECT_ERROR` |
| strict 命中降级 | stderr 列出全部降级列 + `STRICT_DEGRADED`，退出 1 | 不适用 |
| 0 张表 | 输出空骨架 + stderr 警告，退出 0 | 200 + warnings 提示 |
| 输出文件不可写 | stderr `IO_ERROR`，退出 1，不创建/不覆盖 | 不适用 |

认证失败消息透传 PG 的原始错误文本（PG 报文本身不含密码，安全）；JDBC 异常链中
的 URL（含参数）在打印前剥除。

## 8. 测试策略

- **PgTypeMapper 全矩阵单测**：`@ParameterizedTest` 覆盖第 3 节映射表每一行 +
  降级清单代表类型 + 无法识别形态；断言输出类型与 degraded 标记；
- **MetadataYamlGenerator golden**：固定 `IntrospectionResult` → 生成文本与
  golden 文件逐字节比对（与现有 `GoldenOutputTest` 同风格）；覆盖多表排序、
  降级列、0 表空骨架；
- **CLI 测试**：互斥报错（退出 2）、缺密码（退出 2）、strict 降级（退出 1）；
  网络相关用 mock `PgMetadataIntrospector` 注入；
- **Web 测试**：`/api/metadata/pull` 成功/连接失败/参数校验，MockMvc + mock 服务；
- **端到端验收（手动）**：对远程实测 PG 依次拉 `crm`、`tpcds`，与已知建表脚本
  （`/root/pg-init/02_crm.sql`、`03_tpcds.sql`）逐表逐列比对类型与列序；
  生成的 YAML 用作 `--metadata` 跑一条已知改写用例。

不引入 Testcontainers（本机无 docker，且 pg_catalog 行为已由手动验收覆盖）。

## 验收标准

1. `java -jar sql-mask.jar --pull-metadata --host 127.0.0.1 --database crm
   --user postgres --password ... --output crm.yaml` 在隧道环境下成功产出
   `crm.yaml`，内容与 crm 库实际表结构一致（表名/列名/列序/类型）；
2. 同一命令重跑两次，文件逐字节相同；
3. 给 crm 库任意表加一列 `extra jsonb` 后重跑：该列出现在 YAML 且类型为
   `varchar`，stderr 出现对应降级警告；`--strict` 时退出码 1；
4. Web 页面从数据库导入 crm 后，编辑器出现 4 张表且可立即执行改写；
   连接错误显示在表单内；
5. `mvn test` 全绿。
