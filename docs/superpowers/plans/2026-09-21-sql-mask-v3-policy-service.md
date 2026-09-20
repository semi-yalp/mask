# sql-mask v3 策略微服务 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **执行方式（用户已指示）**：本计划在 2026-09-21 06:17 自动开工；开工先过 **Phase 0 v2 完成度闸门**（见下），v2 未完则先收口 v2 再执行本计划；全程不问用户；全部完成并回归后 `git push -u origin new-main`。

**Goal:** 在现有仓库上按 v3 目标设计重建 `mask-policy` / `mask-policy-server`，交付独立策略微服务：连接管理（三方言直连 + 连接状态）、实例/策略 CRUD（`accessType=SELECT`）、每策略多版本与回退、表/列/UDF 联想、通配符资源（glob 含 `?`）、按主体编译生效配置供改写服务消费；**本期不做认证**；`policies.yaml` 本地路径保留。

**Architecture:** 「另起新模块重写」落地为在原模块路径 `mask-policy` / `mask-policy-server` 上按 v3 规格**整包重建**（旧代码留在 git 历史作参考），但**保持 mask-core 依赖的公共契约逐字节稳定**（`Subject`/`SubjectSelector`/`PolicyType`/`GlobMatcher.matches`/`PolicyEngine`/`PolicyIndex.of`/`PolicyYamlLoader.parse`/`PolicyException`/`server.PolicyController`），使改写引擎在重写全程可编译不回归。策略服务直接消费 `mask-core` 的 `ConnectionSpec`/`MetadataIntrospectors`（pg/mysql/trino）做连接测试与 live 联想，产出的 `EffectiveConfigResponse`（mask-core 契约 record）供给已在 `mask-core` 就绪的 `PolicyServiceConfigSource` 消费者。存储沿用 PostgreSQL + `spring.sql.init`；密码沿用 `passwordRef`（环境变量名）约定，不引入加密子系统。

**Tech Stack:** Java 17, Spring Boot 3.3.5, PostgreSQL + `spring.sql.init`, `spring-boot-starter-jdbc`(JdbcTemplate), Jackson, JUnit 5, `io.zonky.test:embedded-postgres:2.0.7`（DB 集成测试）, 依赖 `mask-core`（error / Subject / `io.sqlmask.introspect` / config.source / DialectProfiles）。

**Spec:** `docs/superpowers/specs/2026-09-21-sql-mask-v3-goal-design.md`（计划据此论证；执行者需同时读 spec 与本计划）

## Global Constraints

（数值与措辞从 spec 抄录；每个任务隐含包含本节全部约束）

- **方言范围**：仅 `postgresql` / `mysql` / `trino`（仓库现有解析器 + introspector + 驱动）。Hive/SparkSQL 显式延后，直连/联想/`ConnectionSpec` 均不注册。
- **密码**：沿用 **passwordRef** 约定——实例只存环境变量名，用时 `System.getenv(passwordRef)` 解析；不引入 AES-GCM / 主密钥 / 加密子系统；错误消息与日志不含密码明文。
- **存储**：PostgreSQL + `spring.sql.init.mode=always` + `schema.sql`（`CREATE TABLE IF NOT EXISTS`），**不用** H2 与 Flyway（spec 的 H2/Flyway 是未实现的 v2 构想，按现实收敛）。
- **REST 前缀**：沿用**无版本前缀**契约（`/api/instances`、`/api/effective/{instance}`、`/api/instances/{name}/policies...`），**不引入** `/api/v1`；`mask-core` 消费者 `PolicyServiceConfigSource` 的调用 URL 保持不变。
- **认证**：本期**不考虑认证**；`PolicyApiKeyFilter` 保留但仅当 env key 配置后才拦截（现状默认开放），不新增过滤/权限/租户。
- **保持 mask-core 依赖契约稳定**：重写 `mask-policy` 时下列签名/字段**逐字节不变**：`Subject`、`SubjectSelector`、`PolicyType`、`Policy`、`PolicyResource`、`DataMaskItem`、`RowFilterItem`、`MaskInstruction`、`RowFilterHit`、`PolicyNames`、`PolicyException`、`GlobMatcher.matches(String,String)`、`PolicyEngine(PolicyIndex)`及 `maskFor`/`rowFiltersFor`、`PolicyIndex.of`、`PolicyYamlLoader.parse(String,String)`、`server.PolicyController`。仅 `GlobMatcher` 内部新增 `?` 语义。
- **`accessType`**：只存在于策略服务端模型 `PolicyEntity`，取 `SELECT` 单值枚举；不进入 `mask-policy` 库 `Policy`（避免破坏 YAML 本地路径）；数据面编译按既有 `policyType`（DATA_MASK/ROW_FILTER）工作。
- **软校验**：资源存在性不做硬校验（能取到快照/live 就提示告警，取不到/手填不拒）；UDF 列类型矩阵仅在列类型已知时执行；`requireGlobFree` 移除。
- **版本语义**：版本**只增、历史不可变**；每次 CREATE/UPDATE/ROLLBACK 落一条 `policy_version` 行并推进 `PolicyEntity.current_version` 与实例 `config_version`；回退=内容取自目标历史版本的新版本（`change_type=ROLLBACK`、`source_version=目标版本`）。
- **错误码**：`SqlMaskException.Code` 新增 `CONNECTION_FAILED`、`VERSION_NOT_FOUND`、`CONCURRENT_MODIFICATION`；统一错误体 `{code, message}`（沿用 `PolicyApiExceptionHandler` 形状），业务错误 400、`POLICY_INSTANCE_NOT_FOUND` 404。
- **通配符**：资源字段（catalog/schema/table/columns）支持 `*`（多字符，同层）与 `?`（单字符）；数据面编译把通配资源**展开为明确表/列集**进生效配置，不把 glob 抛给引擎。
- **删除守卫**：存在策略时 `deleteInstance` 拒绝（`CONFIG_ERROR`）；删除策略保留版本历史。
- **提交纪律**：每个 Step 用 Conventional Commits；任务完成即 commit；全部任务绿后整体 `git push -u origin new-main`。

## Review Focus

（spec 未逐条讲、任何人使用都可能踩的失败面；每条已钉进归属任务的测试）

1. **版本并发写**：两客户端同时 `PUT` 同一策略，后写者必须 `CONCURRENT_MODIFICATION`（400），不得静默覆盖——按 `PolicyEntity.currentVersion()` 乐观比对。→ C4/D1。
2. **回退到不存在版本**：`rollback` 拿不存在的 `version` → `VERSION_NOT_FOUND`（400），不产生脏历史。→ D2。
3. **连接失败不留脏数据**：建实例连接测试失败 → 400 `CONNECTION_FAILED`，实例**不落库**、`config_version` 不动、诊断不含密码。→ E3。
4. **无元数据时的 glob 与联想**：实例无快照且 live 不可达时 `suggest` 不 500（返回可读失败）；glob 无展开目标不静默吞，编译产出警告。→ F1/D2。
5. **生效配置恒为具体表/列**：`GET /api/effective/{instance}` 的 `ColumnBinding`/`TablePayload` **绝不出现 `*`/`?`**（展开失败即丢弃+警告）。→ D2/H2。
6. **SELECT-only 强制**：任何非 `SELECT` 的 `accessType` 提交 → 400 `CONFIG_ERROR`；缺省默认为 `SELECT`。→ C2/D1。

---

## Phase 0：v2 完成度闸门（开工第一步，非 v3 构建任务）

**执行者开工时先做这一步，再进入 Task A1。**

**Files（只读）:**
- `docs/superpowers/specs/2026-09-21-sql-mask-v2-goal-design.md`（§10 验收、§11 里程碑）
- 仓库各模块（mask-core/mask-metadata/mask-query/mask-policy-server/docker）

**判定依据（对照 v2 §10 的八条验收，逐条在仓库里核对）：**

- 服务契约（rewrite / 实例 / 导入 / 错误码矩阵）——现状：mask-core 8080 `/api/rewrite` + 实例模式存在；**基本完成**，仅核对不回归。
- 元数据导入五方言 golden——现状：`mask-introspect`（在 mask-core `io.sqlmask.introspect`）支持 pg/mysql/trino；hive/spark **未实现**。→ 判定 v2 该项未完成。
- 密码 AES-GCM 加密落库——现状：**无加密子系统**，全系统用 passwordRef。→ v2 该项未完成。
- 工程化门（checkstyle/spotless/spotbugs/rat/jacoco/enforcer/CI）——现状：`mask-build-tools` 有部分 shade transformer；CI 是否存在于 `.github/` 需核对。→ 若 `.github/workflows` 缺则 v2 该项未完成。
- Docker 交付——现状：`docker/*.Dockerfile` + `docker-compose.*.yml` 存在（metadata/policy/query/metrics）。→ 基本完成。

**闸门结论（执行者据此决策，不问用户）：**

- **v3 依赖的 v2 基线**（改写服务 instance-mode 消费者 `PolicyServiceConfigSource`/`InstanceConfigSources`/`RewriteController.instance`、三方言 `ConnectionSpec`/`MetadataIntrospectors`、PG + `spring.sql.init` 约定、Docker 服务编排）**已在仓库成立** → 若核对确认这些成立，**即可进入 v3 执行**。
- **v2 未完成但属于 v3 明确非目标**（hive/spark 方言、AES-GCM 加密、Flyway/H2、`.github` CI、TPC 99+22 golden、v1/v2 解析缺陷修复清单）→ **记录为延后项**（写进最终总结：`Deferred (v2 非 v3 依赖项)`），**不顺手实现**。
- 若核对发现 v3 依赖的 v2 基线有缺口（例如 instance 模式消费者坏、`ConnectionSpec` 缺、schema 起不来）→ **先修基线缺口**（按 v2 §11 M1/M2/M4 的最小口径），再进 v3。
- 上述结论必须落成一段「v2 完成度核对表」（逐条：验收项 → 仓库现状 → 判定），作为第一个 commit。

---

## 阶段 A：mask-core 少量改动（2 个任务）

## Task A1: mask-core 错误码新增

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/error/SqlMaskException.java`（`enum Code`）
- Test: `mask-core/src/test/java/io/sqlmask/error/SqlMaskExceptionTest.java`

**Interfaces:**
- Produces: `SqlMaskException.Code.CONNECTION_FAILED`、`Code.VERSION_NOT_FOUND`、`Code.CONCURRENT_MODIFICATION`（后续全部任务使用）

- [ ] **Step 1: 写失败测试**（若测试文件不存在则新建）

```java
class SqlMaskExceptionTest {
  @Test void v3CodesExist() {
    assertThat(SqlMaskException.Code.valueOf("CONNECTION_FAILED")).isNotNull();
    assertThat(SqlMaskException.Code.valueOf("VERSION_NOT_FOUND")).isNotNull();
    assertThat(SqlMaskException.Code.valueOf("CONCURRENT_MODIFICATION")).isNotNull();
  }
}
```

- [ ] **Step 2: 运行确认失败** — Run: `mvn -q -pl mask-core test -Dtest=SqlMaskExceptionTest`。Expected: FAIL（`valueOf` 抛 `IllegalArgumentException`）。
- [ ] **Step 3: 实现** — 在 `enum Code` 末尾追加三个常量（不改变既有顺序）。
- [ ] **Step 4: 运行确认通过** — Run: `mvn -q -pl mask-core test`。Expected: PASS 且既有测试不回归。
- [ ] **Step 5: 提交** — `git add mask-core/.../SqlMaskException.java mask-core/.../SqlMaskExceptionTest.java && git commit -m "feat(core): SqlMaskException 新增 CONNECTION_FAILED/VERSION_NOT_FOUND/CONCURRENT_MODIFICATION"`

---

## Task A2: GlobMatcher 支持 `?`（单字符）

**Files:**
- Modify: `mask-policy/src/main/java/io/sqlmask/policy/match/GlobMatcher.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/match/GlobMatcherTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `GlobMatcher.matches(String pattern, String value)`，语义升级为：`*` 匹配同层任意字符序列（可空），`?` 匹配恰好一个字符；其余字符字面匹配；大小写沿用现状（调用方负责 normalize）。

- [ ] **Step 1: 写失败测试**（在 `GlobMatcherTest` 追加）

```java
@Test void questionMarkMatchesExactlyOneChar() {
  assertThat(GlobMatcher.matches("customer_?", "customer_1")).isTrue();
  assertThat(GlobMatcher.matches("customer_?", "customer_12")).isFalse();
  assertThat(GlobMatcher.matches("c?st*", "customer")).isTrue();
  assertThat(GlobMatcher.matches("c?st", "cost")).isTrue();   // ? 只占一位，跨位不吞
}
@Test void starSemanticsUnchanged() {
  assertThat(GlobMatcher.matches("order_*", "order_2026")).isTrue();
  assertThat(GlobMatcher.matches("a**b", "axb")).isTrue();
}
```

- [ ] **Step 2: 运行确认失败** — Run: `mvn -q -pl mask-policy test -Dtest=GlobMatcherTest`。Expected: FAIL（`?` 现状按字面匹配）。
- [ ] **Step 3: 实现** — 参考现有 `GlobMatcher` 内部（现状只处理 `*`），把匹配改成逐字符扫描：遇 `?` 消费恰好一个非空字符；遇 `*` 仍为「零或多字符、同层不跨分隔」的分割语义；遇到多个连续 `*` 折叠为一个。保持 `static boolean matches` 签名不变、无状态。
- [ ] **Step 4: 运行确认通过** —
  Run: `mvn -q -pl mask-policy test`。Expected: PASS（新增用例 + 既有全部 glob/policy 用例绿，`?` 升级不破坏 `*` 语义）。
- [ ] **Step 5: 提交** — `git commit -m "feat(policy): GlobMatcher 支持 ? 单字符通配（* 语义不变）"`

---

## 阶段 B：重写 mask-policy 库（契约稳定，4 个任务）

> 阶段说明：`mask-policy` 按 v3 规格整包重建，但下列**公共契约逐字节不变**（Global Constraints）；按包分组替换，每任务结束模块可编译且既有相关测试转绿。旧实现从 `git log` 读取作参考。

## Task B1: 模型层重建（model + 异常）

**Files:**
- Replace: `mask-policy/src/main/java/io/sqlmask/policy/model/Policy*.java`、`Subject*.java`、`MaskInstruction.java`、`RowFilterHit.java`、`PolicyNames.java` 与 `mask-policy/src/main/java/io/sqlmask/policy/PolicyException.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/model/PolicyModelTest.java`、`PolicyModelGuardsTest.java`

**Interfaces:** 逐字节复刻现有契约（见 Global Constraints 契约清单）：`Subject`、`SubjectSelector`（`matchLevel` 3/2/1/0、`*` 通配、非空校验）、`PolicyType { DATA_MASK, ROW_FILTER }`、`Policy`（紧凑构造：恰好一种物品；DATA_MASK 资源必须列级、`*` 允许；ROW_FILTER 必须表级）、`PolicyResource`（`table(...)/column(...)`；`PolicyNames.normalize`）、`DataMaskItem`/`RowFilterItem`（标量参数校验）、`MaskInstruction`、`RowFilterHit`、`PolicyNames`（空/空白抛含 `"\"*\" for any"` 文案的 `PolicyException`）、`PolicyException`（message + cause 两个 ctor）。

- [ ] **Step 1: 先读旧实现** — 从 `git show HEAD:mask-policy/src/main/java/io/sqlmask/policy/model/...` 与 `git show HEAD:mask-policy/.../PolicyException.java` 抓取每个类型当前字段/签名/校验，作为重建基准（不凭空写）。
- [ ] **Step 2: 删除旧模型文件，写入契约相同的新 record/class**（保留 head 注释与新类文档，注明「v3 重写，契约与旧版逐字节一致」）。对 `SubjectSelector`/`PolicyType`/`Policy`/`PolicyNames` 的构造校验逐一保留（参照旧测试）。
- [ ] **Step 3: 跑既有模型测试确认通过** — 运行 `mvn -q -pl mask-policy test -Dtest='PolicyModelTest,PolicyModelGuardsTest'`。Expected: PASS（若旧测试存在，语义一致即绿；测试文件可保留旧断言，仅当断言与新文档冲突才微调并记录理由）。
- [ ] **Step 4: 跑 mask-core 编译确认契约稳定** — Run: `mvn -q -pl mask-core -am compile -DskipTests`。Expected: BUILD SUCCESS（mask-core 依赖的契约未变）。
- [ ] **Step 5: 提交** — `git commit -m "refactor(policy): v3 重写模型层，契约与旧版逐字节一致（含 PolicyException）"`

## Task B2: match 层重建（PolicyEngine / PolicyIndex / GlobMatcher 已含 ?）

**Files:**
- Replace: `mask-policy/src/main/java/io/sqlmask/policy/match/PolicyEngine.java`、`PolicyIndex.java`（`GlobMatcher.java` 本任务顺带核对 A2 已含 `?`）
- Test: `mask-policy/src/test/java/io/sqlmask/policy/match/PolicyEngineTest.java`、`PolicyEngineMissPathTest.java`

**Interfaces:** 复刻既有：`record PolicyIndex(List<Policy> dataMasks, List<Policy> rowFilters)` + `static PolicyIndex of(List<Policy>)`（过滤 disabled、按 priority 降序稳定、按类型分组）；`final class PolicyEngine(PolicyIndex)` + `Optional<MaskInstruction> maskFor(catalog,schema,table,column,Subject)` + `List<RowFilterHit> rowFiltersFor(catalog,schema,table,Subject)`（索引序首命中、`matchLevel` 最高优先、行过滤 AND 组合）。

- [ ] **Step 1: 读旧实现**（`git show HEAD:...`）作基准。
- [ ] **Step 2: 重建两个类到与旧契约一致**（沿用决策：首策略命中即胜；多 item 取最高 matchLevel，同分取声明序；disabled 不进索引）。
- [ ] **Step 3: 跑既有 match 测试确认通过** — Run: `mvn -q -pl mask-policy test -Dtest='PolicyEngineTest,PolicyEngineMissPathTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "refactor(policy): v3 重写 match 层（PolicyEngine/PolicyIndex 契约不变）"`

## Task B3: store 层重建（PolicyYamlLoader）

**Files:**
- Replace: `mask-policy/src/main/java/io/sqlmask/policy/store/PolicyYamlLoader.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/store/PolicyYamlLoaderTest.java`、`PolicyYamlLoaderGuardsTest.java`

**Interfaces:** `final class PolicyYamlLoader { List<Policy> parse(String yaml, String sourceName) }`。文档形态与错误语义**不变**（根 `policies:`；`enabled` 默认 true、`priority` 默认 0、`resources` 非空、`column` 字符串/列表/`"*"`、`dataMaskItems`/`rowFilterItems` 二选一；错误路径前缀 `sourceName`；重复策略名与空 item 列表拒绝；`allowDuplicateKeys(false)` 安全构造器）。

- [ ] **Step 1: 读旧实现**作基准。
- [ ] **Step 2: 重建 loader**（保持 YAML 形态逐字节兼容；SnakeYAML `SafeConstructor`）。
- [ ] **Step 3: 跑既有 loader 测试确认通过** — Run: `mvn -q -pl mask-policy test -Dtest='PolicyYamlLoaderTest,PolicyYamlLoaderGuardsTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "refactor(policy): v3 重写 policy YAML loader（形态与错误语义不变）"`

## Task B4: server 层重建（PolicyController /api/policies/parse）+ 全模块回归

**Files:**
- Replace: `mask-policy/src/main/java/io/sqlmask/policy/server/PolicyController.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/server/PolicyControllerTest.java`

**Interfaces:** `@RestController @RequestMapping("/api/policies")` + `@PostMapping("/parse")` 接收 `{policyYaml}` 返回 `{policies:[{name, enabled, priority, type(data_mask|row_filter), resources[], itemCount}]}`——与旧版 DTO 形状一致。错误仍抛 `PolicyException`。

- [ ] **Step 1: 读旧实现**作基准。
- [ ] **Step 2: 重建 controller**（DTO 字段与大小写语义一致）。
- [ ] **Step 3: 跑本模块全测试 + mask-core 编译** —
  Run: `mvn -q -pl mask-policy test && mvn -q -pl mask-core -am compile -DskipTests`。Expected: 全 PASS + BUILD SUCCESS。
- [ ] **Step 4: 提交** — `git commit -m "refactor(policy): v3 重写 /api/policies/parse 端点（契约不变）"`

---

## 阶段 C：重写 mask-policy-server 数据模型与存储（4 个任务）

> 阶段说明：`mask-policy-server` 整包重建。旧实现从 git 历史作参考；本阶段交付可编译 + 存储层绿；Web 在阶段 E。

## Task C1: 服务端数据模型 + schema.sql

**Files:**
- Delete: `mask-policy-server/src/main/java/io/sqlmask/policyserver/model/*.java`（旧）
- Create（新模型，`io.sqlmask.policyserver.model`）:
  - `ConnectionConfig.java`、`ConnectionStatus.java`、`EngineInstance.java`、`AccessType.java`、`ChangeType.java`、`PolicyEntity.java`、`PolicyVersion.java`、`ResourceSelector.java`、`TableDef.java`、`ColumnDef.java`、`UdfDefinition.java`
- Replace: `mask-policy-server/src/main/resources/schema.sql`
- Test: 无（纯模型，由 C2 起覆盖）；`model` test 可补 `PolicyEntityTest` 重构（校验默认值）。

**Interfaces（精确签名，后续任务必用）:**

```java
record ConnectionConfig(String dialect, String host, int port, String database,
    String dbUser, String passwordRef, List<String> schemas, boolean includeViews,
    String sslmode, Integer connectTimeoutSeconds)            // schemas List.copyOf
enum ConnectionStatus { CONNECTED, FAILED, UNCONNECTED }
record EngineInstance(String name, String dialect, ConnectionConfig connection,
    ConnectionStatus status, List<TableDef> tables)           // compact: tables copyOf; status null→UNCONNECTED; connection 可空
enum AccessType { SELECT }                                     // valueOf 未知 → CONFIG_ERROR（C2/D1）
enum ChangeType { CREATE, UPDATE, ROLLBACK }
record PolicyEntity(String name, AccessType accessType, PolicyType policyType,
    boolean enabled, Integer priority, ResourceSelector resource, SubjectSelector subjects,
    String udf, List<Object> arguments, String filterExpr, int currentVersion)
  // compact: accessType null→AccessType.SELECT; priority 0; arguments copyOf; subjects 空→主("*")
  // 3 个便捷 ctor：旧 8 参(priority 0)、7 参(无 subjects)、无 version(0)
record PolicyVersion(int version, ChangeType changeType, PolicyEntity content,
    Integer sourceVersion, java.time.Instant createdAt)
record ResourceSelector(String catalog, String schema, String table, List<String> columns)
  // columns copyOf；字段允许 glob（* 与 ?）——不再调用任何 requireGlobFree
record TableDef(String catalog, String schema, String name, List<ColumnDef> columns)
record ColumnDef(String name, String typeDeclaration)
record UdfDefinition(String name, List<UdfSignature> signatures)
  // nested record UdfSignature(List<String> params, String returns)；List.copyOf
```

**schema.sql（新；`CREATE TABLE IF NOT EXISTS`，无 Flyway）：**

```sql
CREATE TABLE IF NOT EXISTS policy_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
  connection JSONB,               -- ConnectionConfig 序列化
  connection_status VARCHAR(16) NOT NULL DEFAULT 'UNCONNECTED',
  config_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE IF NOT EXISTS instance_table (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  catalog VARCHAR(255) NOT NULL, schema_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL,
  position INT NOT NULL
);
CREATE TABLE IF NOT EXISTS instance_column (
  id BIGSERIAL PRIMARY KEY,
  table_id BIGINT NOT NULL REFERENCES instance_table(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL, type_declaration TEXT NOT NULL, position INT NOT NULL
);
CREATE TABLE IF NOT EXISTS policy (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  access_type VARCHAR(16) NOT NULL DEFAULT 'SELECT',
  policy_type VARCHAR(32) NOT NULL,
  is_enabled BOOLEAN NOT NULL,
  udf VARCHAR(255), arguments JSONB, filter_expr TEXT,
  resource JSONB NOT NULL, subjects JSONB, priority INT NOT NULL DEFAULT 0,
  current_version INT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(instance_id, name)
);
CREATE TABLE IF NOT EXISTS policy_version (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  policy_id BIGINT NOT NULL REFERENCES policy(id) ON DELETE CASCADE,
  version INT NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  content JSONB NOT NULL,        -- PolicyEntity 完整快照
  source_version INT,
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(policy_id, version)
);
CREATE TABLE IF NOT EXISTS instance_udf (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  param_types TEXT NOT NULL, return_type VARCHAR(255) NOT NULL, position INT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(instance_id, name, param_types)
);
```

- [ ] **Step 1: 删除旧 model 包**（`git rm` 旧 model/java + 旧 `schema.sql`；旧代码仍在历史可查）。
- [ ] **Step 2: 写入上表全部新 record/class + 新 `schema.sql`**（注释标明 v3 重写）。
- [ ] **Step 3: 写模型默认值测试** — `PolicyEntityTest`：null accessType→SELECT、null priority→0、null subjects→主`("*")`、`currentVersion` 缺省 0；`EngineInstance` status null→UNCONNECTED。Run: `mvn -q -pl mask-policy-server test -Dtest='model.PolicyEntityTest'`（若含工程编译则需先在旧包删除后 `mvn -q -pl mask-policy-server -am compile -DskipTests` 通过）。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "refactor(policy-server): v3 数据模型（连接/状态/accessType/版本）+ schema.sql 重写"`

## Task C2: PolicyStore 接口（含版本语义）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/store/PolicyStore.java`
- Test: 无独立测试（C3/C4 实现覆盖）

**Interfaces（精确签名）:**

```java
public interface PolicyStore {
  EngineInstance createInstance(EngineInstance instance);                       // 重名 → CONFIG_ERROR
  EngineInstance updateInstanceConnection(String name, ConnectionConfig cfg, ConnectionStatus status);
  EngineInstance updateInstanceTables(String name, List<TableDef> tables);      // “启用策略引用的表/列不消失”守卫在服务层，store 只管替换
  Optional<EngineInstance> findInstance(String name);
  List<EngineInstance> listInstances();
  void deleteInstance(String name);                                             // 有策略 → CONFIG_ERROR
  PolicyEntity createPolicy(String instanceName, PolicyEntity policy);          // 写历史 v1 + current_version=1
  PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy); // 乐观：policy.currentVersion()==存储值，否则 CONCURRENT_MODIFICATION；写新历史
  PolicyEntity rollbackPolicy(String instanceName, String policyName, int targetVersion);  // 目标不存在 → VERSION_NOT_FOUND
  Optional<PolicyEntity> findPolicy(String instanceName, String policyName);
  List<PolicyEntity> listPolicies(String instanceName);
  List<PolicyVersion> policyVersions(String instanceName, String policyName);   // 按 version 升序
  void deletePolicy(String instanceName, String policyName);                    // 推进 config_version；历史保留
  UdfDefinition createUdf(String instanceName, UdfDefinition udf);
  UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf);
  Optional<UdfDefinition> findUdf(String instanceName, String udfName);
  List<UdfDefinition> listUdfs(String instanceName);
  void deleteUdf(String instanceName, String udfName);
  long currentVersion(String instanceName);                                     // config_version；未知实例 → POLICY_INSTANCE_NOT_FOUND
}
```

- [ ] **Step 1: 写接口**（删除旧 Store 接口，写入上述；方法签名含新语义注释）。
- [ ] **Step 2: 编译确认** — Run: `mvn -q -pl mask-policy-server -am compile -DskipTests`。Expected: BUILD SUCCESS（接口替换可编译）。
- [ ] **Step 3: 提交** — `git commit -m "refactor(policy-server): PolicyStore 接口重构（版本/回退/连接）/accessType"`

## Task C3: InMemoryPolicyStore（版本/回退/并发）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/store/InMemoryPolicyStore.java`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java`（重写）

**Interfaces:** 实现 C2 全部方法。语义要点：
- 每个 `PolicyEntity` 落地前 `currentVersion` 由 store 授予：`createPolicy` 记历史 v1、`updatePolicy` 在 `policy.currentVersion() == 存储值` 时落 v(current+1) 并把行版本更新为 v+1（否则抛 `SqlMaskException(CONCURRENT_MODIFICATION)`）、`rollbackPolicy` 读目标版本内容落 v(current+1)（`ChangeType.ROLLBACK`、`source_version=targetVersion`；目标不存在抛 `SqlMaskException(VERSION_NOT_FOUND)`）。
- 每次变更（含 connection/tables/policy/udf/rollback）`bump` 实例 `config_version`。
- `deletePolicy` 仅删当前、历史保留；`deleteInstance` 有政策即拒绝。

- [ ] **Step 1: 写失败测试**（重写 `InMemoryPolicyStoreTest`）——覆盖：create 后历史恰一条 v1；update 无冲突→v2；update 带陈旧 version→`CONCURRENT_MODIFICATION`；rollback 到 v1 内容正确且 `source_version=1`、`change_type=ROLLBACK`；rollback 到不存在版本→`VERSION_NOT_FOUND`；`currentVersion(instance)` 每次变更推进；删除实例有策略拒绝；udf/replace/delete 与 `current_config_version` 联动。Run: `mvn -q -pl mask-policy-server test -Dtest='store.InMemoryPolicyStoreTest'`。Expected: FAIL（方法缺失/语义未实现）。
- [ ] **Step 2: 实现 InMemoryPolicyStore**（同步 `LinkedHashMap`s 或 `synchronized` 状态；`PolicyEntity` 带上授予的 `currentVersion`）。
- [ ] **Step 3: 运行确认通过** — Run: `mvn -q -pl mask-policy-server test -Dtest='store.InMemoryPolicyStoreTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): InMemoryPolicyStore 版本/回退/乐观并发实现"`

## Task C4: JdbcPolicyStore（PostgreSQL + 版本表）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java`
- Replace: `mask-policy-server/pom.xml`（按需确认 `org.postgresql` runtime + `io.zonky.test:embedded-postgres:2.0.7` test 仍在）
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreTest.java`（embedded-postgres，重写）

**Interfaces:** 实现 C2 接口。要点：
- 事务：每个变更方法 `@Transactional`；`bump` 在**同一事务**内 `UPDATE policy_instance SET config_version=config_version+1`。
- `policy_version` 写入与 `policy.current_version` 更新同事务；唯一约束 `(policy_id, version)` 兜底并发。
- `content JSONB` 用 Jackson ObjectMapper 存 `PolicyEntity` 全量快照；`resource`/`subjects`/`arguments`/`connection` JSONB 同前。
- `policyVersions` 升序返回；`rollbackPolicy` 先查目标版本（不存在 → `VERSION_NOT_FOUND`）再落新版本。
- `createInstance` 需支持 `connection JSONB` + `connection_status` 列。

- [ ] **Step 1: 写失败测试**（embedded-postgres + `schema.sql` 生命周期，镜像 C3 用例 + JSONB 往返：null `subjects` 读作主`("*")`、`access_type`/`current_version`/`connection` 往返保真、并发版本冲突）。Run: `mvn -q -pl mask-policy-server test -Dtest='store.JdbcPolicyStoreTest'`。Expected: FAIL。
- [ ] **Step 2: 实现 JdbcPolicyStore**（基于旧实现的 `getConnection`/GeneratedKeyHolder/jackson 序列化模式；加版本表与乐观比对）。
- [ ] **Step 3: 运行确认通过** — Run: `mvn -q -pl mask-policy-server test -Dtest='store.JdbcPolicyStoreTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): JdbcPolicyStore 版本/回退/乐观并发 + schema.sql 对齐"`

---

## 阶段 D：服务层（Validator / Compiler / PolicyService）

## Task D1: PolicyValidator 重写（accessType 强制 + glob 资源 + 软校验）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`
- Replace: `mask-policy-server/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`、`PolicyValidatorGuardsTest.java`

**Interfaces:** `class PolicyValidator`（参照旧实现骨架，但语义按 Global Constraints 调整）：
- `void validateInstance(EngineInstance)`：名称正则 `[A-Za-z0-9_.\-]+`；dialect ∈ `EngineDefs.names()`（=`DialectProfiles.names()`={postgresql,trino,mysql}）；连接存在时校验 `ConnectionConfig.dialect==instance.dialect`；表/列去重与类型 `TypeResolver` 解析（同旧）。
- `List<String> validatePolicy(EngineInstance, List<UdfDefinition> udfs, PolicyEntity policy, List<PolicyEntity> otherEnabledPolicies)`（返回**警告列表**，硬错误仍抛）：
  - `PolicyEntity.accessType()` 必须为 `AccessType.SELECT`（未知值在构造/valueOf 已拒，这里兜底校验）；
  - 资源非空——但**允许 glob**（`*`/`?`），不调用任何 `requireGlobFree`；
  - DATAMASK：`udf` 非空、`MaskingPolicy.validateArguments(...)`、资源列 ≥1；**列存在性软校验**——能从 store 快照找到表格/列则校验，缺失名字仅在 warnings 里提示（不再硬拒 P0）；UDF 引用解析：存在性 + 参数个数 + 标量矩阵必检；列类型矩阵仅当列类型已知时校验（未知 → warning）；
  - ROW_FILTER：`filterExpr` 非空白，经 `RowFilterRegistry.build` 对由快照表构建的 Calcite schema 校验（沿用旧 YAML 白名单）；快照缺该表 → warning 不硬拒；
  - 重叠判定：**仅两策略资源都为精确（无 glob）且同表同列**时按主体相交判定；含 glob 的资源不参与严格重叠拒绝（记 warning 提示保守）。
- 其余 `validateUdf`、`policiesFailingUdfResolution(...)` 沿用旧语义。

- [ ] **Step 1: 读旧实现**作基准。
- [ ] **Step 2: 写失败测试** —— 重写 `PolicyValidatorTest` 覆盖：`accessType=SELECT` 放行、非 SELECT（如 `AccessType` 无该值场景用反射/替代 → 直接断言构造传入 SELECT 仅一种，且 validator 对非法值抛 `CONFIG_ERROR`）；资源含 `*`/`?` 放行（旧 `requireGlobFree` 测试**删除**）；未知列/表 → 返回 warning 不抛；UDF 列类型未知 → warning、已知不匹配 → 抛；重叠判定仅作用于精确资源。Run: `mvn -q -pl mask-policy-server test -Dtest='PolicyValidatorTest'`。Expected: FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过** — Run: `mvn -q -pl mask-policy-server test -Dtest='PolicyValidatorTest,PolicyValidatorGuardsTest'`。Expected: PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(policy-server): PolicyValidator 重写（SELECT 强制/glob 资源/软校验/重叠仅精确）"`

## Task D2: EffectiveConfigCompiler（glob 展开 + 版本化）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java`(重写)

**Interfaces:**
```java
public static EffectiveConfigResponse compile(EngineInstance instance, List<PolicyEntity> policies,
    Subject subject, List<String> warnings)
```
- 过滤：enabled 且 `accessType==SELECT` 且 `SubjectSelector` 命中（`matchLevel>0`）；按 `priority DESC, name ASC`。
- **glob 展开**：对 `PolicyEntity.resource`：
  - table 精确（无 glob）→ 直接取该 table；
  - table 含 glob → 用 `GlobMatcher.matches` 对 `instance.tables` 全部匹配出表集，`*`/`?` 均可用；
  - columns 精确 → 取这些列；columns 含 glob → 对每张展开表的列名匹配；**匹配为空 → warnings 加一条**，该策略不产生绑定；
  - 对展开后的**具体**表/列产生 `ColumnBinding`/`TablePayload`——产物里**绝不出现 `*`/`?`**。
- row_filter：表级 glob → 每条匹配表各一条 `rowFilter`；同一表多条 → `"(f1) AND (f2)"`（priority 序）；具体表无 glob → 同旧。
- 输出 `EffectiveConfigResponse(instance, dialect, configVersion=0(请求方覆写), PolicySummary, ConfigPayload)`（record 契约来自 mask-core，字段形状不变）。

- [ ] **Step 1: 写失败测试**（重写 `EffectiveConfigCompilerTest`）——覆盖：glob 表展开多表、glob 列展开、glob 无匹配 → warning + 无绑定、产物无 `*`/`?` 残留断言、`accessType=SELECT` 过滤、主语过滤、优先级列归属、row-filter AND 组合、单条保真。Run: `mvn -q -pl mask-policy-server test -Dtest='compile.EffectiveConfigCompilerTest'`。Expected: FAIL。
- [ ] **Step 2: 实现**。
- [ ] **Step 3: 运行确认通过** — Run: `mvn -q -pl mask-policy-server test -Dtest='compile.EffectiveConfigCompilerTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): EffectiveConfigCompiler glob 展开（产物恒为具体表/列）+ SELECT 过滤"`

## Task D3: PolicyService（编排 + 版本推进 + effective 打点）

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyService.java`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java`(重写, Mockito)

**Interfaces:**
```java
public final class PolicyService {
  PolicyService(PolicyStore store, PolicyValidator validator)          // 基准 ctor
  EngineInstance createInstance(String name, String dialect, ConnectionConfig connection, boolean fetchMetadata, EngineAccess access);
  EngineInstance updateConnection(String name, ConnectionConfig connection, EngineAccess access);   // 重测连接
  ConnectionStatus retestConnection(String name, EngineAccess access);
  EngineInstance metadataFetch(String name, EngineAccess access);       // live 拉表 → replace 快照（幂等，config_version 推进）
  EngineInstance instance(String name); List<EngineInstance> instances(); void deleteInstance(String name);
  PolicyEntity createPolicy(String instance, PolicyEntity policy);
  PolicyEntity updatePolicy(String instance, String name, PolicyEntity policy);
  PolicyEntity rollbackPolicy(String instance, String name, int targetVersion);
  List<PolicyVersion> policyVersions(String instance, String name);
  List<PolicyEntity> policies(String instance); Optional<PolicyEntity> policy(String instance, String name);
  void deletePolicy(String instance, String name);
  // UDF CRUD 沿用旧签名 createUdf/replaceUdf/udfs/udf/deleteUdf
  EffectiveConfigResponse effective(String instance, Subject subject);  // configVersion=store.currentVersion(instance)
}
```
语义要点：createInstance 传 `ConnectionConfig` 时先 `access.test(...)`（失败 → `CONNECTION_FAILED`，**不落库**）→ `store.createInstance(instance(status=CONNECTED))`；`fetchMetadata=true` 且连接存在 → `access.fetch(...)` 填 tables；无连接 → `status=UNCONNECTED`、tables 空。`metadataFetch` 用 `access.fetch` 替换 tables + bump。`effective` 把 `compile` 警告透出（logger.info，不写响应——响应契约无 warnings 字段）。UDF 删除守卫沿用。

- [ ] **Step 1: 写失败测试**（Mockito mock store/validator/access）：连接失败不落库（verify store 未 create）；成功→CONNECTED；fetchMetadata 填表；metadataFetch 幂等 bump；create/update policy 调用 store；rollback 透传；effective 打 configVersion；UDF 守卫。Run: `mvn -q -pl mask-policy-server test -Dtest='PolicyServiceTest'`。Expected: FAIL。
- [ ] **Step 2: 实现**。
- [ ] **Step 3: 运行确认通过** — Run: `mvn -q -pl mask-policy-server test -Dtest='PolicyServiceTest'`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): PolicyService 编排（连接乘积/版本/回退/effective 打点）"`

---

## 阶段 E：连接管理（EngineAccess + 端点）

## Task E1: ConnectionConfig ↔ ConnectionSpec 适配 + 密码解析

**Files:**
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/connection/ConnectionResolver.java`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/connection/ConnectionResolverTest.java`

**Interfaces:**
```java
final class ConnectionResolver {
  static ConnectionSpec toSpec(ConnectionConfig cfg);   // 映射 mask-core ConnectionSpec(engine=cfg.dialect(), ...)
  static String resolvePassword(ConnectionConfig cfg);  // System.getenv(cfg.passwordRef())；缺失 → SqlMaskException(CONNECTION_FAILED, "passwordRef env 'X' not set")
}
```
- `toSpec`：`new ConnectionSpec(cfg.dialect(), cfg.host(), cfg.port(), cfg.database(), cfg.dbUser(), resolvePassword(cfg), cfg.schemas(), cfg.includeViews(), /*strict*/false, cfg.sslmode(), cfg.connectTimeoutSeconds())`（ConnectionSpec 字段顺序以 mask-core 现行为准，严格按 `git show HEAD:.../ConnectionSpec.java` 核对）。
- [ ] **Step 1: 写测试**（mock env）：缺 env → CONNECTION_FAILED；有 env → password 正确；toSpec 字段映射全对、engine 非法（如 hive）→ 构造即拒。Run: `mvn -q -pl mask-policy-server test -Dtest='connection.ConnectionResolverTest'`。Expected: FAIL。
- [ ] **Step 2: 实现**（读 mask-core `ConnectionSpec` 构造器核对字段序）。
- [ ] **Step 3: 运行确认通过** — Run: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): ConnectionConfig→ConnectionSpec + passwordRef 环境解析"`

## Task E2: EngineAccess（连接测试 + live 拉表）+ 预检端点

**Files:**
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/connection/EngineAccess.java`、`JdbcEngineAccess.java`、`ConnectionTestResult.java`、`ConnectionStatus.java`(已 C1)
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/connection/JdbcEngineAccessTest.java`（embedded-postgres 作 PG 真连）

**Interfaces:**
```java
record ConnectionTestResult(boolean ok, String dialect, long latencyMs, List<String> warnings) {
  static ConnectionTestResult ok(...); static ConnectionTestResult failed(...);
}
interface EngineAccess {
  ConnectionTestResult test(ConnectionConfig cfg);               // 失败 → 抛 SqlMaskException(CONNECTION_FAILED, message 含主机/库但不含密码)
  List<TableDef> fetch(ConnectionConfig cfg);                    // live 只读拉表结构 → TableDefs（用 MetadataIntrospectors.byEngine）
}
final class JdbcEngineAccess implements EngineAccess {
  // test: DriverManager.getConnection(spec.toJdbcUrl(), props) → conn.isValid(3)/或直接 close 不再查询 → latencyMs
  // fetch: MetadataIntrospectors.byEngine(cfg.dialect()).introspect(spec) → IntrospectionResult(catalog, tables, warnings) → List<TableDef>
}
```
- [ ] **Step 1: 写测试**（真实嵌入 PG：`ConnectionSpec` 指向 embedded PG；test ok 且不含密码；fetch 返回该库表/列（yamlType 映射）；merger 为 PostgreSQL 驱动）。Run: `mvn -q -pl mask-policy-server test -Dtest='connection.JdbcEngineAccessTest'`。Expected: FAIL。
- [ ] **Step 2: 实现**（连接超时并入 url `connectTimeout=`；错误映射：SQLException→CONNECTION_FAILED，包装 message 脱敏——不含 user/password）。
- [ ] **Step 3: 运行确认通过** — Run: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): EngineAccess 直连测试 + live 拉表（pg/mysql/trino）"`

## Task E3: 连接相关 REST
（放到阶段 G 与控制器一起实现；本任务先在 PolicyService 侧把 `POST /api/connections/test` 的语义钉住——见 G1 路由表。）

- [ ] **标记**：E3 为设计占位（无文件），由 G1 的 `POST /api/connections/test` 实现闭合；此处不落代码，避免重复。继续 F1。

---

## 阶段 F：联想

## Task F1: SuggestService + 端点（快照 / live 双通道）

**Files:**
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/app/SuggestService.java`
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/web/SuggestController.java`（路由在 G1 一并注册）
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/app/SuggestServiceTest.java`

**Interfaces:**
```java
record SuggestItem(String name, String schema, String table, String type) {}
record SuggestResult(String source, List<SuggestItem> items) {}    // source: "snapshot"|"live"
final class SuggestService(PolicyStore store, EngineAccess access) {
  SuggestResult suggest(String instance, String kind, String q, String schema, String table, int limit);
  // kind ∈ {table, column, udf}
}
```
语义：
- `table`：先从 `store.findInstance(instance).tables()`（快照）按 `q` 前缀/glob 匹配（`schema` 过滤）；快照表为空且实例有 connection → live `access.fetch(cfg)` 现场拉取再匹配（`source=live`）；live 也失败 → 抛 `CONNECTION_FAILED`（非 500）。
- `column`：需要 `table`（或 `schema`+`table`）上下文；快照列匹配 / live 拉表取该表列。
- `udf`：从 `store.listUdfs` 按 `q` 前缀匹配（`type` 为 signature 摘要）。
- 返回 `limit`（默认 20）上限；q 为空返回空列表（不 500）。
- **阻断失败面**：实例无快照、live 不可达 → `CONNECTION_FAILED`；实例不存在 → `POLICY_INSTANCE_NOT_FOUND`。

- [ ] **Step 1: 写测试**（用 InMemoryPolicyStore + mock EngineAccess）：快照表匹配、快照空→live 兜底、live 失败→CONNECTION_FAILED、column/udf、limit、空 q。Run: `mvn -q -pl mask-policy-server test -Dtest='app.SuggestServiceTest'`。Expected: FAIL。
- [ ] **Step 2: 实现**。
- [ ] **Step 3: 运行确认通过** — Run: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): Suggest 联想（快照优先/live 兜底/列与 UDF）"`

---

## 阶段 G：Web 层与启动（控制器 + 装配 + 集成测试）

## Task G1: 管理面/数据面控制器全量重写

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/web/PolicyAdminController.java`、`UdfController.java`、`EffectiveConfigController.java`、`SuggestController.java`(新)、`ConnectionTestController.java`(新，或并 PolicyAdminController)
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/web/PolicyApiExceptionHandler.java`、`PolicyApiKeyFilter.java`（保留默认开放）、`MetadataStructureFetcher.java`（**删除**——v3 直连替代 HTTP 导入；若 G-F 阶段有测试引用则同步删）
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyServerApplication.java`
- Replace: `mask-policy-server/src/main/resources/application.yml`

**路由表（沿用无前缀契约；请求/响应 DTO 定义在各 controller 局部 record，字段与旧 DTO 兼容并对齐新模型）：**

```
POST   /api/connections/test            body {connection: ConnectionConfig} → 200 {ok,dialect,latencyMs,warnings} | 400 CONNECTION_FAILED（不落库）
POST   /api/instances                   body {name,dialect,connection?,tables?,fetchMetadata?} → InstanceDto(status,connectionSummary,tables) | 400 CONNECTION_FAILED(连接失败不落库)
GET    /api/instances[/{name}]          → 列表/详情（含 status；密码仅 passwordRef 名，不回传明文）
PUT    /api/instances/{name}/connection body {connection} → 重测 → status
POST   /api/instances/{name}/connection/test → 200 status | 400 CONNECTION_FAILED
POST   /api/instances/{name}/metadata-fetch → {tableCount,columnCount,warnings,status}
DELETE /api/instances/{name}            有策略 → CONFIG_ERROR
GET    /api/instances/{name}/policies[/{policy}]      POST .../policies        PUT .../policies/{policy}   DELETE .../policies/{policy}
GET    /api/instances/{name}/policies/{policy}/versions    → [{version,changeType,sourceVersion,createdAt,policy}]
POST   /api/instances/{name}/policies/{policy}/rollback    body {version}      → PolicyDto（新 currentVersion）
GET    /api/instances/{name}/suggest?kind=table|column|udf&q=…&schema=…&table=…&limit=…
POST/GET/PUT/DELETE /api/instances/{name}/udfs[/{name}]    （沿用）
GET    /api/effective/{instance}?user=&groups=             （沿用；configVersion 由 store 打点）
GET    /actuator/health
```

**PolicyDto**（管理面）新增字段：`accessType`（默认 `SELECT`）、`currentVersion`（回显）；连接摘要字段（`hasConnection`、`connectionStatus`）。`import-metadata`（旧 HTTP 导入）**移除**（直连替代）。`PolicyApiKeyFilter` 保留但 env 未配置即开放（不新增鉴权）。

- [ ] **Step 1: 写 MockMvc 失败测试**（`web/*EndpointTest` 重写，InMemory store profile）——覆盖上述全部路由的正/负契约 + 无密码明文字段断言 + `CONCURRENT_MODIFICATION`/`VERSION_NOT_FOUND`/`CONNECTION_FAILED` 400 形状 + effective 主体过滤 + suggest。
- [ ] **Step 2: 实现各 controller + DTO + ExceptionHandler**（错误映射沿用：`SqlMaskException`→code、`PolicyException`→CONFIG_ERROR 400、兜底 500；`CONNECTION_FAILED` 不给明文）。
- [ ] **Step 3: 运行确认** — Run: `mvn -q -pl mask-policy-server test`。Expected: PASS。
- [ ] **Step 4: 提交** — `git commit -m "feat(policy-server): Web 层重写（连接/版本/回退/联想/effective）+ 错误映射"`

## Task G2: PolicyServerApplication + 装配

**Files:**
- Replace: `mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyServerApplication.java`
- Replace: `mask-policy-server/src/main/resources/application.yml`

**装配（对照旧实现）：** bean `PolicyValidator`、`PolicyService(store,validator)`、`EngineAccess(JdbcEngineAccess)`、`SuggestService(store,access)`、`PolicyStore(ObjectProvider<JdbcTemplate> → JdbcPolicyStore | InMemoryPolicyStore)`、`FilterRegistrationBean<PolicyApiKeyFilter>`（default open）；`spring.application.name: mask-policy`、`server.port: 8081`、datasource `POLICY_PG_*`、`spring.sql.init.mode=always`。测试 profile：排除 `DataSourceAutoConfiguration` → InMemory store。

- [ ] **Step 1: 重写 application.yml + 装配类**（按旧实现 bean 结构；不注册 ApiKey env 即开放）。
- [ ] **Step 2: 启动冒烟** — Run: `mvn -q -pl mask-policy-server spring-boot:run`（或 `-DskipTests package` 后 `java -jar`）→ 等 `/actuator/health` 200；用 curl 打 `GET /api/instances` 返回 `[]`、`POST /api/connections/test`（PG 真连）返回 ok。Expected: 200。
- [ ] **Step 3: 提交** — `git commit -m "chore(policy-server): v3 装配与配置（PG 默认/InMemory 测试 profile/直连 bean）"`

## Task G3: 集成契约回补（版本/回退/连接/联想端到端 MockMvc）

- [ ] **Step 1: 补 `web/V3PolicyFlowTest`**：MockMvc 全流程——`POST /api/instances`（真实 embedded PG 或 mock EngineAccess）→ 建 udf → 建策略（v1）→ `PUT`（v2）→ `GET .../versions`（两条）→ `rollback {1}`（v3, ROLLBACK, source=1）→ `GET /api/effective/{instance}` 断言 configVersion 推进、产物无 glob → `suggest` 返回快照条目。Run: `mvn -q -pl mask-policy-server test -Dtest='web.V3PolicyFlowTest'`。Expected: PASS。
- [ ] **Step 2: 提交** — `git commit -m "test(policy-server): v3 全流程集成契约（连接→udf→策略→版本→回退→effective→联想）"`

---

## 阶段 H：数据面消费验证（mask-core 不变）

## Task H1: mask-core 消费者回归

**Files:** 只读（mask-core 现状已含 instance 模式消费，不新增代码）
- `mask-core/src/test/java/io/sqlmask/config/source/PolicyServiceConfigSourceTest.java`、`mask-core/src/test/java/io/sqlmask/server/RewriteController...Test.java`（按现存测试名核对）

- [ ] **Step 1: 跑 mask-core 消费侧既有测试**确认 v3 契约（effective 形状、configVersion、LRU、fail-closed、stale）不回归 — Run: `mvn -q -pl mask-core test`。Expected: PASS。
- [ ] **Step 2: 记录**：若某测试因 v3 响应多出字段而失败 → 断言改为「关键字段存在 + 旧字段值不变」（记录理由）。若无失败 → 本任务零代码、仅在 H2 总结写入。提交（若无代码则无 commit，或提交文档 note）。

## Task H2: 端到端冒烟（compose 双服务 + PG）

**Files:**
- Modify: `docker/policy.Dockerfile`（按需：jar 路径不变 `mask-policy-server/target/*.jar`）
- Modify: `docker-compose.policy.yml`（按需：去掉无 Elasticsearch 依赖；确认 env `POLICY_PG_*`、端口对着）

- [ ] **Step 1: 构建 & 起服务** — Run: `mvn -q -pl mask-policy-server -am package -DskipTests && docker compose -f docker-compose.policy.yml up -d --build`。Expected: policy 服务 healthy、`curl localhost:8081/actuator/health` 200。
- [ ] **Step 2: 端到端脚本验证**（bash）：建连接（指向 compose 里的 PG 或外部可达 PG）→ 建实例（fetch metadata）→ 建带 glob 策略 → `PUT` 升级到 v2 → `rollback {1}` → `GET /api/effective/{instance}?user=alice` 断言 configVersion 递增、无 `*`/`?`；若 `mask-core` 服务也在（`POLICY_SERVICE_URL=http://localhost:8081`），用 `/api/rewrite {instance, sql, user}` 走一次改写冒烟。Expected: 全绿。
- [ ] **Step 3: 提交** — `git commit -m "test(e2e): v3 策略服务端到端冒烟（连接/版本回退/effective/改写）"`

---

## 阶段 I：验收与收尾

## Task I1: §8 验收门逐条核对（spec §8）

- [ ] **Step 1: 对照 spec §8 八条验收逐个跑测试**（连接管理 golden / 策略 CRUD / 版本回退矩阵 / 联想双通道 / 通配符矩阵 / 数据面主体 SELECT-only / YAML 回归 / 全仓回归）。Run: `mvn -q verify`（含 mask-core/mask-policy/mask-policy-server 全模块；若某些 checkstyle/spotbugs 门未配好则至少 `mvn -q test` 全绿并记录）。Expected: 逐条绿。
- [ ] **Step 2: 修复发现的任何失败**（TDD）；全部绿。
- [ ] **Step 3: 提交** — `git commit -m "test(accept): v3 §8 验收门核对通过"`

## Task I2: 文档与 README 收口

- [ ] **Step 1: 更新 `README.md`** 服务形态段：策略服务 v3 能力（连接管理/版本回退/联想/glob/SELECT-only/无认证）、端口 8081、`POLICY_PG_*`、移除 HTTP `import-metadata` 描述、`passwordRef` 约定、三方言范围、Hive/Spark 延后说明。
- [ ] **Step 2: 在 `docs/superpowers/specs/2026-09-21-sql-mask-v3-goal-design.md` 顶部状态行**由「目标定稿」改为「已实现（2026-09-21，见 plan）」，并在 §7 复用表补「实现对照」小节记录本计划对 spec 的收敛（passwordRef、三方言、PG、无 /api/v1、glob `?`、沿用既有 /api 契约）。
- [ ] **Step 3: 提交** — `git commit -m "docs(v3): README 服务形态 + spec 状态/实现对照更新"`

## Task I3: 全量回归 + 推送 GitHub

- [ ] **Step 1: 全仓回归** — Run: `mvn -q verify`（或最低 `mvn -q test`，如实记录哪个门过了）— Run 前确认真实 PG 可达或用测试 profile（zonky embedded 自动起）。Expected: 全绿（或如实记为部分门跳过）。
- [ ] **Step 2: 核对 git 状态** — `git status` 应无未提交变更；`git log --oneline -20` 检查任务 commit 链条完整。
- [ ] **Step 3: 推送** — `git push -u origin new-main`。Expected: 远端更新（`git status` 显示 ahead 归零）。
- [ ] **Step 4: 写最终总结**（作为正文输出给用户）：实现内容摘要、v2 完成度核对表（Phase 0 产物）、Deferred 清单（v2 非 v3 依赖项：hive/spark 方言、AES-GCM 加密、Flyway/H2、.github CI、TPC golden）、回归结果、推送结果（commit 哈希）。