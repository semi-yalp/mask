# 策略优先级统一模型实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为策略服务（`io.sqlmask.policyserver`）引入 `priority` 字段：掩码轴按 priority 降序首命中唯一生效（仅同 priority 重叠才在写入期拒绝），行过滤轴移除按表互斥、编译期 AND 组合；wire 协议 / core 客户端 / mask-policy PDP 零改动。

**Architecture:** 消解发生在 policy-server 编译期（`EffectiveConfigCompiler` 已有"先到先得"列去重，按 priority 排序后首命中即最高优先级）；`EffectiveConfigResponse` 仍是预消解的扁平产物，协议不变。写入期 `PolicyValidator` 的重叠拒绝加"同 priority"前置条件；row_filter 互斥移除，由编译期 AND 组合兜底（fail-closed）。

**Tech Stack:** Java 17（record）、Spring Boot（MockMvc 端点测试）、JUnit 5、Zonky 嵌入式 PostgreSQL（`JdbcPolicyStoreTest`）、Maven 多模块。

**Spec:** `docs/superpowers/specs/2026-09-17-policy-priority-design.md`（统一决策规则见 spec §2；本计划实现其 §3–§5、§7、§8）

## Global Constraints

- wire 协议 `EffectiveConfigResponse` 结构**不得改动**（spec §6）。
- `mask-policy` 模块（PDP）**不得改动**；其现有测试必须全部保持通过（spec §8 回归证据）。
- 错误语义延续现状：`SqlMaskException` + `Code.CONFIG_ERROR`（HTTP 400），不新增状态码。
- `priority` 为 int，缺省 0，无范围限制；JSON 字段名 `priority`，可缺省。
- 管理面继续拒绝 glob（`PolicyValidator.requireGlobFree` 不动）。
- 管理 API 对外行为除 priority 新字段外逐字节不变（extraction spec 迁移原则）。
- 单模块测试命令统一用：`mvn -q -pl mask-core -am test -Dtest=<ClassName> -DfailIfNoTests=false`（在仓库根 `C:\Users\yhh\orca\mask` 执行）。

---

### Task 1: `PolicyEntity` 增加 `priority` 字段

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/model/PolicyEntity.java`
- Create: `mask-core/src/test/java/io/sqlmask/policyserver/model/PolicyEntityTest.java`

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces: `PolicyEntity` 规范构造器变为 9 参 `(String name, PolicyType policyType, boolean enabled, Integer priority, ResourceSelector resource, SubjectSelector subjects, String udf, List<Object> arguments, String filterExpr)`；访问器 `int priority()`；null 归一化为 0；保留旧 8 参（带 subjects）与 7 参（不带 subjects）便捷构造器，priority 默认 0。后续所有任务依赖 `priority()` 访问器。

- [ ] **Step 1: 写失败测试**

创建 `mask-core/src/test/java/io/sqlmask/policyserver/model/PolicyEntityTest.java`：

```java
package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEntityTest {

  private static PolicyEntity canonical(Integer priority) {
    return new PolicyEntity("p", PolicyType.DATAMASK, true, priority,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void nullPriorityNormalizesToZero() {
    assertEquals(0, canonical(null).priority());
  }

  @Test
  void explicitPriorityIsKept() {
    assertEquals(5, canonical(5).priority());
  }

  @Test
  void legacyArityConstructorsDefaultToZero() {
    assertEquals(0, new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4),
        null).priority());
    assertEquals(0, new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null).priority());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyEntityTest -DfailIfNoTests=false`
Expected: 编译失败（`PolicyEntity` 构造器参数不匹配 / 无 `priority`）。

- [ ] **Step 3: 最小实现**

修改 `PolicyEntity.java` 为（完整文件内容）：

```java
package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;

import java.util.List;

/**
 * A stored policy: kind, enablement, priority, subject selector, target
 * selector and the kind-specific payload — {@code udf} + {@code arguments}
 * for datamask, {@code filterExpr} for row filters. Higher priority wins
 * when two enabled datamask policies overlap; row filters compose with AND
 * regardless of priority.
 */
public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    Integer priority, ResourceSelector resource, SubjectSelector subjects, String udf,
    List<Object> arguments, String filterExpr) {

  public PolicyEntity {
    priority = priority == null ? 0 : priority;
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    subjects = subjects == null
        ? new SubjectSelector(java.util.Set.of("*"), java.util.Set.of())
        : subjects;
  }

  /** Legacy-arity constructor (pre-priority call sites): priority defaults to 0. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, SubjectSelector subjects, String udf,
      List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, 0, resource, subjects, udf, arguments, filterExpr);
  }

  /** Convenience constructor without subjects: the policy applies to everyone. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, 0, resource, null, udf, arguments, filterExpr);
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyEntityTest -DfailIfNoTests=false`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 回归 mask-core 既有测试（legacy 构造器兼容性）**

Run: `mvn -q -pl mask-core -am test -Dtest='PolicyValidatorTest,EffectiveConfigCompilerTest,PolicyServiceTest' -DfailIfNoTests=false`
Expected: PASS（旧 8 参/7 参构造器委托，既有调用点不受影响）。

- [ ] **Step 6: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/model/PolicyEntity.java \
  mask-core/src/test/java/io/sqlmask/policyserver/model/PolicyEntityTest.java
git commit -m "feat(policy): PolicyEntity 增加 priority 字段（缺省 0，legacy 构造器保留）"
```

---

### Task 2: `PolicyValidator` 仅同 priority 拒绝 datamask 重叠；row_filter 互斥移除

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`（`validatePolicy` 中的重叠循环，约 118–134 行）
- Modify: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`

**Interfaces:**
- Consumes: Task 1 的 `PolicyEntity.priority()`（int）。
- Produces: 校验语义——datamask 仅当 `列相交 && subjectsMayOverlap && priority 相等` 时抛 `SqlMaskException(CONFIG_ERROR)`，错误信息含双方策略名与双方 priority 值及提示"use a different priority or disable one of them first"；row_filter 不再因重叠抛错（表达式白名单校验等其余规则不变）。

- [ ] **Step 1: 改写受影响的既有测试 + 写新失败测试**

在 `PolicyValidatorTest.java` 中：

(a) 用下面两个方法**整体替换** `rejectsRowFilterOverlapAndUnknownTable` 方法（原方法断言 rf2 与 rf 重叠抛错——该行为已按 spec §4 移除）：

```java
  private PolicyEntity rowFilter(String name, String expr) {
    return new PolicyEntity(name, PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(), expr);
  }

  @Test
  void allowsRowFilterOverlap() {
    PolicyEntity rf = rowFilter("rf", "status = 'active'");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, UDFS, rf, List.of()));
    PolicyEntity rf2 = rowFilter("rf2", "id > 0");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, UDFS, rf2, List.of(rf)));
  }
```

(b) 在类中新增三个测试（放在 `rejectsOverlapWithEnabledPolicy` 之后）：

```java
  @Test
  void rejectsSamePriorityOverlapWithPrioritiesInMessage() {
    PolicyEntity existing = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity overlapping = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
    assertTrue(e.getMessage().contains("a_mask") && e.getMessage().contains("b_mask"));
    assertTrue(e.getMessage().contains("priority 0"));
  }

  @Test
  void allowsOverlapWithDifferentPriority() {
    PolicyEntity existing = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 10,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity overlapping = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
  }

  @Test
  void allowsSamePriorityWhenSubjectsDisjoint() {
    PolicyEntity aliceOnly = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity bobOnly = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("bob"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, bobOnly, List.of(aliceOnly)));
  }
```

（三个新测试均用 9 参规范构造器显式给 priority——不存在"8 参带 priority"的重载。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyValidatorTest -DfailIfNoTests=false`
Expected: FAIL——`allowsRowFilterOverlap`、`allowsOverlapWithDifferentPriority`、`allowsSamePriorityWhenSubjectsDisjoint` 失败（现状一律拒绝）；`rejectsSamePriorityOverlapWithPrioritiesInMessage` 失败（信息不含 "priority 0"）。

- [ ] **Step 3: 最小实现**

修改 `PolicyValidator.java` 重叠循环（保留方法内其余部分不动），将：

```java
      boolean overlap = (policy.policyType() == PolicyType.ROW_FILTER
          || intersects(other.resource().columns(), policy.resource().columns()))
          && subjectsMayOverlap(other.subjects(), policy.subjects());
      if (overlap) {
        throw error("policy '" + policy.name() + "' overlaps enabled policy '" + other.name()
            + "' on table '" + tableKey(policy.resource()) + "'; disable one of them first");
      }
```

替换为：

```java
      // Overlap rejection is datamask-only now: row filters compose with AND at
      // compile time, and different-priority datamask overlap resolves by
      // priority (spec 2026-09-17-policy-priority-design §4).
      boolean overlap = policy.policyType() == PolicyType.DATAMASK
          && other.priority() == policy.priority()
          && intersects(other.resource().columns(), policy.resource().columns())
          && subjectsMayOverlap(other.subjects(), policy.subjects());
      if (overlap) {
        throw error("policy '" + policy.name() + "' (priority " + policy.priority()
            + ") overlaps enabled policy '" + other.name() + "' (priority "
            + other.priority() + ") on table '" + tableKey(policy.resource())
            + "'; use a different priority or disable one of them first");
      }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyValidatorTest -DfailIfNoTests=false`
Expected: PASS（含既有 `rejectsOverlapWithEnabledPolicy`——默认 priority 0 同级仍拒绝；`PolicyValidatorGuardsTest` 一并通过）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java \
  mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java
git commit -m "feat(policy): 重叠拒绝收紧为同 priority（datamask），row_filter 互斥移除"
```

---

### Task 3: `EffectiveConfigCompiler` priority 排序 + row_filter AND 组合

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java`
- Modify: `mask-core/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `PolicyEntity.priority()`。
- Produces: 编译语义——`compile(instance, policies, subject)` 中 enabled 主体命中集按 `priority 降序、name 升序` 处理；同列多策略时绑定归属最高 priority 者且 `config().policies()` 中出现其 UDF；多条 row_filter 按 same 顺序组合为 `(f1) AND (f2)`，单条保持原文（不加括号），零条为 null。返回类型不变。

- [ ] **Step 1: 写失败测试**

在 `EffectiveConfigCompilerTest.java` 追加（import 需补 `java.util.Comparator` 不需要；需 `SubjectSelector`、`Set` 已有）：

```java
  @Test
  void highestPriorityPolicyWinsColumnOwnership() {
    List<PolicyEntity> policies = List.of(
        new PolicyEntity("low_mask", PolicyType.DATAMASK, true, 0,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            new SubjectSelector(Set.of("*"), Set.of()), "mask_low", List.of(), null),
        new PolicyEntity("high_mask", PolicyType.DATAMASK, true, 10,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            new SubjectSelector(Set.of("*"), Set.of()), "mask_high", List.of(3), null));
    EffectiveConfigResponse response =
        EffectiveConfigCompiler.compile(INSTANCE, policies, Subject.of("alice", List.of()));
    assertEquals(List.of(new EffectiveConfigResponse.ColumnBinding(
            "crm", "public", "customer", "phone", "high_mask")),
        response.config().columns());
    assertEquals(new EffectiveConfigResponse.UdfDefinition("mask_high", List.of(3)),
        response.config().policies().get("high_mask"));
  }

  @Test
  void composesMultipleRowFiltersHighestPriorityFirstNameTiebreak() {
    PolicyEntity high = new PolicyEntity("rf_z", PolicyType.ROW_FILTER, true, 10,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "id > 0");
    PolicyEntity low = new PolicyEntity("rf_a", PolicyType.ROW_FILTER, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "status = 'active'");
    PolicyEntity tie = new PolicyEntity("rf_b", PolicyType.ROW_FILTER, true, 10,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "region = 'cn'");
    var table = EffectiveConfigCompiler
        .compile(INSTANCE, List.of(low, tie, high), Subject.of("alice", List.of()))
        .config().metadata().tables().get(0);
    // 同 priority(10)平局按 name 升序:rf_b 在 rf_z 之前;rf_a(priority 0)最后
    assertEquals("(region = 'cn') AND (id > 0) AND (status = 'active')", table.rowFilter());
  }

  @Test
  void singleRowFilterStaysVerbatim() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "status = 'active'");
    var table = EffectiveConfigCompiler
        .compile(INSTANCE, List.of(rf), Subject.of("alice", List.of()))
        .config().metadata().tables().get(0);
    assertEquals("status = 'active'", table.rowFilter());
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=EffectiveConfigCompilerTest -DfailIfNoTests=false`
Expected: FAIL——列归属断言失败（现状取存储列表序先到先得）；组合测试断言失败（现状抛 CONFIG_ERROR）。

- [ ] **Step 3: 最小实现**

修改 `EffectiveConfigCompiler.compile`：

(a) `enabled` 列表排序（在 `int disabled = ...` 之前插入 `.sorted(...)`），并补 import `java.util.Comparator`：

```java
    List<PolicyEntity> enabled = policies.stream()
        .filter(PolicyEntity::enabled)
        .filter(p -> p.subjects().matchLevel(subject) > 0)
        .sorted(Comparator.comparingInt(PolicyEntity::priority).reversed()
            .thenComparing(PolicyEntity::name))
        .toList();
```

(b) 表循环内的 row_filter 分支由"第二条即抛"改为收集。将：

```java
      String rowFilter = null;
      String rowFilterPolicy = null;
      Set<String> emittedColumns = new HashSet<>();
      for (PolicyEntity policy : enabled) {
        if (!targets(policy.resource(), table)) {
          continue;
        }
        if (policy.policyType() == PolicyType.ROW_FILTER) {
          if (rowFilter != null) {
            throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "table '"
                + tableKey(table) + "': multiple enabled row_filter policies ('"
                + rowFilterPolicy + "', '" + policy.name() + "')");
          }
          rowFilter = policy.filterExpr();
          rowFilterPolicy = policy.name();
        } else {
```

替换为：

```java
      List<String> filterParts = new ArrayList<>();
      Set<String> emittedColumns = new HashSet<>();
      for (PolicyEntity policy : enabled) {
        if (!targets(policy.resource(), table)) {
          continue;
        }
        if (policy.policyType() == PolicyType.ROW_FILTER) {
          filterParts.add(policy.filterExpr());
        } else {
```

(c) 表循环结束后、构造 `TablePayload` 之前组合（保持单条原文逐字节不变）：

```java
      String rowFilter = null;
      if (filterParts.size() == 1) {
        rowFilter = filterParts.get(0);
      } else if (filterParts.size() > 1) {
        rowFilter = filterParts.stream().map(f -> "(" + f + ")")
            .reduce((a, b) -> a + " AND " + b).orElseThrow();
      }
```

并将 `tables.add(new EffectiveConfigResponse.TablePayload(...))` 保持引用 `rowFilter` 局部变量不变。(c) 之后**必须删除**两类残留——`import io.sqlmask.error.SqlMaskException;`（第 4 行）与私有方法 `tableKey(TableDef)`（第 102 行附近）：二者在该文件中仅被被移除的 throw 使用，已无引用。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=EffectiveConfigCompilerTest -DfailIfNoTests=false`
Expected: PASS（含既有用例——单条 row_filter 输出 `"status = 'active'"` 不变）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java \
  mask-core/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java
git commit -m "feat(policy): 编译期 priority 消解（列绑定高者胜）与 row_filter AND 组合"
```

---

### Task 4: 管理面 REST `priority` 往返

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java`（`PolicyDto` record、`toModel`、`toDto`）
- Modify: `mask-core/src/test/java/io/sqlmask/server/PolicyAdminEndpointTest.java`

**Interfaces:**
- Consumes: Task 1 的 9 参规范构造器与 `priority()`。
- Produces: `PolicyDto(String name, String policyType, boolean isEnabled, Integer priority, ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments, String filterExpr)`——请求可缺省 `priority`（null → 实体归一化 0），响应回显 int。`toModel`/`toDto` 签名不变。

- [ ] **Step 1: 写失败测试**

在 `PolicyAdminEndpointTest.java` 追加（复用已有 `INSTANCE_BODY`、`@BeforeEach` 清理与 `mask_phone` UDF 注册模式）：

```java
  @Test
  void policyPriorityRoundTrips() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    String withPriority = """
        {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
         "priority": 7,
         "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                      "columns": ["phone"]},
         "subjects": {"users": ["alice"], "groups": []},
         "udf": "mask_phone", "arguments": [3, 4]}
        """;
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(withPriority))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(7));
    mvc.perform(get("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(7));
  }

  @Test
  void omittedPriorityDefaultsToZero() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(0));
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyAdminEndpointTest -DfailIfNoTests=false`
Expected: FAIL——响应无 `priority` 字段（jsonPath 不存在）。

- [ ] **Step 3: 最小实现**

`PolicyAdminController.java` 三处改动：

(a) `PolicyDto` 增加 `Integer priority`（置于 `isEnabled` 之后）：

```java
  public record PolicyDto(String name, String policyType, boolean isEnabled, Integer priority,
      ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments,
      String filterExpr) {
  }
```

(b) `toModel` 中构造改为 9 参规范构造器（`dto.priority()` 原样传入，null 由实体归一化）：

```java
    return new PolicyEntity(dto.name(), parseType(dto.policyType()), dto.isEnabled(),
        dto.priority(),
        new ResourceSelector(dto.resource().catalog(), dto.resource().schema(),
            dto.resource().table(), dto.resource().columns() == null
                ? List.of() : dto.resource().columns()),
        subjects, dto.udf(), dto.arguments(), dto.filterExpr());
```

(c) `toDto` 回显：

```java
  private static PolicyDto toDto(PolicyEntity p) {
    return new PolicyDto(p.name(), p.policyType().name().toLowerCase(Locale.ROOT),
        p.enabled(), p.priority(),
        new ResourceDto(p.resource().catalog(), p.resource().schema(),
            p.resource().table(), p.resource().columns()),
        new SubjectDto(p.subjects().users(), p.subjects().groups()),
        p.udf(), p.arguments(), p.filterExpr());
  }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyAdminEndpointTest -DfailIfNoTests=false`
Expected: PASS（既有用例不受影响——旧断言不涉及 priority 字段序）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java \
  mask-core/src/test/java/io/sqlmask/server/PolicyAdminEndpointTest.java
git commit -m "feat(policy): 管理面策略 API 增加 priority 字段（缺省 0，回显）"
```

---

### Task 5: 持久化——schema.sql 与 `JdbcPolicyStore` 携带 priority

**Files:**
- Modify: `mask-core/src/main/resources/schema.sql`（`policy` 表）
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java`（`insertPolicy`、`updatePolicy`、`mapPolicy`、`selectPolicySql`）
- Modify: `mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreTest.java`
- Modify: `mask-core/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java`
- （`InMemoryPolicyStore` 主代码零改动——实体原样存储，仅补回归测试）

**Interfaces:**
- Consumes: Task 1 的 `priority()` / 9 参构造器。
- Produces: `policy` 表列 `priority INT NOT NULL DEFAULT 0`；`JdbcPolicyStore` 读写该列；两个 store 的 round-trip 均保留 priority。

- [ ] **Step 1: 写失败测试**

(a) `JdbcPolicyStoreTest.java` 追加（复用既有 `store` / `instanceName()` / `instance()` 助手）：

```java
  @Test
  void policyPriorityRoundTripsThroughJdbc() {
    store.createInstance(instance());
    PolicyEntity withPriority = new PolicyEntity("mask_p7", PolicyType.DATAMASK, true, 7,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new io.sqlmask.policy.model.SubjectSelector(java.util.Set.of("*"), java.util.Set.of()),
        "mask_phone", List.of(3, 4), null);
    store.createPolicy(instanceName(), withPriority);
    assertEquals(7, store.findPolicy(instanceName(), "mask_p7").orElseThrow().priority());

    store.updatePolicy(instanceName(), "mask_p7",
        new PolicyEntity("mask_p7", PolicyType.DATAMASK, true, 3,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            new io.sqlmask.policy.model.SubjectSelector(java.util.Set.of("*"), java.util.Set.of()),
            "mask_phone", List.of(3, 4), null));
    assertEquals(3, store.findPolicy(instanceName(), "mask_p7").orElseThrow().priority());
  }
```

（用全限定 `SubjectSelector` 免改 import 区；9 参规范构造器显式给 priority——不存在"8 参带 priority"的重载。）

(b) `InMemoryPolicyStoreTest.java` 追加（沿用该文件既有 `store` 字段、`INSTANCE` 常量与 `findPolicy` 访问模式）：

```java
  @Test
  void policyPrioritySurvivesInMemoryRoundTrip() {
    store.createInstance(INSTANCE);
    store.createPolicy("pg_prod", new PolicyEntity("p7",
        PolicyType.DATAMASK, true, 7,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    assertEquals(7, store.findPolicy("pg_prod", "p7").orElseThrow().priority());
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest='JdbcPolicyStoreTest,InMemoryPolicyStoreTest' -DfailIfNoTests=false`
Expected: `JdbcPolicyStoreTest` FAIL（JDBC 往返丢 priority——select 不含该列）；`InMemoryPolicyStoreTest` 可能直接 PASS（内存原样存实体，属回归守卫）。

- [ ] **Step 3: 最小实现**

(a) `schema.sql` 的 `policy` 表（紧跟 `subjects JSONB,` 之后加列与存量库升级注释，风格对齐 subjects 列）：

```sql
  -- 存量库升级：ALTER TABLE policy ADD COLUMN priority INT NOT NULL DEFAULT 0;
  priority INT NOT NULL DEFAULT 0,
```

(b) `JdbcPolicyStore.java` 四处：

`insertPolicy`（列清单加 `priority`）：

```java
    jdbc.update("INSERT INTO policy (instance_id, name, policy_type, is_enabled, priority,"
            + " udf, arguments, filter_expr, resource, subjects)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb)",
        ps -> {
          ps.setLong(1, instanceId);
          ps.setString(2, policy.name());
          ps.setString(3, policy.policyType().name());
          ps.setBoolean(4, policy.enabled());
          ps.setInt(5, policy.priority());
          setNullableString(ps, 6, policy.udf());
          setNullableJson(ps, 7, policy.arguments());
          setNullableString(ps, 8, policy.filterExpr());
          ps.setString(9, toJson(policy.resource()));
          ps.setString(10, toJson(policy.subjects()));
        });
```

`updatePolicy`（SET 子句加 `priority = ?`，参数序随之后移一位，原 8 参变 9 参）：

```java
    int updated = jdbc.update("UPDATE policy SET policy_type = ?, is_enabled = ?, priority = ?,"
            + " udf = ?, arguments = ?::jsonb, filter_expr = ?, resource = ?::jsonb,"
            + " subjects = ?::jsonb, updated_at = now()"
            + " WHERE instance_id = ? AND name = ?",
```

参数绑定按序：`policy_type`, `is_enabled`, `priority`(setInt), `udf`, `arguments`, `filter_expr`, `resource`, `subjects`, `instance_id`, `policyName`。

`mapPolicy`：

```java
  private PolicyEntity mapPolicy(ResultSet rs) throws SQLException {
    return new PolicyEntity(
        rs.getString("name"),
        PolicyType.valueOf(rs.getString("policy_type")),
        rs.getBoolean("is_enabled"),
        rs.getInt("priority"),
        resourceFrom(rs.getString("resource")),
        subjectFrom(rs.getString("subjects")),
        rs.getString("udf"),
        argumentsFrom(rs.getString("arguments")),
        rs.getString("filter_expr"));
  }
```

`selectPolicySql`：

```java
  private String selectPolicySql() {
    return "SELECT name, policy_type, is_enabled, priority, udf, arguments, filter_expr,"
        + " resource, subjects FROM policy";
  }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest='JdbcPolicyStoreTest,InMemoryPolicyStoreTest' -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/resources/schema.sql \
  mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java \
  mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreTest.java \
  mask-core/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java
git commit -m "feat(policy): policy 表与 JDBC store 携带 priority（默认 0，含存量库升级注释）"
```

---

### Task 6: 全量回归（PDP 零改动证据 + 服务端全绿）

**Files:**
- 无新增/修改——纯验证任务。

**Interfaces:**
- Consumes: Task 1–5 全部产物。
- Produces: 全仓测试通过记录（spec §8 的"PDP 回归：mask-policy 现有测试全部保持通过"证据）。

- [ ] **Step 1: 全仓测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，所有模块（mask-policy、mask-core、mask-audit、mask-metadata、mask-lite）测试全绿。特别确认 mask-policy 的 `PolicyEngineTest`、`PolicyIndexTest`、`PolicyModelTest` 未有任何改动且通过（`git status` 干净、`git log --oneline -- mask-policy` 无新提交）。

- [ ] **Step 2: spec 对照自查**

对照 `docs/superpowers/specs/2026-09-17-policy-priority-design.md` §8 测试清单逐项核对：校验器四场景、编译器四场景、REST 往返/缺省、存储两 store、PDP 回归——均可在本次与此前各任务的测试运行输出中指认。

- [ ] **Step 3: 无提交（工作区应干净）**

Run: `git status --short`
Expected: 无输出（或仅与本计划无关的既有未跟踪文件）。
