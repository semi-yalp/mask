# 策略子系统（mask-policy）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把策略功能独立为 `mask-policy` Maven 模块（Ranger 式策略模型 + PDP 决策引擎 + 策略 YAML + 校验 REST），引入 `user`/`groups` 主体维度与资源通配/优先级，改写引擎（mask-core）作为执行点通过决策接口取结果；旧 metadata.yaml 行为逐字节不变。

**Architecture:** `mask-policy`（零 mask-core 依赖：model / match / store 三层 + spring-web 校验端点）← `mask-core`（PEP 适配：`LegacyPolicyAdapter` 把旧配置转为策略集、`PdpMaskSelector` 查掩码、`RowFilterRegistry.buildFromPolicies` 查行过滤）。掩码决策每输出列唯一，行过滤决策按命中项 AND 叠加。策略来源二选一：旧 metadata 内嵌策略 或 新 policies.yaml，同时非空显式报错。

**Tech Stack:** Java 17、Maven 多模块（`sql-mask-parent` → `mask-core` / `mask-policy`）、snakeyaml、Apache Calcite（仅 mask-core）、Spring Boot 3.3、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-06-policy-subsystem-design.md`（本计划从 spec 论证，两者一起阅读）。

**基线:** 分支 `feature/policy`，提交 `ae2157c`，`mvn test` 300 个测试全绿。注意：本仓库存在并行开发会话，执行前先 `git log --oneline -3` 确认基线未漂移；若 `mask-core/src/main/java/io/sqlmask/config/source/`、`PolicyRegistry`、`LoadedConfig` 已被进一步改动，先重读本计划引用的这些文件再动手。

## Global Constraints

- 依赖方向固定：`mask-core` → `mask-policy`；`mask-policy` 的 pom **不得**出现 `io.sqlmask:mask-core` 依赖，也不得 import 任何 `io.sqlmask.metadata` / `io.sqlmask.error` / Calcite / picocli 类。
- `mask-policy` 的全部错误都是 `PolicyException`（无错误码字段）；`mask-core` 适配为 `SqlMaskException(CONFIG_ERROR)`，消息原样透传，不加前缀。
- 标识符规范化两侧一致：`PolicyNames.normalize(s, part)` ≡ `ColumnKey.normalize(s, part)` = `s.toLowerCase(Locale.ROOT)` + 非空校验；用户/组名不折叠、大小写敏感；`*` 是资源级与主体选择器中唯一的通配符。
- 主体特异性：user 精确(3) > group 精确(2) > `*`(1) > 不匹配(0)；匿名主体（无 user 无 groups）只被 `*` 命中。策略间决策顺序：`enabled` 过滤 → `priority` 降序 → 声明顺序（稳定排序）。
- 掩码：一个输出列一个主体最多一条指令；多来源输出列在**有命中的来源列**中取规范化列键字典序最小（`ColumnKey.ORDER`）。行过滤：命中全部叠加为 AND（按决策顺序），注入语义沿用行过滤 v2（先内层、每引用位置一份、文本重建无子树共享）。
- 旧路径逐字节不变：只给 `--metadata`（或 `metadataYaml`）时，输出与错误消息（含行过滤错误的前缀 `table 'X': row filter`）与基线完全一致；`GoldenOutputTest` 等既有测试不改断言。
- 新旧策略来源互斥：`policyYaml` 非空时 metadata 的 `policies` / `columns` / `rowFilter` 必须全空，否则 `CONFIG_ERROR`。
- 构建与验证命令一律在仓库根目录：`mvn test`（全量回归）、`mvn -q -DskipTests compile`（快速编译）。
- 提交信息：conventional commits 中文，与仓库现有风格一致（`feat: ...` / `test: ...` / `docs: ...`）。
- 主体现有携带方式：CLI 复用 `--user`（pull 模式=数据库用户，改写模式=查询主体）+ 新增 `--groups`（仅改写模式）；REST `/api/rewrite` 请求体加 `policyYaml` / `user` / `groups`。

---

### Task 0: 固化基线

**Files:**
- 无代码改动。

**Interfaces:**
- Consumes: 分支 `feature/policy` 当前状态（提交 `ae2157c`，工作区除 `untitled.md` 外干净）。
- Produces: 一个全部测试通过的起点；后续任务失败时可 `git diff` 定位回归。

- [ ] **Step 1: 确认基线状态**

Run: `git log --oneline -3 && git status --short`
Expected: 最新提交包含 `ae2157c feat(core): 编译契约 DTO 与 ConfigSource 抽象...`（或其后继）；工作区无未提交的源码改动（`untitled.md` 可忽略，不要提交、不要删除）。

- [ ] **Step 2: 全量测试确认绿**

Run: `mvn test`
Expected: `BUILD SUCCESS`，`Tests run: 300, Failures: 0, Errors: 0`。若不是 300，记录实际数字——它就是后续每个任务"全量回归"的对照值（并行会话可能已加测试，以执行时实际值 + 0 失败为准）。

---

### Task 1: 依赖方向修正（mask-policy 不依赖 mask-core）

**Files:**
- Modify: `mask-policy/pom.xml`
- Modify: `mask-core/pom.xml`

**Interfaces:**
- Produces: `mask-policy` 可独立编译（仅 snakeyaml + spring-web + 测试依赖）；`mask-core` 编译类路径上可见 `mask-policy` 的全部类（Task 2 起可用）。

- [ ] **Step 1: 改 mask-policy/pom.xml 的 dependencies**

删除 `io.sqlmask:mask-core` 依赖，加入 snakeyaml（其余保留）：

```xml
  <dependencies>
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
      <version>${snakeyaml.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
```

- [ ] **Step 2: 改 mask-core/pom.xml，加 mask-policy 依赖**

在 `<dependencies>` 首位加入：

```xml
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-policy</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 3: 编译验证双向**

Run: `mvn -q -DskipTests compile`
Expected: EXIT=0（reactor 按 core→policy 排序构建）。

- [ ] **Step 4: Commit**

```bash
git add mask-policy/pom.xml mask-core/pom.xml
git commit -m "build: 依赖方向修正 mask-core→mask-policy，策略模块零 core 依赖"
```

---

### Task 2: mask-policy 模型层

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policy/PolicyException.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/PolicyNames.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/Subject.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/SubjectSelector.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/PolicyResource.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/DataMaskItem.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/RowFilterItem.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/PolicyType.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/Policy.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/MaskInstruction.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/model/RowFilterHit.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/model/PolicyModelTest.java`

**Interfaces:**
- Produces（后续任务全部依赖这些签名，逐字使用）:
  - `PolicyException(String message)` / `PolicyException(String message, Throwable cause)`
  - `PolicyNames.normalize(String, String partName) -> String`
  - `record Subject(String user, List<String> groups)`；`Subject.anonymous()`；`Subject.of(String user, List<String> groups)`
  - `record SubjectSelector(Set<String> users, Set<String> groups)`；`int matchLevel(Subject)`
  - `record PolicyResource(String catalog, String schema, String table, String column)`（column 可 null）；静态 `table(catalog, schema, table)` / `column(catalog, schema, table, column)`
  - `record DataMaskItem(SubjectSelector selector, String udf, List<Object> arguments)`
  - `record RowFilterItem(SubjectSelector selector, String filterExpr)`
  - `enum PolicyType { DATA_MASK, ROW_FILTER }`
  - `record Policy(String name, boolean enabled, int priority, PolicyType type, List<PolicyResource> resources, List<DataMaskItem> dataMaskItems, List<RowFilterItem> rowFilterItems)`
  - `record MaskInstruction(String policyName, String udf, List<Object> arguments)`
  - `record RowFilterHit(String policyName, String expr)`

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyModelTest {

  private static final SubjectSelector EVERYONE =
      new SubjectSelector(Set.of(), Set.of("*"));

  @Test
  void subjectOfNormalizesUserAndGroups() {
    Subject subject = Subject.of(" Alice ", List.of("  devs", "devs", "", null));
    assertEquals("Alice", subject.user());
    assertEquals(List.of("devs"), subject.groups());
  }

  @Test
  void anonymousSubjectMatchesOnlyWildcard() {
    Subject anonymous = Subject.anonymous();
    assertEquals(1, new SubjectSelector(Set.of(), Set.of("*")).matchLevel(anonymous));
    assertEquals(0, new SubjectSelector(Set.of("alice"), Set.of()).matchLevel(anonymous));
    assertEquals(0, new SubjectSelector(Set.of(), Set.of("devs")).matchLevel(anonymous));
  }

  @Test
  void selectorSpecificityUserBeatsGroupBeatsWildcard() {
    Subject alice = Subject.of("alice", List.of("devs"));
    assertEquals(3, new SubjectSelector(Set.of("alice", "bob"), Set.of("devs")).matchLevel(alice));
    assertEquals(2, new SubjectSelector(Set.of(), Set.of("devs", "ops")).matchLevel(alice));
    assertEquals(1, new SubjectSelector(Set.of(), Set.of("*")).matchLevel(alice));
    assertEquals(0, new SubjectSelector(Set.of(), Set.of("ops")).matchLevel(alice));
  }

  @Test
  void emptySelectorIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new SubjectSelector(Set.of(), Set.of()));
    assertTrue(e.getMessage().contains("non-empty users or groups"));
  }

  @Test
  void resourceLevelsAreNormalizedAndColumnMayBeNull() {
    PolicyResource column = PolicyResource.column("CRM", "Public", "Customer", "Phone");
    assertEquals("crm", column.catalog());
    assertEquals("public", column.schema());
    assertEquals("customer", column.table());
    assertEquals("phone", column.column());
    assertEquals(null, PolicyResource.table("crm", "public", "customer").column());
    assertThrows(PolicyException.class, () -> new PolicyResource("crm", "", "t", null));
  }

  @Test
  void dataMaskArgumentsMustBeScalars() {
    assertThrows(PolicyException.class,
        () -> new DataMaskItem(EVERYONE, "mask_phone", List.of(3, Map.of("a", 1))));
    assertThrows(PolicyException.class,
        () -> new DataMaskItem(EVERYONE, "mask_phone", List.of((Object) null)));
  }

  @Test
  void blankFilterExprIsRejected() {
    assertThrows(PolicyException.class, () -> new RowFilterItem(EVERYONE, "   "));
  }

  @Test
  void dataMaskPolicyRequiresColumnLevelAndOnlyDataMaskItems() {
    PolicyException noColumn = assertThrows(PolicyException.class,
        () -> new Policy("m", true, 0, PolicyType.DATA_MASK,
            List.of(PolicyResource.table("crm", "public", "customer")),
            List.of(new DataMaskItem(EVERYONE, "mask_phone", List.of())), List.of()));
    assertTrue(noColumn.getMessage().contains("column level"));

    PolicyException mixed = assertThrows(PolicyException.class,
        () -> new Policy("m", true, 0, PolicyType.DATA_MASK,
            List.of(PolicyResource.column("crm", "public", "customer", "phone")),
            List.of(new DataMaskItem(EVERYONE, "mask_phone", List.of())),
            List.of(new RowFilterItem(EVERYONE, "status = 'a'"))));
    assertTrue(mixed.getMessage().contains("dataMaskItems only"));
  }

  @Test
  void rowFilterPolicyIsTableLevelAndOnlyRowFilterItems() {
    PolicyException withColumn = assertThrows(PolicyException.class,
        () -> new Policy("f", true, 0, PolicyType.ROW_FILTER,
            List.of(PolicyResource.column("crm", "public", "orders", "status")),
            List.of(),
            List.of(new RowFilterItem(EVERYONE, "status <> 'archived'"))));
    assertTrue(withColumn.getMessage().contains("table-level"));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-policy test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现模型层**

`PolicyException.java`:

```java
package io.sqlmask.policy;

/**
 * Policy subsystem error. Every policy failure is a configuration failure;
 * mask-core adapts this to SqlMaskException(CONFIG_ERROR) at the PEP boundary
 * with the message passed through verbatim.
 */
public class PolicyException extends RuntimeException {

  public PolicyException(String message) {
    super(message);
  }

  public PolicyException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

`PolicyNames.java`:

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.Locale;

/**
 * Resource identifier normalization shared by the loader and the matcher:
 * PostgreSQL unquoted-identifier convention (fold to lower case), identical
 * to mask-core's ColumnKey.normalize so both sides agree.
 */
public final class PolicyNames {

  private PolicyNames() {
  }

  public static String normalize(String identifier, String partName) {
    if (identifier == null || identifier.isBlank()) {
      throw new PolicyException(partName + " identifier must not be blank (use \"*\" for any)");
    }
    return identifier.toLowerCase(Locale.ROOT);
  }
}
```

`Subject.java`:

```java
package io.sqlmask.policy.model;

import java.util.List;

/**
 * Query subject: the acting user and their groups. A null user with no groups
 * is the anonymous subject; it matches only "*" wildcard selectors.
 */
public record Subject(String user, List<String> groups) {

  public Subject {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  public static Subject anonymous() {
    return new Subject(null, List.of());
  }

  /** Normalizes blanks away, trims and de-duplicates groups preserving order. */
  public static Subject of(String user, List<String> groups) {
    String normalizedUser = user == null || user.isBlank() ? null : user.trim();
    List<String> normalizedGroups = groups == null ? List.of() : groups.stream()
        .filter(g -> g != null && !g.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
    return new Subject(normalizedUser, normalizedGroups);
  }
}
```

`SubjectSelector.java`:

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.Set;

/**
 * Subject predicate declared by a policy item. "*" is the only wildcard.
 * Specificity: exact user (3) > exact group (2) > "*" (1) > no match (0).
 */
public record SubjectSelector(Set<String> users, Set<String> groups) {

  public SubjectSelector {
    users = users == null ? Set.of() : Set.copyOf(users);
    groups = groups == null ? Set.of() : Set.copyOf(groups);
    if (users.isEmpty() && groups.isEmpty()) {
      throw new PolicyException(
          "subject selector requires a non-empty users or groups list; use [\"*\"] for everyone");
    }
  }

  /** Highest matching specificity for {@code subject}; 0 when nothing matches. */
  public int matchLevel(Subject subject) {
    if (subject == null) {
      return 0;
    }
    int level = 0;
    if (users.contains("*") || groups.contains("*")) {
      level = 1;
    }
    if (subject.groups().stream().anyMatch(g -> !g.equals("*") && groups.contains(g))) {
      level = Math.max(level, 2);
    }
    if (subject.user() != null && users.contains(subject.user()) && !subject.user().equals("*")) {
      level = Math.max(level, 3);
    }
    return level;
  }
}
```

`PolicyResource.java`:

```java
package io.sqlmask.policy.model;

/**
 * One resource path entry. Every level is a concrete identifier or "*";
 * {@code column} is null for table-level (row filter) resources. Levels are
 * normalized on construction.
 */
public record PolicyResource(String catalog, String schema, String table, String column) {

  public PolicyResource {
    catalog = PolicyNames.normalize(catalog, "catalog");
    schema = PolicyNames.normalize(schema, "schema");
    table = PolicyNames.normalize(table, "table");
    column = column == null ? null : PolicyNames.normalize(column, "column");
  }

  public static PolicyResource table(String catalog, String schema, String table) {
    return new PolicyResource(catalog, schema, table, null);
  }

  public static PolicyResource column(String catalog, String schema, String table, String column) {
    return new PolicyResource(catalog, schema, table, column);
  }
}
```

`DataMaskItem.java`:

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.List;
import java.util.Map;

/** One dataMask policy item: who it applies to and the masking UDF call. */
public record DataMaskItem(SubjectSelector selector, String udf, List<Object> arguments) {

  public DataMaskItem {
    if (selector == null) {
      throw new PolicyException("dataMaskItem requires a subject selector (users/groups)");
    }
    if (udf == null || udf.isBlank()) {
      throw new PolicyException("dataMaskItem requires a udf identifier");
    }
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    for (Object argument : arguments) {
      if (argument == null || argument instanceof Map<?, ?> || argument instanceof List<?>) {
        throw new PolicyException("dataMaskItem udf '" + udf + "': argument '" + argument
            + "' must be a scalar (string, number or boolean)");
      }
    }
  }
}
```

`RowFilterItem.java`:

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

/** One rowFilter policy item: who it applies to and the filter expression. */
public record RowFilterItem(SubjectSelector selector, String filterExpr) {

  public RowFilterItem {
    if (selector == null) {
      throw new PolicyException("rowFilterItem requires a subject selector (users/groups)");
    }
    if (filterExpr == null || filterExpr.isBlank()) {
      throw new PolicyException("rowFilterItem requires a non-blank filterExpr");
    }
  }
}
```

`PolicyType.java`:

```java
package io.sqlmask.policy.model;

/** A policy declares items of exactly one kind. */
public enum PolicyType {
  DATA_MASK, ROW_FILTER
}
```

`Policy.java`:

```java
package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.List;

/**
 * One Ranger-style policy: resources plus items of exactly one kind
 * (dataMask or rowFilter); the items carry the subject selectors.
 */
public record Policy(String name, boolean enabled, int priority, PolicyType type,
    List<PolicyResource> resources, List<DataMaskItem> dataMaskItems,
    List<RowFilterItem> rowFilterItems) {

  public Policy {
    if (name == null || name.isBlank()) {
      throw new PolicyException("policy name must not be blank");
    }
    if (type == null) {
      throw new PolicyException("policy '" + name + "': type is required");
    }
    resources = resources == null ? List.of() : List.copyOf(resources);
    dataMaskItems = dataMaskItems == null ? List.of() : List.copyOf(dataMaskItems);
    rowFilterItems = rowFilterItems == null ? List.of() : List.copyOf(rowFilterItems);
    if (resources.isEmpty()) {
      throw new PolicyException("policy '" + name + "': at least one resource is required");
    }
    if (type == PolicyType.DATA_MASK) {
      if (dataMaskItems.isEmpty() || !rowFilterItems.isEmpty()) {
        throw new PolicyException(
            "policy '" + name + "': dataMask policies declare dataMaskItems only");
      }
      for (PolicyResource resource : resources) {
        if (resource.column() == null) {
          throw new PolicyException("policy '" + name
              + "': dataMask resources must declare a column level (use \"*\" for all columns)");
        }
      }
    } else {
      if (rowFilterItems.isEmpty() || !dataMaskItems.isEmpty()) {
        throw new PolicyException(
            "policy '" + name + "': rowFilter policies declare rowFilterItems only");
      }
      for (PolicyResource resource : resources) {
        if (resource.column() != null) {
          throw new PolicyException("policy '" + name
              + "': rowFilter resources are table-level; remove the column level");
        }
      }
    }
  }
}
```

`MaskInstruction.java`:

```java
package io.sqlmask.policy.model;

import java.util.List;

/** The masking decision for one column: which UDF to call with which arguments. */
public record MaskInstruction(String policyName, String udf, List<Object> arguments) {

  public MaskInstruction {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
```

`RowFilterHit.java`:

```java
package io.sqlmask.policy.model;

/** One matched row-filter expression, in the engine's decision order. */
public record RowFilterHit(String policyName, String expr) {
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mask-policy test`
Expected: `PolicyModelTest` 9 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add mask-policy/src
git commit -m "feat(policy): Ranger 式策略模型（Subject/资源/策略项/指令）与 PolicyException"
```

---

### Task 3: PolicyYamlLoader（policies.yaml 解析与结构校验）

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policy/store/PolicyYamlLoader.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/store/PolicyYamlLoaderTest.java`

**Interfaces:**
- Consumes: Task 2 的全部模型类型。
- Produces: `List<Policy> parse(String yaml, String sourceName)` — 路径化诊断（`policies.yaml: policies[0].dataMaskItems[1].udf: ...`），多值 `column` 列表展开为多个 `PolicyResource`，规范化由模型构造器完成。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.policy.store;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyYamlLoaderTest {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();

  @Test
  void parsesMaskAndRowFilterPolicies() {
    String yaml = """
        policies:
          - name: mask-phone
            priority: 5
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone, email]
            dataMaskItems:
              - groups: ["*"]
                udf: mask_phone
                arguments: [3, 4]
          - name: filter-archived
            resources:
              - catalog: crm
                schema: public
                table: orders
            rowFilterItems:
              - users: [alice]
                filterExpr: "status <> 'archived'"
        """;
    List<Policy> policies = loader.parse(yaml, "policies.yaml");
    assertEquals(2, policies.size());
    Policy mask = policies.get(0);
    assertEquals("mask-phone", mask.name());
    assertEquals(true, mask.enabled());
    assertEquals(5, mask.priority());
    assertEquals(PolicyType.DATA_MASK, mask.type());
    assertEquals(2, mask.resources().size()); // column 列表展开
    assertEquals("phone", mask.resources().get(0).column());
    assertEquals("email", mask.resources().get(1).column());
    assertEquals(1, mask.dataMaskItems().size());
    assertEquals(List.of(3, 4), mask.dataMaskItems().get(0).arguments());
    Policy filter = policies.get(1);
    assertEquals(PolicyType.ROW_FILTER, filter.type());
    assertEquals("status <> 'archived'", filter.rowFilterItems().get(0).filterExpr());
  }

  @Test
  void emptyPoliciesListIsLegal() {
    assertEquals(List.of(), loader.parse("policies: []", "policies.yaml"));
  }

  @Test
  void duplicatePolicyNameIsRejected() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("policies[1]: duplicate policy name 'a'"));
  }

  @Test
  void itemsAreExclusive() {
    PolicyException both = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
            rowFilterItems: [{groups: ["*"], filterExpr: "x = 1"}]
        """, "policies.yaml"));
    assertTrue(both.getMessage().contains("exactly one of dataMaskItems / rowFilterItems"));
  }

  @Test
  void emptySubjectSelectorIsRejectedWithPath() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{udf: f}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("dataMaskItems[0]: users/groups"));
  }

  @Test
  void rowFilterResourceMustNotDeclareColumn() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            rowFilterItems: [{groups: ["*"], filterExpr: "x = 1"}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("table-level"));
  }

  @Test
  void invalidYamlIsWrappedWithPathPrefix() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> loader.parse("policies: [ {", "policies.yaml"));
    assertTrue(e.getMessage().startsWith("policies.yaml: invalid YAML"));
  }

  @Test
  void missingPoliciesKeyIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> loader.parse("foo: 1", "policies.yaml"));
    assertTrue(e.getMessage().contains("'policies' must be a list"));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-policy test`
Expected: 编译失败（`PolicyYamlLoader` 不存在）。

- [ ] **Step 3: 实现 PolicyYamlLoader**

```java
package io.sqlmask.policy.store;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the policies.yaml document into validated policies. Every failure is
 * a {@link PolicyException} whose message starts with the YAML path of the
 * offending node (for example "policies.yaml: policies[0].dataMaskItems[1]").
 */
public final class PolicyYamlLoader {

  public List<Policy> parse(String yaml, String sourceName) {
    Object root;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (YAMLException e) {
      throw new PolicyException(sourceName + ": invalid YAML: " + e.getMessage(), e);
    }
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new PolicyException(sourceName + ": root must be a mapping with a 'policies' key");
    }
    Object policiesNode = rootMap.get("policies");
    if (!(policiesNode instanceof List<?> policyList)) {
      throw new PolicyException(
          sourceName + ": 'policies' must be a list (use policies: [] for none)");
    }
    List<Policy> policies = new ArrayList<>();
    Set<String> seenNames = new LinkedHashSet<>();
    for (int i = 0; i < policyList.size(); i++) {
      policies.add(loadPolicy(policyList.get(i), sourceName + ": policies[" + i + "]", seenNames));
    }
    return policies;
  }

  private Policy loadPolicy(Object node, String path, Set<String> seenNames) {
    if (!(node instanceof Map<?, ?> map)) {
      throw new PolicyException(path + " must be a mapping");
    }
    String name = requiredString(map, "name", path);
    if (!seenNames.add(name)) {
      throw new PolicyException(path + ": duplicate policy name '" + name + "'");
    }
    boolean enabled = map.get("enabled") == null
        ? true
        : requireType(map.get("enabled"), Boolean.class, path + ".enabled", "boolean");
    int priority = map.get("priority") == null
        ? 0
        : requireType(map.get("priority"), Integer.class, path + ".priority", "integer");

    Object resourcesNode = map.get("resources");
    if (!(resourcesNode instanceof List<?> resourceList) || resourceList.isEmpty()) {
      throw new PolicyException(path + ".resources must be a non-empty list");
    }
    List<PolicyResource> resources = new ArrayList<>();
    for (int i = 0; i < resourceList.size(); i++) {
      resources.addAll(loadResource(resourceList.get(i), path + ".resources[" + i + "]"));
    }

    boolean hasMask = map.containsKey("dataMaskItems");
    boolean hasFilter = map.containsKey("rowFilterItems");
    if (hasMask == hasFilter) {
      throw new PolicyException(
          path + ": exactly one of dataMaskItems / rowFilterItems is required");
    }
    if (hasMask) {
      for (PolicyResource resource : resources) {
        if (resource.column() == null) {
          throw new PolicyException(path
              + ".resources: dataMask resources must declare a column level (use \"*\" for all columns)");
        }
      }
      return new Policy(name, enabled, priority, PolicyType.DATA_MASK, resources,
          loadDataMaskItems(map.get("dataMaskItems"), path + ".dataMaskItems"), List.of());
    }
    for (PolicyResource resource : resources) {
      if (resource.column() != null) {
        throw new PolicyException(
            path + ".resources: rowFilter resources are table-level; remove the column level");
      }
    }
    return new Policy(name, enabled, priority, PolicyType.ROW_FILTER, resources, List.of(),
        loadRowFilterItems(map.get("rowFilterItems"), path + ".rowFilterItems"));
  }

  private List<PolicyResource> loadResource(Object node, String path) {
    if (!(node instanceof Map<?, ?> map)) {
      throw new PolicyException(path + " must be a mapping");
    }
    String catalog = requiredString(map, "catalog", path);
    String schema = requiredString(map, "schema", path);
    String table = requiredString(map, "table", path);
    Object columnNode = map.get("column");
    if (columnNode == null) {
      return List.of(new PolicyResource(catalog, schema, table, null));
    }
    if (columnNode instanceof String column) {
      return List.of(new PolicyResource(catalog, schema, table, column));
    }
    if (columnNode instanceof List<?> columns) {
      if (columns.isEmpty()) {
        throw new PolicyException(path + ".column must not be an empty list");
      }
      List<PolicyResource> expanded = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        if (!(columns.get(i) instanceof String column) || column.isBlank()) {
          throw new PolicyException(path + ".column[" + i + "] must be a non-blank string or \"*\"");
        }
        expanded.add(new PolicyResource(catalog, schema, table, column));
      }
      return expanded;
    }
    throw new PolicyException(path + ".column must be a string, a list of strings or \"*\"");
  }

  private List<DataMaskItem> loadDataMaskItems(Object node, String path) {
    if (!(node instanceof List<?> list) || list.isEmpty()) {
      throw new PolicyException(path + " must be a non-empty list");
    }
    List<DataMaskItem> items = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + "[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> map)) {
        throw new PolicyException(itemPath + " must be a mapping");
      }
      SubjectSelector selector = loadSelector(map, itemPath);
      String udf = requiredString(map, "udf", itemPath);
      List<Object> arguments = List.of();
      if (map.containsKey("arguments")) {
        Object argumentsNode = map.get("arguments");
        if (!(argumentsNode instanceof List<?> argumentList)) {
          throw new PolicyException(itemPath + ".arguments must be a list");
        }
        for (int j = 0; j < argumentList.size(); j++) {
          Object argument = argumentList.get(j);
          if (argument == null || argument instanceof Map<?, ?> || argument instanceof List<?>) {
            throw new PolicyException(itemPath + ".arguments[" + j
                + "] must be a scalar (string, number or boolean)");
          }
        }
        arguments = List.copyOf(argumentList);
      }
      items.add(new DataMaskItem(selector, udf, arguments));
    }
    return items;
  }

  private List<RowFilterItem> loadRowFilterItems(Object node, String path) {
    if (!(node instanceof List<?> list) || list.isEmpty()) {
      throw new PolicyException(path + " must be a non-empty list");
    }
    List<RowFilterItem> items = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + "[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> map)) {
        throw new PolicyException(itemPath + " must be a mapping");
      }
      SubjectSelector selector = loadSelector(map, itemPath);
      items.add(new RowFilterItem(selector, requiredString(map, "filterExpr", itemPath)));
    }
    return items;
  }

  private SubjectSelector loadSelector(Map<?, ?> map, String path) {
    Set<String> users = stringSet(map.get("users"), path + ".users");
    Set<String> groups = stringSet(map.get("groups"), path + ".groups");
    if (users.isEmpty() && groups.isEmpty()) {
      throw new PolicyException(
          path + ": users/groups: at least one subject is required (use [\"*\"] for everyone)");
    }
    return new SubjectSelector(users, groups);
  }

  private Set<String> stringSet(Object node, String path) {
    if (node == null) {
      return Set.of();
    }
    if (!(node instanceof List<?> list)) {
      throw new PolicyException(path + " must be a list of strings");
    }
    Set<String> values = new LinkedHashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof String s) || s.isBlank()) {
        throw new PolicyException(path + "[" + i + "] must be a non-blank string");
      }
      values.add(s);
    }
    return values;
  }

  private String requiredString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new PolicyException(path + "." + key + ": required non-blank string is missing");
    }
    return s;
  }

  private <T> T requireType(Object value, Class<T> type, String path, String label) {
    if (!type.isInstance(value)) {
      throw new PolicyException(path + " must be a " + label);
    }
    return type.cast(value);
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mask-policy test`
Expected: 全部测试通过（模型 + 加载器）。

- [ ] **Step 5: Commit**

```bash
git add mask-policy/src
git commit -m "feat(policy): policies.yaml 加载器（路径化诊断、items 互斥、主体必填）"
```

---

### Task 4: PolicyIndex + PolicyEngine（PDP 决策）

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policy/match/PolicyIndex.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/match/PolicyEngine.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/match/PolicyEngineTest.java`

**Interfaces:**
- Consumes: Task 2 模型、Task 3 无关（engine 只吃 `List<Policy>`）。
- Produces:
  - `PolicyIndex.of(List<Policy>)` — enabled 过滤 + `priority` 降序稳定排序。
  - `PolicyEngine(PolicyIndex index)`；`Optional<MaskInstruction> maskFor(String catalog, String schema, String table, String column, Subject subject)`；`List<RowFilterHit> rowFiltersFor(String catalog, String schema, String table, Subject subject)`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.policy.match;

import io.sqlmask.policy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

  private static final Subject ANON = Subject.anonymous();
  private static final Subject ALICE = Subject.of("alice", List.of("devs"));

  private static Policy maskPolicy(String name, int priority, boolean enabled, String column,
      SubjectSelector selector, String udf) {
    return new Policy(name, enabled, priority, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", column)),
        List.of(new DataMaskItem(selector, udf, List.of())), List.of());
  }

  private static Policy filterPolicy(String name, int priority, SubjectSelector selector,
      String expr) {
    return new Policy(name, enabled_(true), priority, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")),
        List.of(), List.of(new RowFilterItem(selector, expr)));
  }

  private static boolean enabled_(boolean b) {
    return b;
  }

  @Test
  void maskMatchesWildcardLevelsAndReturnsInstruction() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "*", new SubjectSelector(Set.of(), Set.of("*")), "mask_all"))));
    MaskInstruction instruction = engine.maskFor("CRM", "Public", "Customer", "phone", ALICE)
        .orElseThrow();
    assertEquals("m", instruction.policyName());
    assertEquals("mask_all", instruction.udf());
  }

  @Test
  void higherPriorityWinsAndTiesGoToDeclarationOrder() {
    Policy low = maskPolicy("low", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_low");
    Policy high = maskPolicy("high", 1, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_high");
    assertEquals("high", new PolicyEngine(PolicyIndex.of(List.of(low, high)))
        .maskFor("crm", "public", "customer", "phone", ANON).orElseThrow().policyName());
    // 同 priority：声明顺序
    Policy first = maskPolicy("first", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_first");
    Policy second = maskPolicy("second", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_second");
    assertEquals("first", new PolicyEngine(PolicyIndex.of(List.of(first, second)))
        .maskFor("crm", "public", "customer", "phone", ANON).orElseThrow().policyName());
  }

  @Test
  void disabledPoliciesAreSkipped() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(
        maskPolicy("off", 9, false, "phone", new SubjectSelector(Set.of(), Set.of("*")), "x"),
        maskPolicy("on", 0, true, "phone", new SubjectSelector(Set.of(), Set.of("*")), "y"))));
    assertEquals("on", engine.maskFor("crm", "public", "customer", "phone", ANON)
        .orElseThrow().policyName());
  }

  @Test
  void itemSpecificityUserBeatsGroupBeatsWildcard() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "phone",
        new SubjectSelector(Set.of("alice"), Set.of("devs", "ops")), "mask_user"))));
    assertEquals("mask_user", engine.maskFor("crm", "public", "customer", "phone", ALICE)
        .orElseThrow().udf());
  }

  @Test
  void noMatchYieldsEmptyMaskAndEmptyFilters() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "phone", new SubjectSelector(Set.of("bob"), Set.of()), "mask_bob"))));
    assertTrue(engine.maskFor("crm", "public", "customer", "phone", ANON).isEmpty());
    assertTrue(engine.rowFiltersFor("crm", "public", "orders", ANON).isEmpty());
  }

  @Test
  void rowFiltersAccumulateAllMatchingPoliciesInDecisionOrder() {
    Policy p1 = new Policy("f-low", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")), "a = 1")));
    Policy p2 = new Policy("f-high", true, 5, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of("alice"), Set.of()), "b = 2")));
    List<RowFilterHit> hits = new PolicyEngine(PolicyIndex.of(List.of(p1, p2)))
        .rowFiltersFor("crm", "public", "orders", ALICE);
    assertEquals(2, hits.size());
    assertEquals("f-high", hits.get(0).policyName()); // priority 高者在前
    assertEquals("f-low", hits.get(1).policyName());
  }

  @Test
  void rowFilterSkippedForSubjectWithoutMatch() {
    Policy p = new Policy("f", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of("alice"), Set.of()), "a = 1")));
    assertTrue(new PolicyEngine(PolicyIndex.of(List.of(p)))
        .rowFiltersFor("crm", "public", "orders", ANON).isEmpty());
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-policy test`
Expected: 编译失败（`PolicyIndex` / `PolicyEngine` 不存在）。

- [ ] **Step 3: 实现 PolicyIndex 与 PolicyEngine**

`PolicyIndex.java`:

```java
package io.sqlmask.policy.match;

import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic decision order over enabled policies: higher priority first,
 * ties keep declaration order (stable sort). Disabled policies are dropped.
 */
public record PolicyIndex(List<Policy> dataMasks, List<Policy> rowFilters) {

  public PolicyIndex {
    dataMasks = List.copyOf(dataMasks);
    rowFilters = List.copyOf(rowFilters);
  }

  public static PolicyIndex of(List<Policy> policies) {
    List<Policy> ordered = policies.stream()
        .filter(Policy::enabled)
        .sorted(Comparator.comparingInt(Policy::priority).reversed())
        .toList();
    return new PolicyIndex(
        ordered.stream().filter(p -> p.type() == PolicyType.DATA_MASK).toList(),
        ordered.stream().filter(p -> p.type() == PolicyType.ROW_FILTER).toList());
  }
}
```

`PolicyEngine.java`:

```java
package io.sqlmask.policy.match;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.MaskInstruction;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyNames;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.RowFilterHit;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The policy decision point (PDP): pure, stateless matching of resources and
 * subjects against the policy index. Deterministic for a given index,
 * resource and subject. Request-side names are concrete values; "*" appears
 * only on the policy declaration side.
 */
public final class PolicyEngine {

  private final PolicyIndex index;

  public PolicyEngine(PolicyIndex index) {
    this.index = index;
  }

  /** Column mask decision: the first matching policy's most specific item, or empty. */
  public Optional<MaskInstruction> maskFor(String catalog, String schema, String table,
      String column, Subject subject) {
    String c = PolicyNames.normalize(catalog, "catalog");
    String s = PolicyNames.normalize(schema, "schema");
    String t = PolicyNames.normalize(table, "table");
    String col = PolicyNames.normalize(column, "column");
    for (Policy policy : index.dataMasks()) {
      if (policy.resources().stream().noneMatch(r -> matches(r, c, s, t, col))) {
        continue;
      }
      DataMaskItem best = null;
      int bestLevel = 0;
      for (DataMaskItem item : policy.dataMaskItems()) {
        int level = item.selector().matchLevel(subject);
        if (level > bestLevel) {
          bestLevel = level;
          best = item;
        }
      }
      if (best != null) {
        return Optional.of(new MaskInstruction(policy.name(), best.udf(), best.arguments()));
      }
    }
    return Optional.empty();
  }

  /**
   * Row filter decision: all matching hits in decision order; AND composition
   * is the caller's job (masking uniqueness does not apply to predicates).
   */
  public List<RowFilterHit> rowFiltersFor(String catalog, String schema, String table,
      Subject subject) {
    String c = PolicyNames.normalize(catalog, "catalog");
    String s = PolicyNames.normalize(schema, "schema");
    String t = PolicyNames.normalize(table, "table");
    List<RowFilterHit> hits = new ArrayList<>();
    for (Policy policy : index.rowFilters()) {
      if (policy.resources().stream().noneMatch(r -> matches(r, c, s, t, null))) {
        continue;
      }
      RowFilterItem best = null;
      int bestLevel = 0;
      for (RowFilterItem item : policy.rowFilterItems()) {
        int level = item.selector().matchLevel(subject);
        if (level > bestLevel) {
          bestLevel = level;
          best = item;
        }
      }
      if (best != null) {
        hits.add(new RowFilterHit(policy.name(), best.filterExpr()));
      }
    }
    return List.copyOf(hits);
  }

  private static boolean matches(PolicyResource r, String c, String s, String t, String column) {
    if (!levelMatches(r.catalog(), c) || !levelMatches(r.schema(), s)
        || !levelMatches(r.table(), t)) {
      return false;
    }
    if (column == null) {
      return r.column() == null;
    }
    return r.column() != null && levelMatches(r.column(), column);
  }

  private static boolean levelMatches(String pattern, String value) {
    return "*".equals(pattern) || pattern.equals(value);
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mask-policy test`
Expected: 全部测试通过。

- [ ] **Step 5: Commit**

```bash
git add mask-policy/src
git commit -m "feat(policy): PDP 决策引擎（priority/声明顺序、主体特异性、行过滤多命中叠加）"
```

---

### Task 5: LegacyPolicyAdapter（mask-core，旧配置 → 策略集）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/config/LegacyPolicyAdapter.java`
- Test: `mask-core/src/test/java/io/sqlmask/config/LegacyPolicyAdapterTest.java`

**Interfaces:**
- Consumes: `MaskingConfig`（tables / columnPolicies / policies）、Task 2 模型、Task 4 `PolicyEngine`。
- Produces: `static List<Policy> convert(MaskingConfig config)` — 每个 `ColumnPolicyBinding` → 一条 DATA_MASK 策略（名 `<policyName>:<columnKey>`，主体 `groups:["*"]`，priority 0）；每个带 `rowFilter` 的表 → 一条 ROW_FILTER 策略（名 `rowFilter:<catalog.schema.table>`，主体 `groups:["*"]`）。声明顺序保持。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.config;

import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.match.PolicyIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPolicyAdapterTest {

  private static final String LEGACY_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            columns:
              - {name: id, type: bigint}

      columns:
        - {catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask}

      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Test
  void convertedPoliciesReproduceLegacyLookups() {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(LEGACY_YAML, "metadata.yaml");
    List<Policy> policies = LegacyPolicyAdapter.convert(loaded.config());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(policies));
    Subject anonymous = Subject.anonymous();

    // 掩码：精确列命中，udf/参数与旧定义一致
    var mask = engine.maskFor("crm", "public", "customer", "phone", anonymous).orElseThrow();
    assertEquals("mask_phone", mask.udf());
    assertEquals(List.of(3, 4), mask.arguments());
    assertTrue(engine.maskFor("crm", "public", "customer", "id", anonymous).isEmpty());

    // 行过滤：带 rowFilter 的表命中且表达式一致；无 rowFilter 的表不命中
    var hits = engine.rowFiltersFor("crm", "public", "customer", anonymous);
    assertEquals(1, hits.size());
    assertEquals("status = 'active'", hits.get(0).expr());
    assertTrue(engine.rowFiltersFor("crm", "public", "orders", anonymous).isEmpty());

    // 策略名约定（错误消息前缀用）
    assertEquals("phone_mask:crm.public.customer.phone", mask.policyName());
  }

  @Test
  void convertedRowFilterPolicyIsNamedAfterTable() {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(LEGACY_YAML, "metadata.yaml");
    List<Policy> policies = LegacyPolicyAdapter.convert(loaded.config());
    assertEquals("rowFilter:crm.public.customer", policies.stream()
        .filter(p -> p.type() == PolicyType.ROW_FILTER).findFirst().orElseThrow().name());
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=LegacyPolicyAdapterTest`
Expected: 编译失败（`LegacyPolicyAdapter` 不存在）。

- [ ] **Step 3: 实现 LegacyPolicyAdapter**

```java
package io.sqlmask.config;

import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts the legacy metadata.yaml policy sections (policies / columns /
 * rowFilter) into an equivalent policy set: every item applies to everyone
 * (subject groups ["*"]), priority 0, enabled, in declaration order. The
 * rewrite output is byte-identical to the legacy registry path.
 */
public final class LegacyPolicyAdapter {

  private LegacyPolicyAdapter() {
  }

  public static List<Policy> convert(MaskingConfig config) {
    List<Policy> policies = new ArrayList<>();
    SubjectSelector everyone = new SubjectSelector(Set.of(), Set.of("*"));
    for (MaskingConfig.ColumnPolicyBinding binding : config.columnPolicies()) {
      ColumnKey key = binding.key();
      MaskingPolicy policy = config.policies().get(binding.policyName());
      policies.add(new Policy(
          binding.policyName() + ":" + key,
          true, 0, PolicyType.DATA_MASK,
          List.of(PolicyResource.column(key.catalog(), key.schema(), key.table(), key.column())),
          List.of(new DataMaskItem(everyone, policy.udf(), policy.arguments())),
          List.of()));
    }
    for (TableMetadata table : config.tables()) {
      if (table.rowFilter() == null) {
        continue;
      }
      policies.add(new Policy(
          "rowFilter:" + ColumnKey.normalize(table.catalog(), "catalog") + "."
              + ColumnKey.normalize(table.schema(), "schema") + "."
              + ColumnKey.normalize(table.name(), "table"),
          true, 0, PolicyType.ROW_FILTER,
          List.of(PolicyResource.table(
              ColumnKey.normalize(table.catalog(), "catalog"),
              ColumnKey.normalize(table.schema(), "schema"),
              ColumnKey.normalize(table.name(), "table"))),
          List.of(),
          List.of(new RowFilterItem(everyone, table.rowFilter()))));
    }
    return policies;
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=LegacyPolicyAdapterTest`
Expected: 2 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src
git commit -m "feat(core): 旧格式策略转换为等价策略集（主体 * / priority 0）"
```

---

### Task 6: PolicyResourceResolver（资源 fail-closed 校验）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/config/PolicyResourceResolver.java`
- Test: `mask-core/src/test/java/io/sqlmask/config/PolicyResourceResolverTest.java`

**Interfaces:**
- Consumes: `LoadedConfig.tables()`（声明表集合）、Task 2 模型。
- Produces: `void validate(List<Policy> policies)` — 每个策略资源必须命中 ≥1 张声明表；column 非 `*` 时必须存在于某张命中表的声明列，否则 `SqlMaskException(CONFIG_ERROR)`，消息带 `policy '<name>':` 前缀。仅在 policyYaml 路径调用（Task 8）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyResourceResolverTest {

  private static final String METADATA_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
      policies: {}
      """;

  private LoadedConfig loaded() {
    return new YamlConfigLoader().loadContent(METADATA_YAML, "metadata.yaml");
  }

  private static Policy rowFilterPolicy(String catalog, String schema, String table) {
    return new Policy("f", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table(catalog, schema, table)), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")), "id > 0")));
  }

  @Test
  void unknownTableIsRejectedWithPolicyPrefix() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new PolicyResourceResolver(loaded()).validate(
            List.of(rowFilterPolicy("crm", "public", "nowhere"))));
    assertTrue(e.getMessage().contains("policy 'f':"));
    assertTrue(e.getMessage().contains("matches no declared table"));
    assertTrue(e.getCode() == SqlMaskException.Code.CONFIG_ERROR);
  }

  @Test
  void wildcardCatalogResolvesWhenAnyTableIsDeclared() {
    new PolicyResourceResolver(loaded()).validate(
        List.of(rowFilterPolicy("*", "public", "customer")));
  }

  @Test
  void unknownColumnIsRejected() {
    Policy mask = new Policy("m", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "email")),
        List.of(new io.sqlmask.policy.model.DataMaskItem(
            new SubjectSelector(Set.of(), Set.of("*")), "mask_email", List.of())),
        List.of());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new PolicyResourceResolver(loaded()).validate(List.of(mask)));
    assertTrue(e.getMessage().contains("column 'email'"));
  }

  @Test
  void starColumnExpandsToAllDeclaredColumns() {
    Policy mask = new Policy("m", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "*")),
        List.of(new io.sqlmask.policy.model.DataMaskItem(
            new SubjectSelector(Set.of(), Set.of("*")), "mask_all", List.of())),
        List.of());
    new PolicyResourceResolver(loaded()).validate(List.of(mask));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyResourceResolverTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 PolicyResourceResolver**

```java
package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;

import java.util.List;

/**
 * Fail-closed resource resolution: every policy resource must match at least
 * one declared table (and a concrete column must exist on one of the matched
 * tables). A policy that silently matches nothing is a configuration error —
 * a typo would otherwise disable protection unnoticed.
 */
public final class PolicyResourceResolver {

  private final LoadedConfig loaded;

  public PolicyResourceResolver(LoadedConfig loaded) {
    this.loaded = loaded;
  }

  public void validate(List<Policy> policies) {
    for (Policy policy : policies) {
      for (PolicyResource resource : policy.resources()) {
        List<TableMetadata> tables = matchingTables(resource);
        if (tables.isEmpty()) {
          throw error("policy '" + policy.name() + "': resource '" + describe(resource)
              + "' matches no declared table");
        }
        if (resource.column() != null && !"*".equals(resource.column())) {
          boolean columnDeclared = tables.stream().anyMatch(t -> hasColumn(t, resource.column()));
          if (!columnDeclared) {
            throw error("policy '" + policy.name() + "': resource column '"
                + resource.column() + "' is not declared on any matched table of '"
                + describe(resource) + "'");
          }
        }
      }
    }
  }

  private List<TableMetadata> matchingTables(PolicyResource resource) {
    return loaded.tables().stream()
        .filter(t -> levelMatches(resource.catalog(), t.catalog(), "catalog")
            && levelMatches(resource.schema(), t.schema(), "schema")
            && levelMatches(resource.table(), t.name(), "table"))
        .toList();
  }

  private boolean hasColumn(TableMetadata table, String column) {
    return table.columns().stream()
        .anyMatch(c -> ColumnKey.normalize(c.name(), "column").equals(column));
  }

  private static boolean levelMatches(String pattern, String declared, String part) {
    return "*".equals(pattern)
        || pattern.equals(ColumnKey.normalize(declared, part));
  }

  private static String describe(PolicyResource resource) {
    return resource.catalog() + "." + resource.schema() + "." + resource.table();
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mask-core -am test -Dtest=PolicyResourceResolverTest`
Expected: 4 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src
git commit -m "feat(core): 策略资源 fail-closed 解析校验（未命中声明表/列即 CONFIG_ERROR）"
```

---

### Task 7: 行过滤注册表接入 PDP + 改写器门控切换

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/rowfilter/RowFilterRegistry.java`
- Modify: `mask-core/src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（三处门控：约 83、294、462 行的 `table.rowFilter() == null`）
- Test: `mask-core/src/test/java/io/sqlmask/rowfilter/RowFilterRegistryTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 4 `PolicyEngine.rowFiltersFor`、Task 2 `RowFilterHit` / `Subject`。
- Produces:
  - `static RowFilterRegistry buildFromPolicies(List<TableMetadata> tables, PolicyEngine engine, Subject subject, DialectAdapter dialect, SchemaPlus schema)` — 对每张声明表查询命中项并逐条注册；同一表多条命中合并为单个 AND 模板；错误前缀 `policy '<name>': filterExpr`。
  - `boolean isControlled(String catalog, String schema, String table)` — 注册表驱动的"受控表"判定（改写器新门控）。
  - 改写器：受控判定从静态 `table.rowFilter()` 字段切换到注册表（新格式路径下表字段恒为 null，注册表才是事实来源；旧路径两者等价）。

- [ ] **Step 1: 写失败测试（追加到 RowFilterRegistryTest）**

```java
  @Test
  void buildFromPoliciesRegistersHitsWithPolicyPrefix() {
    Policy filter = new Policy("f", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "customer")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")),
            "status = 'active'")));
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(filter)));
    RowFilterRegistry registry = RowFilterRegistry.buildFromPolicies(
        loaded.tables(), engine, Subject.anonymous(), dialect, schema);
    assertTrue(registry.conditionTemplateOf("crm", "public", "customer").isPresent());
    assertTrue(registry.isControlled("crm", "public", "customer"));
  }

  @Test
  void invalidPolicyExpressionFailsWithPolicyPrefixNotTableName() {
    Policy filter = new Policy("f", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "customer")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")),
            "status = 'active' AND lower(status) = 'active'")));
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(filter)));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> RowFilterRegistry.buildFromPolicies(
            loaded.tables(), engine, Subject.anonymous(), dialect, schema));
    assertTrue(e.getMessage().startsWith("policy 'f': filterExpr"));
    assertTrue(e.getCode() == SqlMaskException.Code.CONFIG_ERROR);
  }

  @Test
  void multipleHitsOnSameTableCombineIntoSingleAndTemplate() {
    Policy first = new Policy("f1", true, 1, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "customer")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")),
            "status = 'active'")));
    Policy second = new Policy("f2", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "customer")), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")),
            "region = 'north'")));
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(first, second)));
    RowFilterRegistry registry = RowFilterRegistry.buildFromPolicies(
        loaded.tables(), engine, Subject.anonymous(), dialect, schema);
    SqlNode template = registry.conditionTemplateOf("crm", "public", "customer").orElseThrow();
    assertEquals(SqlKind.AND, template.getKind());
  }
```

说明：`loaded` / `dialect` / `schema` 的构造方式沿用该测试类中既有用例的 setup（`YamlConfigLoader().loadContent(...)` + `DialectRegistry.create("postgresql")` + `YamlCalciteSchemaFactory.create(loaded)`）；所需 import：`io.sqlmask.policy.match.PolicyEngine`、`io.sqlmask.policy.match.PolicyIndex`、`io.sqlmask.policy.model.*`、`io.sqlmask.policy.model.Subject`。若类中 setup 变量名不同，按既有用例风格接入。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=RowFilterRegistryTest`
Expected: 编译失败（`buildFromPolicies` / `isControlled` 不存在）。

- [ ] **Step 3: 实现 RowFilterRegistry 扩展**

在 `RowFilterRegistry` 中：

1. 原 `register(TableMetadata, ...)` 改为委托，保持旧前缀逐字节不变：

```java
  private void register(TableMetadata table, DialectAdapter dialect, SchemaPlus schema) {
    registerCondition(table, table.rowFilter(),
        "table '" + table.qualifiedName() + "': row filter", dialect, schema);
  }
```

2. 新增公共注册方法（原 register 主体改参数化；AND 组合在放入 templates 时完成）：

```java
  void registerCondition(TableMetadata table, String expr, String prefix,
      DialectAdapter dialect, SchemaPlus schema) {
    String wrapped = "SELECT * FROM " + table.qualifiedName() + " WHERE " + expr;
    Set<String> columnNames = table.columns().stream()
        .map(column -> column.name().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    SqlNode parsed;
    try {
      parsed = dialect.parse(wrapped, 0);
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " cannot be parsed: " + e.getMessage(), e);
    }
    SqlNode condition = ((SqlSelect) parsed).getWhere();
    try {
      condition.accept(whitelistVisitor(columnNames));
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " " + e.getMessage(), e);
    }
    // validate a second parse so the validator's in-place mutations never
    // reach the cached template
    SqlNode validationTree;
    try {
      validationTree = dialect.parse(wrapped, 0);
      dialect.validate(validationTree, schema);
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " is not a valid condition: " + e.getMessage(), e);
    }
    String tableKey = key(table.catalog(), table.schema(), table.name());
    SqlNode existing = templates.get(tableKey);
    templates.put(tableKey, existing == null ? condition
        : new SqlBasicCall(SqlStdOperatorTable.AND,
            List.of(existing, condition), SqlParserPos.ZERO));
  }
```

（所需 import：`org.apache.calcite.sql.fun.SqlStdOperatorTable`、`org.apache.calcite.sql.parser.SqlParserPos`、`org.apache.calcite.sql.SqlBasicCall`；模板树未被校验器触碰且下游按文本重建，共享无副作用。）

3. 新增两个静态工厂/查询：

```java
  /**
   * Registers the PDP's row-filter decisions for a subject: every declared
   * table is asked for hits; each hit is validated with a policy-name prefix
   * and multiple hits on one table combine into a single AND template.
   */
  public static RowFilterRegistry buildFromPolicies(List<TableMetadata> tables,
      PolicyEngine engine, Subject subject, DialectAdapter dialect, SchemaPlus schema) {
    RowFilterRegistry registry = new RowFilterRegistry();
    for (TableMetadata table : tables) {
      for (RowFilterHit hit : engine.rowFiltersFor(
          table.catalog(), table.schema(), table.name(), subject)) {
        registry.registerCondition(table, hit.expr(),
            "policy '" + hit.policyName() + "': filterExpr", dialect, schema);
      }
    }
    return registry;
  }

  /** Registry-driven "controlled table" check: the single source of truth for the rewriter. */
  public boolean isControlled(String catalog, String schema, String table) {
    return templates.containsKey(key(catalog, schema, table));
  }
```

- [ ] **Step 4: 切换 RowFilterRewriter 门控（三处）**

把三处 `if (table.rowFilter() == null) { ... }` 门控替换为注册表查询（注册表成为受控判定的事实来源；旧路径下注册表恰好包含全部带 `rowFilter` 的表，行为等价）：

- 约 83 行与 294 行（遍历/候选判定处）：
```java
    if (!registry.isControlled(table.catalog(), table.schema(), table.name())) {
      // 原本的"非受控"分支原样保留
```
- 约 462 行（`injectIfFiltered`）：
```java
    SqlNode template = context.registry.conditionTemplateOf(
        table.catalog(), table.schema(), table.name()).orElse(null);
    if (template == null) {
      return reference;
    }
```
（删除原 `orElseThrow` 兜底分支——注册表即门控，"declared but not present" 不可能再发生。）注意上下文里注册表变量的实际名字（`registry` / `context.registry`），按所在作用域取用。

- [ ] **Step 5: 运行行过滤全部测试（旧路径回归）**

Run: `mvn -q -pl mask-core -am test -Dtest='RowFilter*Test'`
Expected: 既有 RowFilterRegistryTest / RowFilterRewriterTest 全部通过（旧行为等价），新增 3 个用例通过。

- [ ] **Step 6: Commit**

```bash
git add mask-core/src
git commit -m "feat(core): 行过滤注册表从 PDP 命中构建（AND 组合 + policy 前缀），受控判定切注册表"
```

---

### Task 8: PEP 接线（引擎统一走 PDP，退役旧选择器）

**Files:**
- Move: `mask-core/src/main/java/io/sqlmask/policy/MaskingPolicy.java` → `mask-core/src/main/java/io/sqlmask/config/MaskingPolicy.java`（包声明改为 `io.sqlmask.config`；它是旧格式配置模型值类型，避免 mask-core 与 mask-policy 分裂 `io.sqlmask.policy` 包）
- Delete: `mask-core/src/main/java/io/sqlmask/policy/PolicyRegistry.java`
- Delete: `mask-core/src/main/java/io/sqlmask/policy/PolicySelector.java`
- Delete: `mask-core/src/test/java/io/sqlmask/policy/PolicySelectorTest.java`
- Modify: `mask-core/src/main/java/io/sqlmask/config/LoadedConfig.java`（record 去掉 `policyRegistry` 组件）
- Modify: `mask-core/src/main/java/io/sqlmask/config/YamlConfigLoader.java`（删 `buildRegistry`，构造 `new LoadedConfig(config)`）
- Modify: `mask-core/src/main/java/io/sqlmask/config/source/EffectiveConfigAssembler.java`（`new LoadedConfig(config)`）
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/OutputRewrite.java`（`MaskingPolicy` → `MaskInstruction`）
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/RewritePlan.java`（`of(lineage, MaskSelector)`）
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/SqlRewriteService.java`（`renderUdfCall(MaskInstruction, ...)`；错误消息里的 `policy.name()` → `policy.policyName()`）
- Create: `mask-core/src/main/java/io/sqlmask/rewrite/MaskSelector.java`
- Create: `mask-core/src/main/java/io/sqlmask/rewrite/PdpMaskSelector.java`
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/RewriteEngine.java`
- Modify: `mask-core/src/main/java/io/sqlmask/config/MaskingConfig.java` / `mask-core/src/main/java/io/sqlmask/server/ConfigController.java`（`MaskingPolicy` import 改为 `io.sqlmask.config.MaskingPolicy`）
- Test: Modify `mask-core/src/test/java/io/sqlmask/rewrite/RewritePlanTest.java`（选择器改为 lambda/fake `MaskSelector`）、`SqlRewriteServiceTest.java`（`MaskingPolicy` → `MaskInstruction`，注意 `MaskInstruction` 需要 `policyName` 字段）
- Test: Create `mask-core/src/test/java/io/sqlmask/rewrite/RewriteEnginePolicyTest.java`

**Interfaces:**
- Consumes: Task 2/4/5/6/7 的全部产物。
- Produces:
  - `interface MaskSelector { Optional<MaskInstruction> select(Set<ColumnOrigin> origins); }`
  - `PdpMaskSelector(PolicyEngine engine, Subject subject)` — 每来源列查 PDP，命中者中取 `ColumnKey.ORDER` 字典序最小（与旧 `PolicySelector` 决胜规则一致）。
  - `RewriteEngine.rewrite(String metadataYaml, String sqlText, String dialectName)`（旧签名，行为不变）
  - `RewriteEngine.rewrite(LoadedConfig loaded, String sqlText, String dialectName)`（既有重载，行为不变）
  - `RewriteEngine.rewrite(LoadedConfig loaded, String policyYaml, String sqlText, String dialectName, Subject subject)` — 新入口：`policyYaml` 非空走新格式（互斥校验 + 加载 + 资源校验 + `buildFromPolicies`），否则旧格式（`LegacyPolicyAdapter` 转换 + 旧 `RowFilterRegistry.build`）。
  - `LoadedConfig(MaskingConfig config)`（单组件 record）。

- [ ] **Step 1: 写失败测试（RewriteEnginePolicyTest）**

```java
package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteEnginePolicyTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
      policies: {}
      """;

  private static final String POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {groups: ["*"], udf: mask_phone, arguments: [3, 4]}
        - name: filter-active
          resources:
            - {catalog: crm, schema: public, table: customer}
          rowFilterItems:
            - {groups: ["*"], filterExpr: "status = 'active'"}
      """;

  private static final String SQL = "SELECT phone FROM customer;";

  @Test
  void policyYamlMaskMatchesLegacyEquivalent() {
    String legacy = METADATA.replace("policies: {}", """
        columns:
          - {catalog: crm, schema: public, table: customer, column: phone, policy: p}
        policies:
          p:
            udf: mask_phone
            arguments: [3, 4]
        """);
    String viaLegacy = new RewriteEngine().rewrite(legacy, SQL, "postgresql")
        .get(0).rewrittenSql();
    String viaPolicyFile = new RewriteEngine()
        .rewrite(METADATA, POLICIES, SQL, "postgresql", Subject.anonymous())
        .get(0).rewrittenSql();
    assertEquals(viaLegacy, viaPolicyFile);
  }

  @Test
  void subjectWithoutMatchPassesThroughUnmasked() {
    String policies = POLICIES.replace("groups: [\"*\"]", "users: [\"alice\"]");
    List<RewriteEngine.StatementRewrite> result = new RewriteEngine()
        .rewrite(METADATA, policies, SQL, "postgresql", Subject.anonymous());
    assertEquals(SQL.replace(";", ""), result.get(0).rewrittenSql());
    assertEquals(false, result.get(0).masked());
  }

  @Test
  void namedUserGetsMaskAndRowFilter() {
    List<RewriteEngine.StatementRewrite> result = new RewriteEngine()
        .rewrite(METADATA, POLICIES, SQL, "postgresql", Subject.of("alice", List.of()));
    String sql = result.get(0).rewrittenSql();
    assertTrue(result.get(0).masked());
    assertTrue(sql.contains("mask_phone"));
    assertTrue(result.get(0).rowFiltered());
    assertTrue(sql.contains("status = 'active'"));
  }

  @Test
  void policyYamlConflictingWithLegacyPoliciesIsRejected() {
    String legacyWithPolicies = METADATA.replace("policies: {}", """
        policies:
          p:
            udf: mask_phone
            arguments: []
        """);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(legacyWithPolicies, POLICIES, SQL,
            "postgresql", Subject.anonymous()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("single source"));
  }

  @Test
  void policyYamlConflictingWithRowFilterIsRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - {name: id, type: bigint}
                - {name: phone, type: varchar}
        policies: {}
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(yaml, POLICIES, SQL, "postgresql",
            Subject.anonymous()));
    assertTrue(e.getMessage().contains("rowFilter"));
  }

  @Test
  void invalidPolicyExpressionFailsBeforeAnyStatement() {
    String policies = POLICIES.replace("status = 'active'",
        "status = 'active' AND random() > 0");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(METADATA, policies, SQL, "postgresql",
            Subject.anonymous()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().startsWith("policy 'filter-active'"));
  }

  @Test
  void twoRowFilterPoliciesComposeWithAnd() {
    String policies = """
        policies:
          - name: f1
            resources:
              - {catalog: crm, schema: public, table: customer}
            rowFilterItems:
              - {groups: ["*"], filterExpr: "status = 'active'"}
          - name: f2
            resources:
              - {catalog: crm, schema: public, table: customer}
            rowFilterItems:
              - {groups: ["*"], filterExpr: "id > 0"}
        """;
    String sql = new RewriteEngine()
        .rewrite(METADATA, policies, SQL, "postgresql", Subject.anonymous())
        .get(0).rewrittenSql();
    assertTrue(sql.contains("status = 'active'"));
    assertTrue(sql.contains("id > 0"));
  }

  @Test
  void multiOriginOutputPicksSmallestColumnKeyAmongHits() {
    String metadata = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: a
              columns: [{name: x, type: varchar}]
            - catalog: crm
              schema: public
              name: b
              columns: [{name: y, type: varchar}]
        policies: {}
        """;
    String policies = """
        policies:
          - name: mask-b-y
            resources:
              - {catalog: crm, schema: public, table: b, column: y}
            dataMaskItems:
              - {groups: ["*"], udf: mask_b}
          - name: mask-a-x
            resources:
              - {catalog: crm, schema: public, table: a, column: x}
            dataMaskItems:
              - {groups: ["*"], udf: mask_a}
        """;
    String sql = new RewriteEngine()
        .rewrite(metadata, policies, "SELECT a.x || b.y FROM a, b;", "postgresql",
            Subject.anonymous())
        .get(0).rewrittenSql();
    // crm.public.a.x < crm.public.b.y：只有 mask_a 生效
    assertTrue(sql.contains("mask_a("));
    assertTrue(!sql.contains("mask_b("));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=RewriteEnginePolicyTest`
Expected: 编译失败（新重载 / `MaskSelector` 不存在）。

- [ ] **Step 3: 移动与退役**

```bash
git mv mask-core/src/main/java/io/sqlmask/policy/MaskingPolicy.java \
       mask-core/src/main/java/io/sqlmask/config/MaskingPolicy.java
git rm mask-core/src/main/java/io/sqlmask/policy/PolicyRegistry.java \
       mask-core/src/main/java/io/sqlmask/policy/PolicySelector.java \
       mask-core/src/test/java/io/sqlmask/policy/PolicySelectorTest.java
```

然后逐文件修改（每个文件先改 import，编译器会指出全部遗漏）：

1. `config/MaskingPolicy.java`：包声明 `package io.sqlmask.config;`。
2. `config/LoadedConfig.java`：`public record LoadedConfig(MaskingConfig config)`（去掉 `policyRegistry` 组件与相关 import；`findTable` / `tables()` / `normalizePart` 保留）。
3. `config/YamlConfigLoader.java`：删除 `buildRegistry` 方法与 `PolicyRegistry` import，`return new LoadedConfig(config);`。
4. `config/source/EffectiveConfigAssembler.java`：`return new LoadedConfig(config);`，`MaskingPolicy` import 改 `io.sqlmask.config.MaskingPolicy`（同包可省略）。
5. `config/MaskingConfig.java`：import 改 `io.sqlmask.config.MaskingPolicy`（同包省略）。
6. `server/ConfigController.java`：`MaskingPolicy` import 改 `io.sqlmask.config.MaskingPolicy`。
7. `rewrite/MaskSelector.java`（新建）：

```java
package io.sqlmask.rewrite;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.policy.model.MaskInstruction;

import java.util.Optional;
import java.util.Set;

/** Chooses the masking instruction for one output column from its origin columns. */
public interface MaskSelector {

  Optional<MaskInstruction> select(Set<ColumnOrigin> origins);
}
```

8. `rewrite/PdpMaskSelector.java`（新建）：

```java
package io.sqlmask.rewrite;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.model.MaskInstruction;
import io.sqlmask.policy.model.Subject;

import java.util.Optional;
import java.util.Set;

/**
 * PEP adapter: asks the policy decision point for every origin column and
 * applies the legacy-stable tie-break — among origins with a mask decision,
 * the lexicographically smallest normalized column key wins. Priority only
 * resolves conflicts on the same column.
 */
public final class PdpMaskSelector implements MaskSelector {

  private final PolicyEngine engine;
  private final Subject subject;

  public PdpMaskSelector(PolicyEngine engine, Subject subject) {
    this.engine = engine;
    this.subject = subject;
  }

  @Override
  public Optional<MaskInstruction> select(Set<ColumnOrigin> origins) {
    ColumnKey bestKey = null;
    Optional<MaskInstruction> best = Optional.empty();
    for (ColumnOrigin origin : origins) {
      ColumnKey key = origin.key();
      Optional<MaskInstruction> instruction = engine.maskFor(
          key.catalog(), key.schema(), key.table(), key.column(), subject);
      if (instruction.isEmpty()) {
        continue;
      }
      if (bestKey == null || ColumnKey.ORDER.compare(key, bestKey) < 0) {
        bestKey = key;
        best = instruction;
      }
    }
    return best;
  }
}
```

9. `rewrite/OutputRewrite.java`：`Optional<MaskInstruction> policy`，`masked(int, String, MaskInstruction)`，import `io.sqlmask.policy.model.MaskInstruction`。
10. `rewrite/RewritePlan.java`：`of(List<OutputLineage> lineage, MaskSelector selector)`，import 调整（`LineageStatus` 等不变）。
11. `rewrite/SqlRewriteService.java`：`renderUdfCall(MaskInstruction policy, ...)` 与 `toLiteral(Object, MaskInstruction policy)`；其中 `policy.name()` → `policy.policyName()`；import 调整。
12. `rewrite/RewriteEngine.java`：

```java
  public List<StatementRewrite> rewrite(String metadataYaml, String sqlText, String dialectName) {
    LoadedConfig loaded =
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialectName);
    return rewrite(loaded, null, sqlText, dialectName, Subject.anonymous());
  }

  public List<StatementRewrite> rewrite(LoadedConfig loaded, String sqlText, String dialectName) {
    return rewrite(loaded, null, sqlText, dialectName, Subject.anonymous());
  }

  /**
   * Rewrites against a resolved configuration with an optional Ranger-style
   * policy file and query subject. {@code policyYaml} blank means the legacy
   * policy sections of the configuration are the single policy source.
   */
  public List<StatementRewrite> rewrite(LoadedConfig loaded, String policyYaml, String sqlText,
      String dialectName, Subject subject) {
    List<io.sqlmask.policy.model.Policy> policies = buildPolicies(loaded, policyYaml);
    io.sqlmask.policy.match.PolicyEngine engine =
        new io.sqlmask.policy.match.PolicyEngine(io.sqlmask.policy.match.PolicyIndex.of(policies));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    DialectAdapter dialect = createDialect(dialectName);
    RowFilterRegistry rowFilters = policyYaml == null || policyYaml.isBlank()
        ? RowFilterRegistry.build(loaded, dialect, schema)
        : RowFilterRegistry.buildFromPolicies(loaded.tables(), engine, subject, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);
    LineageAnalyzer analyzer = new LineageAnalyzer();
    MaskSelector selector = new PdpMaskSelector(engine, subject);
    // ……其余主体与既有 rewrite(LoadedConfig, ...) 完全一致，逐行保留
  }

  private List<io.sqlmask.policy.model.Policy> buildPolicies(LoadedConfig loaded,
      String policyYaml) {
    if (policyYaml == null || policyYaml.isBlank()) {
      return LegacyPolicyAdapter.convert(loaded.config());
    }
    requireNoLegacyPolicies(loaded);
    List<io.sqlmask.policy.model.Policy> policies;
    try {
      policies = new io.sqlmask.policy.store.PolicyYamlLoader().parse(policyYaml, "policies.yaml");
    } catch (io.sqlmask.policy.PolicyException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, e.getMessage(), e);
    }
    new PolicyResourceResolver(loaded).validate(policies);
    return policies;
  }

  private static void requireNoLegacyPolicies(LoadedConfig loaded) {
    MaskingConfig config = loaded.config();
    if (!config.policies().isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policies must come from a single source: metadataYaml declares non-empty 'policies' "
              + "but policyYaml was also given");
    }
    if (!config.columnPolicies().isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policies must come from a single source: metadataYaml declares 'columns' bindings "
              + "but policyYaml was also given");
    }
    for (TableMetadata table : config.tables()) {
      if (table.rowFilter() != null) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policies must come from a single source: metadataYaml declares rowFilter for table '"
                + table.qualifiedName() + "' but policyYaml was also given");
      }
    }
  }
```

（实现时把 `io.sqlmask.policy.model.Policy` 等写成正常 import，不要内联全限定名；上面为了标注"哪些是新增"才展开。`rewriteOne` 及语句循环不动。）

13. 测试文件：`RewritePlanTest` 中 `PolicySelector` 用法替换为内联 lambda `origins -> Optional.empty()`（或按用例需要返回固定指令的 fake）；`SqlRewriteServiceTest` 中 `new MaskingPolicy(name, udf, args)` → `new MaskInstruction(name, udf, args)`。

- [ ] **Step 4: 全量回归**

Run: `mvn test`
Expected: `BUILD SUCCESS`，0 失败。关键回归网：`GoldenOutputTest`（旧 YAML 输出逐字节）、`RowFilterRewriterTest`、`RewriteControllerTest`。此时基线 300 + 新增测试全部通过。

- [ ] **Step 5: Commit**

```bash
git add -A mask-core
git commit -m "feat(core): 引擎统一走 PDP 决策（Subject 维度 + policies.yaml 入口 + 互斥校验），退役旧选择器"
```

---

### Task 9: CLI 参数（--policies / --user 复用 / --groups）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/cli/CliOptions.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskRunner.java`
- Test: Modify `mask-core/src/test/java/io/sqlmask/cli/`（该目录既有测试；构造器签名变化处同步更新；新增 `SqlMaskRunnerPolicyTest.java`）

**Interfaces:**
- Consumes: Task 8 的三参/五参 `rewrite` 重载、`Subject.of`。
- Produces:
  - `record CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups, String sql, Path inputPath, Path outputPath, String dialect)`
  - CLI 选项：`--policies <path>`；`--groups <g1,g2>`（`split = ","`，可重复，合并去重保序）；`--user` 描述更新（改写模式 = 查询主体）。
  - `SqlMaskServiceApplication.CLI_OPTIONS` 常量加入 `--policies`、`--groups`（单参调用也能识别为 CLI 模式）。

- [ ] **Step 1: 写失败测试（SqlMaskRunnerPolicyTest）**

```java
package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskRunnerPolicyTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
      policies: {}
      """;

  private static final String POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {users: ["alice"], udf: mask_phone}
      """;

  @TempDir
  Path dir;

  private Path write(String name, String content) throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  @Test
  void subjectUserDeterminesMasking() throws Exception {
    Path metadata = write("metadata.yaml", METADATA);
    Path policies = write("policies.yaml", POLICIES);
    SqlMaskRunner runner = new SqlMaskRunner();
    CliOptions anonymous = new CliOptions(metadata, policies, null, null,
        "SELECT phone FROM customer;", null, null, "postgresql");
    assertEquals("SELECT phone FROM customer", runner.run(anonymous).trim());
    CliOptions alice = new CliOptions(metadata, policies, "alice", List.of("devs"),
        "SELECT phone FROM customer;", null, null, "postgresql");
    assertTrue(runner.run(alice).contains("mask_phone"));
  }

  @Test
  void conflictingPolicySourcesFailWithConfigError() throws Exception {
    Path metadata = write("metadata.yaml", METADATA.replace("policies: {}", """
        policies:
          p:
            udf: mask_phone
        """));
    Path policies = write("policies.yaml", POLICIES);
    SqlMaskRunner runner = new SqlMaskRunner();
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> runner.run(new CliOptions(metadata, policies, null, null,
            "SELECT phone FROM customer;", null, null, "postgresql")));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mask-core -am test -Dtest=SqlMaskRunnerPolicyTest`
Expected: 编译失败（`CliOptions` 新构造器不存在）。

- [ ] **Step 3: 实现 CLI 三处修改**

1. `CliOptions.java`：

```java
/**
 * Validated CLI options.
 *
 * @param metadataPath path of the YAML metadata configuration
 * @param policiesPath optional path of a Ranger-style policies.yaml; when given,
 *                     the metadata file must not declare policies/columns/rowFilter
 * @param user         rewrite mode: query subject user (pull-metadata mode: database user)
 * @param groups       rewrite mode: query subject groups, order-preserving
 * @param sql          inline SQL text; mutually exclusive with {@code inputPath}
 * @param inputPath    UTF-8 file with the SQL statements to rewrite
 * @param outputPath   optional output file; stdout when absent
 * @param dialect      dialect name
 */
public record CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups,
    String sql, Path inputPath, Path outputPath, String dialect) {

  public CliOptions {
    java.util.Objects.requireNonNull(metadataPath, "metadataPath");
    groups = groups == null ? List.of() : List.copyOf(groups);
    dialect = dialect == null ? "postgresql" : dialect;
  }

  public Optional<Path> output() {
    return Optional.ofNullable(outputPath);
  }
}
```

2. `SqlMaskApplication.java`：加字段与校验——

```java
  @Option(names = "--policies", paramLabel = "<path>",
      description = "Path to a Ranger-style policies.yaml. When given, the metadata file "
          + "must not declare policies/columns/rowFilter.")
  private Path policiesPath;

  @Option(names = "--groups", paramLabel = "<g1,g2>", split = ",",
      description = "Query subject groups for policy matching; repeatable and/or "
          + "comma-separated. Rewrite mode only.")
  private List<String> groups;
```

`--user` 的 description 改为：
```java
      description = "PostgreSQL user for --pull-metadata; the query subject user for "
          + "policy matching in rewrite mode.")
```

`execute(PrintStream, PrintStream)` 里 `pullMetadata` 分支最前面加：
```java
      if (groups != null && !groups.isEmpty()) {
        err.println("sql-mask: --groups cannot be combined with --pull-metadata");
        return 2;
      }
```
构造处改为：
```java
    CliOptions options = new CliOptions(metadataPath, policiesPath, user, groups,
        sql, inputPath, outputPath, dialect);
```
`SqlMaskServiceApplication` 的 `CLI_OPTIONS` 列表加入 `"--policies", "--groups"`。

3. `SqlMaskRunner.java`：

```java
  public String run(CliOptions options) {
    String metadataYaml = readUtf8(options.metadataPath(), "metadata file");
    String policyYaml = options.policiesPath() == null
        ? null
        : readUtf8(options.policiesPath(), "policies file");
    String sqlText = options.sql() != null
        ? options.sql()
        : readUtf8(options.inputPath(), "SQL input file");
    List<StatementRewrite> statements = new RewriteEngine().rewrite(
        metadataYaml, policyYaml, sqlText, options.dialect(),
        Subject.of(options.user(), options.groups()));
    return RewriteEngine.join(statements);
  }
```

（import：`io.sqlmask.policy.model.Subject`。）

- [ ] **Step 4: 运行 CLI 全部测试 + 全量回归**

Run: `mvn -q -pl mask-core -am test -Dtest='*Cli*,SqlMaskRunner*' && mvn test`
Expected: 全部通过（含既有 CLI 测试更新后的构造器）。

- [ ] **Step 5: Commit**

```bash
git add -A mask-core
git commit -m "feat(cli): --policies 与查询主体 --user 复用/--groups（按模式校验互斥）"
```

---

### Task 10: REST（rewrite 新字段 + /api/policies/parse）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/RewriteController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（`@SpringBootApplication(scanBasePackages = "io.sqlmask")`，让 policy 模块的 Controller 进入组合 jar 上下文）
- Modify: `mask-core/src/main/java/io/sqlmask/server/ApiExceptionHandler.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policy/server/PolicyController.java`
- Test: Modify `mask-core/src/test/java/io/sqlmask/server/RewriteControllerTest.java`；Create `mask-policy/src/test/java/io/sqlmask/policy/server/PolicyControllerTest.java`

**Interfaces:**
- Consumes: Task 8 引擎重载、Task 3 加载器、`PolicyException`。
- Produces:
  - `POST /api/rewrite` 请求体：`{ metadataYaml, policyYaml?, sql, dialect?, user?, groups? }`（`policyYaml` 非空时按 Task 8 互斥规则校验）。
  - `POST /api/policies/parse`：请求 `{ policyYaml }`；200 返回 `{ policies: [{ name, enabled, priority, type, resources: [{catalog, schema, table, column}], itemCount }] }`；400 返回 `{ code: "CONFIG_ERROR", message }`。

- [ ] **Step 1: 写失败测试（两个模块各一）**

mask-core `RewriteControllerTest` 追加（沿用该类既有的 MockMvc 设置风格）：

```java
  @Test
  void rewriteAcceptsPolicyYamlAndSubject() throws Exception {
    String metadata = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns: [{name: phone, type: varchar}]
        policies: {}
        """;
    String policies = """
        policies:
          - name: mask-phone
            resources:
              - {catalog: crm, schema: public, table: customer, column: phone}
            dataMaskItems:
              - {users: ["alice"], udf: mask_phone}
        """;
    // alice → masked
    mockMvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": " + quote(metadata)
                + ", \"policyYaml\": " + quote(policies)
                + ", \"sql\": \"SELECT phone FROM customer;\", \"user\": \"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(true));
    // 匿名 → 原样
    mockMvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": " + quote(metadata)
                + ", \"policyYaml\": " + quote(policies)
                + ", \"sql\": \"SELECT phone FROM customer;\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(false));
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n") + "\"";
  }
```

mask-policy `PolicyControllerTest`（不依赖 core，直接调方法）：

```java
package io.sqlmask.policy.server;

import io.sqlmask.policy.PolicyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyControllerTest {

  private final PolicyController controller = new PolicyController();

  @Test
  void parseReturnsStructuredSummary() {
    var response = controller.parse(new PolicyController.PolicyParseRequest("""
        policies:
          - name: mask-phone
            priority: 2
            resources:
              - {catalog: crm, schema: public, table: customer, column: phone}
            dataMaskItems:
              - {groups: ["*"], udf: mask_phone}
        """));
    assertEquals(1, response.policies().size());
    var dto = response.policies().get(0);
    assertEquals("mask-phone", dto.name());
    assertEquals(2, dto.priority());
    assertEquals("data_mask", dto.type());
    assertEquals(1, dto.itemCount());
    assertEquals("phone", dto.resources().get(0).column());
  }

  @Test
  void blankPayloadIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> controller.parse(new PolicyController.PolicyParseRequest(" ")));
    assertTrue(e.getMessage().contains("policyYaml is required"));
  }

  @Test
  void invalidPolicyYamlBubblesAsPolicyException() {
    assertThrows(PolicyException.class,
        () -> controller.parse(new PolicyController.PolicyParseRequest("policies: [ {")));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test`
Expected: 新增测试编译失败/断言失败（字段不存在、404）。

- [ ] **Step 3: 实现**

1. `PolicyController.java`（mask-policy）：

```java
package io.sqlmask.policy.server;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.store.PolicyYamlLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Policy YAML validation endpoint. Lives in mask-policy so the combined jar
 * (core's component scan) and a future standalone policy service expose the
 * same contract. Errors are PolicyException; the combined jar maps them via
 * ApiExceptionHandler to 400 CONFIG_ERROR.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();

  @PostMapping("/parse")
  public PolicyParseResponse parse(@RequestBody PolicyParseRequest request) {
    if (request == null || request.policyYaml() == null || request.policyYaml().isBlank()) {
      throw new PolicyException("policyYaml is required");
    }
    return toResponse(loader.parse(request.policyYaml(), "policies.yaml"));
  }

  private PolicyParseResponse toResponse(List<Policy> policies) {
    return new PolicyParseResponse(policies.stream().map(p -> new PolicyDto(
        p.name(), p.enabled(), p.priority(), p.type().name().toLowerCase(Locale.ROOT),
        p.resources().stream()
            .map(r -> new ResourceDto(r.catalog(), r.schema(), r.table(), r.column()))
            .toList(),
        p.dataMaskItems().size() + p.rowFilterItems().size())).toList());
  }

  public record PolicyParseRequest(String policyYaml) {
  }

  public record PolicyParseResponse(List<PolicyDto> policies) {
  }

  public record PolicyDto(String name, boolean enabled, int priority, String type,
      List<ResourceDto> resources, int itemCount) {
  }

  public record ResourceDto(String catalog, String schema, String table, String column) {
  }
}
```

2. `ApiExceptionHandler.java`（mask-core）追加：

```java
  /** Policy subsystem errors arrive as plain configuration errors. */
  @ExceptionHandler(io.sqlmask.policy.PolicyException.class)
  public ResponseEntity<ApiError> handlePolicy(io.sqlmask.policy.PolicyException e) {
    return ResponseEntity.badRequest().body(new ApiError("CONFIG_ERROR", e.getMessage()));
  }
```

（实现时改为正常 import。）

3. `RewriteController.java`：请求 record 与调用改为——

```java
  public record RewriteRequest(String metadataYaml, String policyYaml, String sql,
      String dialect, String user, java.util.List<String> groups) {
  }
```

```java
    List<StatementRewrite> statements = engine.rewrite(
        request.metadataYaml(), request.policyYaml(), request.sql(), dialect,
        io.sqlmask.policy.model.Subject.of(request.user(), request.groups()));
```

（import 正常化；`metadataYaml` / `sql` 的既有必填校验不动。）

4. `SqlMaskServiceApplication.java`：`@SpringBootApplication(scanBasePackages = "io.sqlmask")`。

- [ ] **Step 4: 全量回归**

Run: `mvn test`
Expected: 全部通过（两个模块）。

- [ ] **Step 5: Commit**

```bash
git add -A mask-core mask-policy
git commit -m "feat: /api/rewrite 主体与策略字段 + /api/policies/parse 校验端点（mask-policy 自带）"
```

---

### Task 11: 页面（主体输入 + 策略文件页签）

**Files:**
- Modify: `mask-core/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /api/policies/parse`（Task 10）、`POST /api/rewrite` 新字段（Task 10）。
- Produces: 页面支持编辑 policies.yaml、按主体执行改写。页面无自动化测试，靠本任务末尾的手动验收清单。

- [ ] **Step 1: 加页签与执行区输入**

在 `#tabs`（约 157 行）的「策略定义」按钮后加：

```html
      <button data-tab="policyfile">策略文件</button>
```

在 `panel-yaml` 面板后加：

```html
    <div class="tab-panel" id="panel-policyfile" hidden>
      <p class="hint">Ranger 式策略文件（policies.yaml）：按主体（users/groups）声明的列脱敏与行过滤。
        给出内容时，YAML 源码中的 policies / columns / rowFilter 必须为空。</p>
      <textarea id="policy-yaml" rows="14" spellcheck="false"
        placeholder="policies:&#10;  - name: mask-phone&#10;    resources:&#10;      - {catalog: crm, schema: public, table: customer, column: phone}&#10;    dataMaskItems:&#10;      - {groups: [&quot;*&quot;], udf: mask_phone, arguments: [3, 4]}"></textarea>
      <div class="row">
        <button id="policy-apply">校验并应用</button>
        <button id="policy-clear">清空</button>
      </div>
      <pre id="policy-result" class="hint"></pre>
    </div>
```

在右侧执行面板（`<section class="pane">` 内 SQL 输入附近，跟随现有 `.row` 布局）加：

```html
    <div class="row">
      <input id="run-user" placeholder="主体用户（可选）">
      <input id="run-groups" placeholder="组，逗号分隔（可选）">
    </div>
```

- [ ] **Step 2: 接线 JS**

在 `let state = {...}`（约 263 行）中加 `policyYaml: ""`。新增（跟随现有 `$` / `esc` 帮助函数风格）：

```js
const policyText = () => $("policy-yaml").value.trim();

$("policy-apply").addEventListener("click", async () => {
  const text = policyText();
  const result = $("policy-result");
  if (!text) { result.textContent = "策略文件为空：改写将只用左侧 YAML 的策略。"; state.policyYaml = ""; return; }
  const res = await fetch("/api/policies/parse", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ policyYaml: text })
  });
  const payload = await res.json();
  if (!res.ok) {
    result.textContent = "校验失败：" + payload.code + " — " + payload.message;
    return;
  }
  state.policyYaml = text;
  result.textContent = "已应用 " + payload.policies.length + " 条策略："
    + payload.policies.map((p) => p.name + " (" + p.type + ")").join(", ");
});

$("policy-clear").addEventListener("click", () => {
  $("policy-yaml").value = "";
  state.policyYaml = "";
  $("policy-result").textContent = "";
});
```

找到执行改写的 fetch 调用（搜索 `/api/rewrite`，约在 `runRewrite` 函数内），请求体扩为：

```js
    body: JSON.stringify({
      metadataYaml: generateYaml(),
      policyYaml: state.policyYaml || undefined,
      sql: sqlText,
      dialect: state.dialect,
      user: $("run-user").value.trim() || undefined,
      groups: $("run-groups").value.split(",").map((g) => g.trim()).filter(Boolean)
    })
```

（字段名以现有请求体为准，只新增 `policyYaml` / `user` / `groups` 三个键；`dialect` 等保留原样。）

- [ ] **Step 3: 手动验收清单**

启动 `mvn -pl mask-core -am package -DskipTests && java -jar mask-core/target/sql-mask.jar`，浏览器打开 `http://localhost:8080`，逐项确认：

1. 「策略文件」页签出现，可编辑；粘贴合法 policies.yaml 点「校验并应用」→ 显示策略摘要；
2. 粘贴非法 YAML → 显示 `校验失败：CONFIG_ERROR — …` 且不改变 state（后续改写不带该内容）；
3. 填入策略文件 + 不填主体 → 执行改写：仅 `groups: ["*"]` 策略生效；
4. 主体输入 `alice` → users: ["alice"] 的策略生效（结果卡片出现"已脱敏/已行过滤"标签）；
5. 同一配置删空策略文件后改写 → 行为与基线一致（旧 YAML 路径不受影响）；
6. 表结构/列策略/策略定义/YAML 源码各页签回归无异常。

- [ ] **Step 4: Commit**

```bash
git add mask-core/src/main/resources/static/index.html
git commit -m "feat(web): 策略文件页签与查询主体输入（/api/policies/parse 校验回显）"
```

---

### Task 12: README 与收尾验证

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: 全部已完成任务。
- Produces: 文档与代码一致；fat jar 冒烟通过。

- [ ] **Step 1: 更新 README（四处）**

1. 开头简介的模式描述后补一句：策略可来自 metadata.yaml（兼容旧格式）或独立的 Ranger 式 policies.yaml（主体 users/groups + 资源通配 + priority），二选一。
2. 新增「策略文件（policies.yaml）」章节：贴 spec §4.1 的 YAML 示例，并写明规则要点——items 二选一、主体必填（`*` 表所有人）、user > group > `*`、priority 高者优先同优先级按声明顺序、掩码单命中行过滤 AND 叠加、资源未命中声明表即报错、与 metadata 内嵌策略互斥。
3. 「CLI 用法」章节补三个参数：`--policies <path>`、`--user`（改写模式下为查询主体）、`--groups g1,g2`；`--groups` 不能与 `--pull-metadata` 同用（退出码 2）。
4. 「REST API」的 `/api/rewrite` 请求示例加 `"policyYaml"`, `"user"`, `"groups"` 字段说明，并新增 `POST /api/policies/parse` 小节（请求/响应/400 语义）。

- [ ] **Step 2: 全量测试**

Run: `mvn test`
Expected: `BUILD SUCCESS`，0 失败。

- [ ] **Step 3: 打包与 CLI 冒烟**

Run: `mvn -q -pl mask-core -am package -DskipTests && java -jar mask-core/target/sql-mask.jar --metadata <tmp>/m.yaml --policies <tmp>/p.yaml --user alice --sql "SELECT phone FROM customer;"`

用 Task 9 测试中的 METADATA / POLICIES 内容造临时文件。
Expected: 输出含 `mask_phone(` 的包装查询；同命令去掉 `--user` 后输出原查询（原样直通）。

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: 策略文件用法与主体参数（policies.yaml / --policies / --user / --groups / API）"
```
