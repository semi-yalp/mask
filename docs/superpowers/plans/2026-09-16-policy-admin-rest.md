# 策略服务管理面 REST 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐策略服务的管理面（实例/表列 CRUD、带主体的策略 CRUD、metaserver 导入、API Key 鉴权）与数据面（`/api/effective/{instance}?user=&groups=` 按主体编译 + 客户端主体缓存）。

**Architecture:** `PolicyEntity` 增加 `subjects`（复用 mask-policy 的 `SubjectSelector`，便捷构造器回退 `*` 保住全部既有调用点）；重叠校验按主体相交放宽；编译器加 `Subject` 参数先过滤再展开；`PolicyServiceConfigSource` 缓存改 `Map<主体键, ResolvedConfig>`；新建 `PolicyAdminController`、`EffectiveConfigController`、导入端点（`MetadataStructureFetcher` 薄缝包住既有 `MetadataClient`）与 `PolicyApiKeyFilter`（未配置 Key 不拦截）。

**Tech Stack:** Java 17 records、Spring Boot 3（MockMvc、FilterRegistrationBean）、Spring JdbcTemplate、Jackson、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-16-policy-admin-rest-design.md`

## Global Constraints

- 复用 `io.sqlmask.policy.model.SubjectSelector`（构造器即校验非空；`matchLevel(Subject) > 0` 即命中；`*` 通配）与 `Subject`（`Subject.of(user, groups)` 归一化、`Subject.anonymous()`）——不新建主体类型。
- 主体相交判定（重叠校验放宽的唯一依据）：selectorA 与 selectorB 相交 ⟺ 任一方 `users` 或 `groups` 含 `*`，或 `A.users ∩ B.users ≠ ∅`，或 `A.groups ∩ B.groups ≠ ∅`。
- `policy` 表新列 `subjects JSONB NOT NULL DEFAULT '{"users":["*"]}'::jsonb`；`JdbcPolicyStore` 读取对 NULL/空容错回退 `SubjectSelector(Set.of("*"), Set.of())`。
- `PolicyEntity` 保留 7 参便捷构造器（subjects 缺省 = 全体 `*`），既有调用点零改动。
- effective 无 `user`/`groups` 参数 = 匿名主体（仅命中 `*` 策略）；`policySummary`：`enabled` = 命中主体的启用策略数，`disabled` = 策略总数 − 该数。
- 错误码只用现有枚举（`CONFIG_ERROR` / `POLICY_INSTANCE_NOT_FOUND` / `POLICY_SERVICE_UNAVAILABLE` / `METADATA_SERVICE_UNAVAILABLE` / `METADATA_INSTANCE_NOT_FOUND`）；REST 错误 400 `{code,message}` 沿用 `ApiExceptionHandler`。
- 鉴权：`SQLMASK_ADMIN_API_KEY` 管 `/api/instances/**`，`SQLMASK_DATA_API_KEY` 管 `/api/effective/**`；**未配置（null/blank）不拦截**（mask-core 托管浏览器 UI，与 metaserver 的 fail-closed 有意不同）；401 响应体逐字节 `{"code":"UNAUTHORIZED","message":"missing or invalid API key","details":[]}`；请求头 `X-Api-Key`。
- 导入幂等：重复导入相同结构也推进 `config_version`（沿用 `updateInstanceTables` 语义）；实例已存在且 dialect 与快照不符 → `CONFIG_ERROR`。
- `config_version` 仍是实例级：任何变更推进，客户端据此刷新。
- 不修改：YAML/CLI 配置路径、`policies.yaml`/`PolicyEngine` 匹配逻辑、`UnknownFunctionTable`、`EffectiveConfigCompiler` 产物形状、mask-metadata 代码、UDF REST 行为。
- 测试命令在仓库根执行：`mvn -pl mask-core test -Dtest=<类名>`；代码风格 2 空格缩进、records、javadoc 一句话起头。

---

### Task 1: PolicyEntity 主体维度与主体相交重叠校验

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/model/PolicyEntity.java`
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`

**Interfaces:**
- Consumes: `io.sqlmask.policy.model.SubjectSelector`（mask-core 已依赖 mask-policy）。
- Produces: `PolicyEntity(String name, PolicyType policyType, boolean enabled, ResourceSelector resource, SubjectSelector subjects, String udf, List<Object> arguments, String filterExpr)`（8 参规范构造，subjects 在 resource 之后）+ 7 参便捷构造（subjects = `*` 全体）。Task 2/3/5 依赖。

- [ ] **Step 1: 写失败测试**

`PolicyValidatorTest` 追加（文件已有 `UDFS` fixture、`datamask(...)` helper 与静态导入）：

```java
  // ---- 主体相交重叠校验 ----

  private static PolicyEntity datamaskWithSubjects(String name, Set<String> users,
      Set<String> groups, List<String> columns) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", columns),
        new SubjectSelector(users, groups), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void sameTypeSameTableDisjointSubjectsDoNotOverlap() {
    PolicyEntity analysts = datamaskWithSubjects("a_mask", Set.of("alice"), Set.of(),
        List.of("phone"));
    PolicyEntity auditors = datamaskWithSubjects("b_mask", Set.of(), Set.of("auditors"),
        List.of("phone"));
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, analysts, List.of(auditors)));
  }

  @Test
  void wildcardSubjectOverlapsEverything() {
    PolicyEntity analysts = datamaskWithSubjects("a_mask", Set.of("alice"), Set.of(),
        List.of("phone"));
    PolicyEntity everyone = datamaskWithSubjects("b_mask", Set.of(), Set.of("*"),
        List.of("phone"));
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, everyone, List.of(analysts)))
        .getMessage().contains("overlaps"));
  }

  @Test
  void userIntersectionAndGroupIntersectionOverlap() {
    PolicyEntity a = datamaskWithSubjects("a_mask", Set.of("alice", "bob"), Set.of(),
        List.of("phone"));
    PolicyEntity b = datamaskWithSubjects("b_mask", Set.of("bob"), Set.of(),
        List.of("phone"));
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, b, List.of(a)));
    PolicyEntity c = datamaskWithSubjects("c_mask", Set.of(), Set.of("analytics"),
        List.of("phone"));
    PolicyEntity d = datamaskWithSubjects("d_mask", Set.of(), Set.of("analytics", "bi"),
        List.of("phone"));
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, d, List.of(c)));
  }

  @Test
  void rowFilterSameTableDisjointSubjectsAllowed() {
    PolicyEntity rfA = new PolicyEntity("rf_a", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("alice"), Set.of()), null, List.of(), "status = 'active'");
    PolicyEntity rfB = new PolicyEntity("rf_b", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of(), Set.of("auditors")), null, List.of(), "id > 0");
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, rfB, List.of(rfA)));
  }

  @Test
  void defaultConstructorMeansEveryone() {
    PolicyEntity legacy = datamask("legacy", "customer", List.of("phone")); // 7 参便捷构造
    assertEquals(Set.of("*"), legacy.subjects().users());
  }
```

顶部补 import：`io.sqlmask.policy.model.SubjectSelector`、`java.util.Set`、`static org.junit.jupiter.api.Assertions.assertEquals`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: COMPILATION ERROR（`PolicyEntity` 无 8 参构造/`subjects()` 访问器）。

- [ ] **Step 3: 实现**

`PolicyEntity.java` 整体替换为：

```java
package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;

import java.util.List;

/**
 * A stored policy: kind, enablement, subject selector, target selector and the
 * kind-specific payload — {@code udf} + {@code arguments} for datamask,
 * {@code filterExpr} for row filters.
 */
public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    ResourceSelector resource, SubjectSelector subjects, String udf,
    List<Object> arguments, String filterExpr) {

  public PolicyEntity {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    subjects = subjects == null
        ? new SubjectSelector(java.util.Set.of("*"), java.util.Set.of())
        : subjects;
  }

  /** Convenience constructor without subjects: the policy applies to everyone. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, resource, null, udf, arguments, filterExpr);
  }
}
```

`PolicyValidator`：重叠循环里 `boolean overlap = ...` 一行改为（`intersects` 调用保持不变）：

```java
      boolean overlap = (policy.policyType() == PolicyType.ROW_FILTER
          || intersects(other.resource().columns(), policy.resource().columns()))
          && subjectsMayOverlap(other.subjects(), policy.subjects());
```

类尾部新增静态方法：

```java
  /** Two selectors may hit the same subject: any "*" or a users/groups intersection. */
  private static boolean subjectsMayOverlap(
      io.sqlmask.policy.model.SubjectSelector a, io.sqlmask.policy.model.SubjectSelector b) {
    if (a.users().contains("*") || a.groups().contains("*")
        || b.users().contains("*") || b.groups().contains("*")) {
      return true;
    }
    return !java.util.Collections.disjoint(a.users(), b.users())
        || !java.util.Collections.disjoint(a.groups(), b.groups());
  }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: PASS（新增 5 个用例 + 既有全部通过——既有用例全走 7 参便捷构造 = 双方 `*`，重叠行为不变）。

Run: `mvn -pl mask-core test`
Expected: PASS（PolicyServiceTest 等全部编译运行通过——便捷构造器保住调用点）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/model/PolicyEntity.java \
        mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java \
        mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java
git commit -m "feat(policyserver): 策略主体维度与按主体相交放宽的重叠校验"
```

---

### Task 2: policy.subjects 存储列与 JdbcPolicyStore 读写

**Files:**
- Modify: `mask-core/src/main/resources/schema.sql`（policy 表加列）
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreIT.java`

**Interfaces:**
- Consumes: Task 1 的 `PolicyEntity.subjects()`（`SubjectSelector(Set<String> users, Set<String> groups)`，Jackson 可直接序列化/反序列化该 record）。
- Produces: `subjects JSONB` 持久化 + NULL 容错读取（Task 3/5 的 REST 与编译不碰存储细节）。

- [ ] **Step 1: 写失败测试**

`JdbcPolicyStoreIT` 追加（文件已有 `instanceName()` / `instance()` / `datamask(...)` helpers 与 UDF CRUD 段）：

```java
  // ---- subjects 持久化 ----

  @Test
  void policySubjectsRoundTrip() {
    store.createInstance(instance());
    PolicyEntity withSubjects = new PolicyEntity("p1", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new io.sqlmask.policy.model.SubjectSelector(
            java.util.Set.of("alice"), java.util.Set.of("analysts")),
        "mask_phone", List.of(3, 4), null);
    store.createPolicy(instanceName(), withSubjects);
    PolicyEntity loaded = store.findPolicy(instanceName(), "p1").orElseThrow();
    assertEquals(java.util.Set.of("alice"), loaded.subjects().users());
    assertEquals(java.util.Set.of("analysts"), loaded.subjects().groups());
  }

  @Test
  void nullSubjectsColumnReadsAsEveryone() {
    store.createInstance(instance());
    // 直接插入无 subjects 的旧行，模拟存量库
    cleanupJdbc.update("INSERT INTO policy (instance_id, name, policy_type, is_enabled,"
            + " udf, arguments, filter_expr, resource, subjects) VALUES (?, 'legacy',"
            + " 'DATAMASK', true, 'mask_phone', '[]'::jsonb, NULL, ?::jsonb, NULL)",
        instanceIdOf(instanceName()), "{\"catalog\":\"crm\",\"schema\":\"public\","
            + "\"table\":\"customer\",\"columns\":[\"phone\"]}");
    PolicyEntity legacy = store.findPolicy(instanceName(), "legacy").orElseThrow();
    assertEquals(java.util.Set.of("*"), legacy.subjects().users());
  }

  private Long instanceIdOf(String name) {
    return cleanupJdbc.queryForObject(
        "SELECT id FROM policy_instance WHERE name = ?", Long.class, name);
  }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=JdbcPolicyStoreIT`
Expected: 无 `POLICY_PG_URL` 时 SKIPPED（编译通过——Task 1 已加 8 参构造）；本任务靠 Step 3 之后有 PG 环境的运行验证（见 Step 4）。

- [ ] **Step 3: 实现**

`schema.sql` 的 `policy` 表定义中，`resource JSONB NOT NULL` 一行后追加（并在文件该表上方加一行注释说明存量库需手动 ALTER）：

```sql
-- 存量库升级：ALTER TABLE policy ADD COLUMN subjects JSONB;
--              UPDATE policy SET subjects = '{"users":["*"]}'::jsonb WHERE subjects IS NULL;
  subjects JSONB NOT NULL DEFAULT '{"users":["*"]}'::jsonb,
```

`JdbcPolicyStore`：
1. `insertPolicy` 的 SQL 列表加 `subjects`（`?::jsonb`），绑定 `ps.setString(n, toJson(policy.subjects()))`；
2. `updatePolicy` 的 SET 子句加 `subjects = ?::jsonb` 并绑定（参数序号顺延）；
3. `selectPolicySql()` 加列 `subjects`；
4. `mapPolicy` 构造 `PolicyEntity` 改用 8 参构造，传 `subjectFrom(rs.getString("subjects"))`；
5. 新增：

```java
  /** Null-tolerant: a missing/blank column (pre-migration row) reads as everyone. */
  private io.sqlmask.policy.model.SubjectSelector subjectFrom(String json) {
    if (json == null || json.isBlank()) {
      return new io.sqlmask.policy.model.SubjectSelector(Set.of("*"), Set.of());
    }
    try {
      return mapper.readValue(json, io.sqlmask.policy.model.SubjectSelector.class);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize policy subjects: " + e.getMessage(), e);
    }
  }
```

import 补 `java.util.Set`。

- [ ] **Step 4: 跑测试**

无 PG：`mvn -pl mask-core test` → PASS（IT skipped，InMemory 路径不受影响）。
有 PG（`POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/sqlmask_policy`，用户名密码 env 同名前缀）：`mvn -pl mask-core test -Dtest=JdbcPolicyStoreIT` → PASS（若 schema.sql 的 CREATE IF NOT EXISTS 不给已存在的 policy 表加列，先手工执行注释中的 ALTER——IT 的 `@BeforeAll` 只对新库生效）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/resources/schema.sql \
        mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java \
        mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreIT.java
git commit -m "feat(policyserver): policy.subjects 列与 JdbcPolicyStore 读写（NULL 容错）"
```

---

### Task 3: 按主体的生效配置（编译器 + 服务 + REST 端点）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java`
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyService.java`（`effective` 加 Subject 参数）
- Create: `mask-core/src/main/java/io/sqlmask/server/EffectiveConfigController.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java`（既有，扩展）
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java`（调用点更新）
- Test: `mask-core/src/test/java/io/sqlmask/server/EffectiveConfigEndpointTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `PolicyEntity.subjects()`；`SubjectSelector.matchLevel(Subject)`。
- Produces: `EffectiveConfigCompiler.compile(EngineInstance, List<PolicyEntity>, Subject)`（3 参，Task 4/5 依赖）；`PolicyService.effective(String name, Subject subject)`；`GET /api/effective/{instance}?user=&groups=`（groups 逗号分隔或重复参数均可）。

- [ ] **Step 1: 写失败测试**

`EffectiveConfigCompilerTest` 追加（先读该文件，沿用其既有 instance/policy fixture 构造方式；下列用例按其 helper 命名调整）：

```java
  // ---- 按主体过滤 ----

  @Test
  void anonymousSubjectSeesOnlyWildcardPolicies() {
    // enabled 策略：一条 subjects=["*"]，一条 subjects=users:["alice"]，均脱敏 phone 列
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        instance(), List.of(wildcardMask(), aliceMask()), Subject.anonymous());
    assertTrue(response.config().columns().stream()
        .noneMatch(c -> "alice_mask".equals(c.policy())));
    assertTrue(response.config().columns().stream()
        .anyMatch(c -> "wildcard_mask".equals(c.policy())));
    assertEquals(1, response.policySummary().enabled());
    assertEquals(1, response.policySummary().disabled()); // 未命中计入 disabled
  }

  @Test
  void namedSubjectSeesOwnAndWildcardPolicies() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        instance(), List.of(wildcardMask(), aliceMask()), Subject.of("alice", List.of()));
    assertEquals(2, response.policySummary().enabled());
  }

  @Test
  void groupMembershipSelectsPolicies() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        instance(), List.of(analystsRowFilter()), Subject.of("bob", List.of("analysts")));
    // row_filter 只对该主体回填到表上
    assertTrue(response.config().metadata().tables().stream()
        .anyMatch(t -> t.rowFilter() != null && t.rowFilter().contains("status")));
  }
```

`PolicyServiceTest`：既有 `service.effective("pg_prod")` 调用改为 `service.effective("pg_prod", Subject.anonymous())`，并补一个断言主体差异的用例：

```java
  @Test
  void effectiveFiltersBySubject() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("only_alice", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(), null));
    assertEquals(1, service.effective("pg_prod", Subject.of("alice", List.of()))
        .config().columns().size());
    assertEquals(0, service.effective("pg_prod", Subject.of("bob", List.of()))
        .config().columns().size());
  }
```

import 补 `io.sqlmask.policy.model.Subject`、`io.sqlmask.policy.model.SubjectSelector`、`java.util.Set`。

新建 `EffectiveConfigEndpointTest`：

```java
package io.sqlmask.server;

import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Data plane: subject-parameterized effective config over REST. */
@SpringBootTest
@AutoConfigureMockMvc
class EffectiveConfigEndpointTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUp() {
    try {
      service.createInstance("pg_prod", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    } catch (io.sqlmask.error.SqlMaskException alreadyExists) {
      // 上下文复用
    }
  }

  @Test
  void returnsSubjectFilteredConfig() throws Exception {
    mvc.perform(get("/api/effective/pg_prod").param("user", "alice").param("groups", "a,b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.dialect").value("postgresql"))
        .andExpect(jsonPath("$.configVersion").isNumber());
  }

  @Test
  void unknownInstanceMapsToNotFound() throws Exception {
    mvc.perform(get("/api/effective/nope").param("user", "alice"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=EffectiveConfigCompilerTest,PolicyServiceTest,EffectiveConfigEndpointTest`
Expected: COMPILATION ERROR（compile/effective 无 3 参/2 参形态；端点 404）。

- [ ] **Step 3: 实现**

`EffectiveConfigCompiler.compile` 签名与过滤：

```java
  public static EffectiveConfigResponse compile(EngineInstance instance,
      List<PolicyEntity> policies, Subject subject) {
    List<PolicyEntity> enabled = policies.stream()
        .filter(PolicyEntity::enabled)
        .filter(p -> p.subjects().matchLevel(subject) > 0)
        .toList();
    int disabled = policies.size() - enabled.size();
    // 其余逻辑不变（enabled 的展开与 summary 用上面两个值）
```

（`PolicySummary(enabled.size(), disabled)` 沿用；import 补 `io.sqlmask.policy.model.Subject`。）

`PolicyService.effective`：

```java
  public EffectiveConfigResponse effective(String name, io.sqlmask.policy.model.Subject subject) {
    EngineInstance instance = requireInstance(name);
    long configVersion = store.currentVersion(name);
    EffectiveConfigResponse compiled =
        EffectiveConfigCompiler.compile(instance, store.listPolicies(name), subject);
    return new EffectiveConfigResponse(instance.name(), instance.dialect(),
        configVersion, compiled.policySummary(), compiled.config());
  }
```

新建 `EffectiveConfigController.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Policy-service data plane: the subject-parameterized compiled effective
 * config. Absent user/groups is the anonymous subject (wildcard policies only).
 */
@RestController
public class EffectiveConfigController {

  private final PolicyService service;

  public EffectiveConfigController(PolicyService service) {
    this.service = service;
  }

  @GetMapping("/api/effective/{instance}")
  public EffectiveConfigResponse effective(
      @PathVariable("instance") String instance,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "groups", required = false) List<String> groups) {
    return service.effective(instance, Subject.of(user, groups));
  }
}
```

（显式 `@PathVariable("instance")`——本仓构建无 `-parameters` 编译旗。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=EffectiveConfigCompilerTest,PolicyServiceTest,EffectiveConfigEndpointTest` → PASS
Run: `mvn -pl mask-core test` → PASS（含 Task 1/2 全部）

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java \
        mask-core/src/main/java/io/sqlmask/policyserver/PolicyService.java \
        mask-core/src/main/java/io/sqlmask/server/EffectiveConfigController.java \
        mask-core/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java \
        mask-core/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java \
        mask-core/src/test/java/io/sqlmask/server/EffectiveConfigEndpointTest.java
git commit -m "feat(policyserver): 生效配置按主体编译与 /api/effective 端点"
```

---

### Task 4: PolicyServiceConfigSource 主体缓存

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/config/source/PolicyServiceConfigSource.java`
- Test: `mask-core/src/test/java/io/sqlmask/config/source/PolicyServiceConfigSourceTest.java`

**Interfaces:**
- Consumes: `Subject`；Task 3 的端点 query 语义（`user=`、`groups=` 重复参数）。
- Produces: `ResolvedConfig load(Subject subject)`（主入口）、`@Override load()`（匿名主体，保持 `ConfigSource` 兼容）、`boolean refresh()`（刷新全部已缓存主体，任一版本变更返回 true）。

- [ ] **Step 1: 写失败测试**

`PolicyServiceConfigSourceTest` 追加（沿用既有 HttpServer/AtomicReference 模式；补一个记录 query 的 `AtomicReference<String> seenQuery`，在 `createContext` 里 `seenQuery.set(exchange.getRequestURI().getQuery())`）：

```java
  // ---- 主体感知 ----

  @Test
  void loadBySubjectSendsQueryParamsAndCachesIndependently() {
    PolicyServiceConfigSource s = source();
    ConfigSource.ResolvedConfig alice = s.load(Subject.of("alice", List.of("a", "b")));
    assertEquals("postgresql", alice.dialect());
    assertTrue(seenQuery.get().contains("user=alice"));
    assertTrue(seenQuery.get().contains("groups=a"));
    assertTrue(seenQuery.get().contains("groups=b"));
    s.load(Subject.of("bob", List.of()));
    assertTrue(seenQuery.get().contains("user=bob"));
    s.load(Subject.of("alice", List.of("a", "b"))); // 命中缓存，不再发请求
    assertEquals("user=bob", seenQuery.get());
  }

  @Test
  void anonymousLoadSendsNoQuery() {
    source().load(Subject.anonymous());
    assertTrue(seenQuery.get() == null || seenQuery.get().isEmpty());
  }

  @Test
  void refreshUpdatesAllCachedSubjects() {
    PolicyServiceConfigSource s = source();
    s.load(Subject.of("alice", List.of()));
    s.load(Subject.of("bob", List.of()));
    assertFalse(s.refresh()); // 两主体同版本
    body.set(BODY_V1.replace("\"configVersion\":1", "\"configVersion\":2"));
    assertTrue(s.refresh());
    assertEquals(2, s.load(Subject.of("alice", List.of())).configVersion());
    assertEquals(2, s.load(Subject.of("bob", List.of())).configVersion());
  }

  @Test
  void coldSubjectFailsClosedWhenServiceDown() {
    PolicyServiceConfigSource s = source();
    s.load(Subject.of("alice", List.of())); // 温缓存主体
    server.stop(0);
    assertEquals(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
        assertThrows(SqlMaskException.class,
            () -> s.load(Subject.of("newbie", List.of()))).getCode());
    // 已缓存主体 stale 可用
    assertEquals("postgresql", s.load(Subject.of("alice", List.of())).dialect());
  }
```

import 补 `io.sqlmask.policy.model.Subject`、`static org.junit.jupiter.api.Assertions.assertFalse`。（既有用例的 `load()` 调用不动——匿名语义兼容。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyServiceConfigSourceTest`
Expected: COMPILATION ERROR（无 `load(Subject)`）。

- [ ] **Step 3: 实现**

`PolicyServiceConfigSource` 改造（保留构造器签名与全部错误映射，替换缓存与 URI 逻辑）：

```java
  private record SubjectKey(String user, List<String> groups) {
  }

  private final java.util.Map<SubjectKey, ResolvedConfig> cache = new java.util.LinkedHashMap<>();

  @Override
  public synchronized ResolvedConfig load() {
    return load(io.sqlmask.policy.model.Subject.anonymous());
  }

  /** Loads (and caches) the effective config compiled for one subject. */
  public synchronized ResolvedConfig load(io.sqlmask.policy.model.Subject subject) {
    SubjectKey key = keyOf(subject);
    ResolvedConfig cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    ResolvedConfig fresh = fetchAndAssemble(key);
    cache.put(key, fresh);
    return fresh;
  }

  /** Polls every cached subject; true when any subject's version moved. */
  public synchronized boolean refresh() {
    boolean anyUpdated = false;
    for (java.util.Map.Entry<SubjectKey, ResolvedConfig> entry : cache.entrySet()) {
      ResolvedConfig fresh = fetchAndAssemble(entry.getKey());
      if (entry.getValue().configVersion() != fresh.configVersion()) {
        entry.setValue(fresh);
        anyUpdated = true;
      }
    }
    return anyUpdated;
  }

  private static SubjectKey keyOf(io.sqlmask.policy.model.Subject subject) {
    return new SubjectKey(subject.user(),
        List.copyOf(new java.util.TreeSet<>(subject.groups())));
  }

  private ResolvedConfig fetchAndAssemble(SubjectKey key) {
    HttpRequest request = HttpRequest.newBuilder(effectiveUri(key))
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
    // ……（其余 HTTP 发送、状态码映射、JSON 解析逻辑保持原样，
    //      只把 effectiveUri 改为带 query 的重载）
  }

  private URI effectiveUri(SubjectKey key) {
    List<String> params = new ArrayList<>();
    if (key.user() != null) {
      params.add("user=" + URLEncoder.encode(key.user(), StandardCharsets.UTF_8));
    }
    for (String group : key.groups()) {
      params.add("groups=" + URLEncoder.encode(group, StandardCharsets.UTF_8));
    }
    String query = params.isEmpty() ? "" : "?" + String.join("&", params);
    return URI.create(baseUrl + "/api/effective/" + instanceName + query);
  }
```

字段相应调整：`effectiveUri` 单值字段改为 `baseUrl`（String）；`fetchAndAssemble(SubjectKey)` 内组装 `Subject` 不需要——URI 只用 key。javadoc 更新为按主体缓存语义。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=PolicyServiceConfigSourceTest` → PASS
Run: `mvn -pl mask-core test` → PASS

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/config/source/PolicyServiceConfigSource.java \
        mask-core/src/test/java/io/sqlmask/config/source/PolicyServiceConfigSourceTest.java
git commit -m "feat(policyserver): PolicyServiceConfigSource 按主体缓存与查询参数"
```

---

### Task 5: 实例与策略 REST CRUD（PolicyAdminController）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/PolicyAdminEndpointTest.java`

**Interfaces:**
- Consumes: `PolicyService` 全部实例/策略方法；Task 1 的 8 参 `PolicyEntity`。
- Produces: `/api/instances`（POST/GET）、`/api/instances/{name}`（GET/DELETE）、`/api/instances/{name}/tables`（PUT）、`/api/instances/{name}/policies`（POST/GET）、`/api/instances/{name}/policies/{policy}`（GET/PUT/DELETE）；DTO 见实现。

- [ ] **Step 1: 写失败测试**

新建 `PolicyAdminEndpointTest`（全流程：建实例 → 注册 UDF → 建带主体策略 → 列表/更新/删除）：

```java
package io.sqlmask.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instance and policy admin CRUD over REST, through the PolicyService facade. */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyAdminEndpointTest {

  private static final String INSTANCE_BODY = """
      {"name": "pg_prod", "dialect": "postgresql",
       "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "phone", "type": "varchar"}]}]}
      """;

  private static final String POLICY_BODY = """
      {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
       "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                    "columns": ["phone"]},
       "subjects": {"users": ["alice"], "groups": []},
       "udf": "mask_phone", "arguments": [3, 4]}
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private io.sqlmask.policyserver.PolicyService service;

  @BeforeEach
  void cleanUp() {
    try {
      service.deletePolicy("pg_prod", "phone_mask_analysts");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteUdf("pg_prod", "mask_phone");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("pg_prod");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留（有策略时先删策略再删实例）
    }
  }

  @Test
  void instanceLifecycle() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pg_prod"))
        .andExpect(jsonPath("$.tables[0].columns[0].name").value("phone"));

    mvc.perform(get("/api/instances")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/instances/pg_prod")).andExpect(status().isOk())
        .andExpect(jsonPath("$.dialect").value("postgresql"));

    mvc.perform(put("/api/instances/pg_prod/tables").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tables\": [" + instanceTableJson() + "]}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subjects.users[0]").value("alice"));

    // 有策略时删实例被拒
    mvc.perform(delete("/api/instances/pg_prod"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(get("/api/instances/pg_prod/policies")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(put("/api/instances/pg_prod/policies/phone_mask_analysts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY.replace("\"isEnabled\": true", "\"isEnabled\": false")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isEnabled").value(false));
    mvc.perform(delete("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/pg_prod")).andExpect(status().isOk());
    mvc.perform(get("/api/instances/pg_prod"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void errorContractFollowsFacade() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\": \"x\", \"dialect\": \"oracle\", \"tables\": []}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
    mvc.perform(get("/api/instances/nope/policies"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
    mvc.perform(post("/api/instances/nope/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  private static String instanceTableJson() {
    return "{\"catalog\": \"crm\", \"schema\": \"public\", \"name\": \"customer\","
        + " \"columns\": [{\"name\": \"phone\", \"type\": \"varchar\"}]}";
  }
}
```

（注：`PolicyEntity` 无 `enabled` JSON 字段名差异——响应 DTO 统一输出 `isEnabled`，见实现；上面 PUT 断言 `$.enabled` 若与 DTO 命名不符，以实现的 DTO 字段名为准并保持请求/响应同名 `isEnabled`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyAdminEndpointTest`
Expected: FAILURE（404，无 controller）。

- [ ] **Step 3: 实现**

新建 `PolicyAdminController.java`：

```java
package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Instance and policy admin CRUD — the policy service's management surface,
 * delegating every mutation to the validating PolicyService facade.
 */
@RestController
@RequestMapping("/api/instances")
public class PolicyAdminController {

  public record ColumnDto(String name, String type) {
  }

  public record TableDto(String catalog, String schema, String name, List<ColumnDto> columns) {
  }

  public record InstanceDto(String name, String dialect, List<TableDto> tables) {
  }

  public record TablesDto(List<TableDto> tables) {
  }

  public record SubjectDto(Set<String> users, Set<String> groups) {
  }

  public record ResourceDto(String catalog, String schema, String table, List<String> columns) {
  }

  public record PolicyDto(String name, String policyType, boolean isEnabled,
      ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments,
      String filterExpr) {
  }

  private final PolicyService service;

  public PolicyAdminController(PolicyService service) {
    this.service = service;
  }

  @PostMapping
  public InstanceDto create(@RequestBody InstanceDto request) {
    return toDto(service.createInstance(request.name(), request.dialect(), toTables(request.tables())));
  }

  @GetMapping
  public List<InstanceDto> list() {
    return service.instances().stream().map(PolicyAdminController::toDto).toList();
  }

  @GetMapping("/{name}")
  public InstanceDto get(@PathVariable("name") String name) {
    return toDto(service.instance(name));
  }

  @PutMapping("/{name}/tables")
  public InstanceDto replaceTables(@PathVariable("name") String name,
      @RequestBody TablesDto request) {
    return toDto(service.updateInstanceTables(name, toTables(request.tables())));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable("name") String name) {
    service.deleteInstance(name);
  }

  @PostMapping("/{name}/policies")
  public PolicyDto createPolicy(@PathVariable("name") String name,
      @RequestBody PolicyDto request) {
    return toDto(service.createPolicy(name, toModel(request)));
  }

  @GetMapping("/{name}/policies")
  public List<PolicyDto> listPolicies(@PathVariable("name") String name) {
    return service.policies(name).stream().map(PolicyAdminController::toDto).toList();
  }

  @GetMapping("/{name}/policies/{policy}")
  public PolicyDto getPolicy(@PathVariable("name") String name, @PathVariable("policy") String policy) {
    return service.policies(name).stream()
        .filter(p -> p.name().equals(policy)).findFirst().map(PolicyAdminController::toDto)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policy '" + policy + "' not found in instance '" + name + "'"));
  }

  @PutMapping("/{name}/policies/{policy}")
  public PolicyDto updatePolicy(@PathVariable("name") String name,
      @PathVariable("policy") String policy, @RequestBody PolicyDto request) {
    return toDto(service.updatePolicy(name, policy, toModel(request)));
  }

  @DeleteMapping("/{name}/policies/{policy}")
  public void deletePolicy(@PathVariable("name") String name, @PathVariable("policy") String policy) {
    service.deletePolicy(name, policy);
  }

  private static List<TableDef> toTables(List<TableDto> tables) {
    if (tables == null) {
      return List.of();
    }
    return tables.stream()
        .map(t -> new TableDef(t.catalog(), t.schema(), t.name(),
            (t.columns() == null ? List.<ColumnDto>of() : t.columns()).stream()
                .map(c -> new ColumnDef(c.name(), c.type())).toList()))
        .toList();
  }

  private static PolicyEntity toModel(PolicyDto dto) {
    SubjectSelector subjects = dto.subjects() == null ? null
        : new SubjectSelector(dto.subjects().users(), dto.subjects().groups());
    return new PolicyEntity(dto.name(), parseType(dto.policyType()), dto.isEnabled(),
        new ResourceSelector(dto.resource().catalog(), dto.resource().schema(),
            dto.resource().table(), dto.resource().columns() == null
                ? List.of() : dto.resource().columns()),
        subjects, dto.udf(), dto.arguments(), dto.filterExpr());
  }

  private static PolicyType parseType(String raw) {
    try {
      return PolicyType.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown policyType '" + raw + "' (expected: DATAMASK, ROW_FILTER)");
    }
  }

  private static PolicyDto toDto(PolicyEntity p) {
    return new PolicyDto(p.name(), p.policyType().name().toLowerCase(Locale.ROOT),
        p.enabled(),
        new ResourceDto(p.resource().catalog(), p.resource().schema(),
            p.resource().table(), p.resource().columns()),
        new SubjectDto(p.subjects().users(), p.subjects().groups()),
        p.udf(), p.arguments(), p.filterExpr());
  }

  private static InstanceDto toDto(EngineInstance i) {
    return new InstanceDto(i.name(), i.dialect(), i.tables().stream()
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(), t.columns().stream()
            .map(c -> new ColumnDto(c.name(), c.typeDeclaration())).toList()))
        .toList());
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=PolicyAdminEndpointTest` → PASS
Run: `mvn -pl mask-core test` → PASS（UDF/Effective 端点测试不受影响——同一 InMemory store bean，测试实例名隔离）

注意：`UdfEndpointTest` / `EffectiveConfigEndpointTest` 也建 `pg_prod`——若 `PolicyAdminEndpointTest` 的 cleanUp 与它们共用上下文，保证各测试自清理（本测试 cleanUp 删实例；UdfEndpointTest 的 setUp 会重建）。若上下文共享导致实例已存在冲突，统一在各测试 setUp 用 try-catch 已存在模式（沿用 UdfEndpointTest 写法）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java \
        mask-core/src/test/java/io/sqlmask/server/PolicyAdminEndpointTest.java
git commit -m "feat(server): 实例与策略管理面 REST（PolicyAdminController）"
```

---

### Task 6: metaserver 导入端点

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/MetadataStructureFetcher.java`（接口 + 默认 HTTP 实现）
- Create: `mask-core/src/main/java/io/sqlmask/server/MetadataImportController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（注册 fetcher bean）
- Test: `mask-core/src/test/java/io/sqlmask/server/MetadataImportEndpointTest.java`

**Interfaces:**
- Consumes: `MetadataClient.fetch(String)` → `MetadataSnapshot(String instance, String dialect, long metadataVersion, List<TableSnapshot> tables)`，`TableSnapshot(String catalog, String schema, String name, List<ColumnSnapshot> columns)`，`ColumnSnapshot(String name, String type)`；`PolicyService.createInstance/updateInstanceTables/instances`。
- Produces: `POST /api/instances/{name}/import-metadata`，body `{"metadataBaseUrl": "...", "metadataApiKey": "...", "metadataInstance": "..."}`。

- [ ] **Step 1: 写失败测试**

新建 `MetadataImportEndpointTest`（stub fetcher 经 @TestConfiguration 注入）：

```java
package io.sqlmask.server;

import io.sqlmask.metadataclient.MetadataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Import-from-metaserver endpoint with a stubbed structure fetcher. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MetadataImportEndpointTest.StubFetcher.class)
class MetadataImportEndpointTest {

  @TestConfiguration
  static class StubFetcher {
    @Bean
    MetadataStructureFetcher metadataStructureFetcher() {
      return (baseUrl, apiKey, instance) -> SNAPSHOT.get();
    }
  }

  private static final AtomicReference<MetadataClient.MetadataSnapshot> SNAPSHOT =
      new AtomicReference<>(new MetadataClient.MetadataSnapshot("meta_pg", "postgresql", 7,
          List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
              List.of(new MetadataClient.ColumnSnapshot("phone", "varchar"))))));

  @Autowired
  private MockMvc mvc;

  @Autowired
  private io.sqlmask.policyserver.PolicyService service;

  @BeforeEach
  void cleanUp() {
    try {
      service.deleteInstance("imported");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("pg_prod");
    } catch (io.sqlmask.error.SqlMaskException e) {
      // 有策略等遗留时忽略（其它测试的实例不强制清）
    }
  }

  @Test
  void createsInstanceFromSnapshot() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://localhost:8082\","
                + " \"metadataApiKey\": \"k\", \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("imported"))
        .andExpect(jsonPath("$.dialect").value("postgresql"))
        .andExpect(jsonPath("$.tables[0].name").value("customer"));
  }

  @Test
  void updatesTablesOfExistingInstance() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk());
    SNAPSHOT.set(new MetadataClient.MetadataSnapshot("meta_pg", "postgresql", 8,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
            List.of(new MetadataClient.ColumnSnapshot("phone", "varchar"),
                new MetadataClient.ColumnSnapshot("email", "varchar"))))));
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].columns.length()").value(2));
  }

  @Test
  void dialectMismatchIsRejected() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk());
    SNAPSHOT.set(new MetadataClient.MetadataSnapshot("meta_pg", "mysql", 9,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
            List.of(new MetadataClient.ColumnSnapshot("phone", "varchar"))))));
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=MetadataImportEndpointTest`
Expected: FAILURE/编译错（无 fetcher/端点）。

- [ ] **Step 3: 实现**

`MetadataStructureFetcher.java`：

```java
package io.sqlmask.server;

import io.sqlmask.metadataclient.MetadataClient;

/**
 * Thin seam over {@link MetadataClient} so import flows (and their tests) don't
 * depend on a live metadata service.
 */
public interface MetadataStructureFetcher {

  MetadataClient.MetadataSnapshot fetch(String baseUrl, String apiKey, String instance);
}
```

同文件或新文件放默认实现（放同文件保持一个职责单元）：

```java
  /** Production impl: one-shot client per call. */
  class HttpMetadataStructureFetcher implements MetadataStructureFetcher {
    @Override
    public MetadataClient.MetadataSnapshot fetch(String baseUrl, String apiKey, String instance) {
      return new MetadataClient(baseUrl, apiKey).fetch(instance);
    }
  }
```

`MetadataImportController.java`：

```java
package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadataclient.MetadataClient;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.TableDef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Imports table structures collected by the metadata service into a policy
 * instance: creates the instance (dialect from the snapshot) or replaces its
 * tables wholesale; repeated imports of identical structures still advance
 * config_version (updateInstanceTables semantics).
 */
@RestController
public class MetadataImportController {

  public record ImportRequest(String metadataBaseUrl, String metadataApiKey,
      String metadataInstance) {
  }

  public record ImportResponse(String name, String dialect, int tables) {
  }

  private final PolicyService service;
  private final MetadataStructureFetcher fetcher;

  public MetadataImportController(PolicyService service, MetadataStructureFetcher fetcher) {
    this.service = service;
    this.fetcher = fetcher;
  }

  @PostMapping("/api/instances/{name}/import-metadata")
  public ImportResponse importMetadata(@PathVariable("name") String name,
      @RequestBody ImportRequest request) {
    if (request == null || request.metadataBaseUrl() == null
        || request.metadataBaseUrl().isBlank() || request.metadataInstance() == null
        || request.metadataInstance().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataBaseUrl and metadataInstance are required");
    }
    MetadataClient.MetadataSnapshot snapshot = fetcher.fetch(request.metadataBaseUrl(),
        request.metadataApiKey(), request.metadataInstance());
    List<TableDef> tables = snapshot.tables().stream()
        .map(t -> new TableDef(t.catalog(), t.schema(), t.name(),
            t.columns().stream()
                .map(c -> new ColumnDef(c.name(), c.type())).toList()))
        .toList();
    EngineInstance existing = service.instances().stream()
        .filter(i -> i.name().equals(name)).findFirst().orElse(null);
    if (existing == null) {
      return new ImportResponse(name, snapshot.dialect(),
          service.createInstance(name, snapshot.dialect(), tables).tables().size());
    }
    if (!existing.dialect().equals(snapshot.dialect())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance '" + name
          + "' is dialect '" + existing.dialect() + "' but metadata instance '"
          + request.metadataInstance() + "' reports '" + snapshot.dialect() + "'");
    }
    return new ImportResponse(name, snapshot.dialect(),
        service.updateInstanceTables(name, tables).tables().size());
  }
}
```

`SqlMaskServiceApplication` 追加 bean：

```java
  @Bean
  MetadataStructureFetcher metadataStructureFetcher() {
    return new MetadataStructureFetcher.HttpMetadataStructureFetcher();
  }
```

import 补 `io.sqlmask.server.MetadataStructureFetcher`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=MetadataImportEndpointTest` → PASS
Run: `mvn -pl mask-core test` → PASS

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/server/MetadataStructureFetcher.java \
        mask-core/src/main/java/io/sqlmask/server/MetadataImportController.java \
        mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
        mask-core/src/test/java/io/sqlmask/server/MetadataImportEndpointTest.java
git commit -m "feat(server): 从 metaserver 导入表结构建/更新策略实例"
```

---

### Task 7: PolicyApiKeyFilter 与文档回写

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/PolicyApiKeyFilter.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（FilterRegistrationBean）
- Test: `mask-core/src/test/java/io/sqlmask/server/PolicyApiKeyFilterTest.java`
- Modify: `README.md`（管理面章节）
- Modify: `docs/superpowers/specs/2026-09-16-policy-admin-rest-design.md`（状态行）

**Interfaces:**
- Consumes: 既有端点路径前缀；`System.getenv("SQLMASK_ADMIN_API_KEY")` / `System.getenv("SQLMASK_DATA_API_KEY")`。
- Produces: 鉴权行为（未配置 Key 的路径放行；401 形状对齐 metaserver）。

- [ ] **Step 1: 写失败测试**

新建 `PolicyApiKeyFilterTest`（纯单元测试，沿 metaserver `ApiKeyFilterTest` 的 MockHttpServletRequest 模式）：

```java
package io.sqlmask.server;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyApiKeyFilterTest {

  private final MockFilterChain chain = new MockFilterChain();

  private MockHttpServletResponse run(PolicyApiKeyFilter filter, String path, String key)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void unconfiguredKeysPassEverything() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter(null, null);
    assertEquals(200, run(filter, "/api/instances", "whatever").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg_prod", null).getStatus());
  }

  @Test
  void adminAndDataKeysAreSeparate() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/instances", "data-secret").getStatus());
    assertEquals(200, run(filter, "/api/instances", "admin-secret").getStatus());
    assertEquals(401, run(filter, "/api/effective/pg_prod", "admin-secret").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg_prod", "data-secret").getStatus());
  }

  @Test
  void unauthenticatedShapeMatchesMetaserver() throws Exception {
    MockHttpServletResponse response = run(
        new PolicyApiKeyFilter("admin-secret", null), "/api/instances/pg_prod/udfs", null);
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\","
        + "\"details\":[]}", response.getContentAsString());
  }

  @Test
  void otherPathsPassEvenWhenConfigured() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(200, run(filter, "/api/rewrite", null).getStatus());
    assertEquals(200, run(filter, "/index.html", null).getStatus());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyApiKeyFilterTest`
Expected: COMPILATION ERROR（类不存在）。

- [ ] **Step 3: 实现**

`PolicyApiKeyFilter.java`：

```java
package io.sqlmask.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Static API key gate over the policy service surfaces: the admin key guards
 * /api/instances/**, the data key guards /api/effective/**. An unconfigured
 * key leaves its surface open (this app also serves a local browser UI);
 * a configured key rejects every request without a matching X-Api-Key.
 */
public final class PolicyApiKeyFilter implements Filter {

  private final String adminKey;
  private final String dataKey;

  public PolicyApiKeyFilter(String adminKey, String dataKey) {
    this.adminKey = adminKey;
    this.dataKey = dataKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String required = requiredKey(request.getRequestURI());
    if (required == null) {
      chain.doFilter(req, res);
      return;
    }
    if (!required.equals(request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }

  /** Null when the path is unmanaged or its key is unconfigured (open). */
  private String requiredKey(String path) {
    if (path.startsWith("/api/instances")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    if (path.startsWith("/api/effective")) {
      return dataKey == null || dataKey.isBlank() ? null : dataKey;
    }
    return null;
  }
}
```

`SqlMaskServiceApplication` 追加注册（env 在启动时读取一次）：

```java
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<PolicyApiKeyFilter> policyApiKeyFilter() {
    org.springframework.boot.web.servlet.FilterRegistrationBean<PolicyApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new PolicyApiKeyFilter(
            System.getenv("SQLMASK_ADMIN_API_KEY"), System.getenv("SQLMASK_DATA_API_KEY")));
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
    registration.setOrder(1);
    return registration;
  }
```

（既有端点测试不受影响：测试环境两 env 均未配置 → 放行。）

- [ ] **Step 4: 跑测试与全模块回归**

Run: `mvn -pl mask-core test -Dtest=PolicyApiKeyFilterTest` → PASS
Run: `mvn -pl mask-core test` → PASS（全部端点测试在无 Key 环境照常通过）
Run: `mvn test` → PASS

- [ ] **Step 5: 文档回写并提交**

README 在「UDF 注册表（策略服务）」章节后追加：

```markdown
## 策略服务管理面（REST）

实例与策略的管理全流程已可通过 REST 编排：`POST /api/instances`（带表列
资源）→ `POST /api/instances/{i}/udfs`（注册脱敏函数签名）→ `POST
/api/instances/{i}/policies`（datamask/row_filter，`subjects` 声明
users/groups 主体，`*` 为全体）→ `GET /api/effective/{i}?user=&groups=`
按主体拉取编译后的生效配置（无参数=匿名主体，仅命中 `*` 策略）。表列
资源可从元数据服务一键导入：`POST /api/instances/{i}/import-metadata`。
同表同类型策略的重叠校验按主体相交放宽——不同人群可各配各的脱敏列与
行过滤。

鉴权：环境变量 `SQLMASK_ADMIN_API_KEY`（管 `/api/instances/**`）与
`SQLMASK_DATA_API_KEY`（管 `/api/effective/**`）配置后强制
`X-Api-Key` 校验（401），未配置则放行（本地开发）；存量策略自动等价
`{"users":["*"]}` 全体生效。
```

spec 状态行改为：`状态：已实现（见 docs/superpowers/plans/2026-09-16-policy-admin-rest.md）`。

```bash
git add mask-core/src/main/java/io/sqlmask/server/PolicyApiKeyFilter.java \
        mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
        mask-core/src/test/java/io/sqlmask/server/PolicyApiKeyFilterTest.java \
        README.md docs/superpowers/specs/2026-09-16-policy-admin-rest-design.md
git commit -m "feat(server): 管理面/数据面 API Key 鉴权与文档回写"
```
