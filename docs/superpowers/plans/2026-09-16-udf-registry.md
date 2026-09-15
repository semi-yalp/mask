# UDF 注册表实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在策略子系统内新增实例级 UDF 注册表（模型 + 存储 + 校验 + REST CRUD），让 DATAMASK 策略在写入时按签名做配置期校验。

**Architecture:** `UdfDefinition(name, signatures)` 挂在引擎实例下但独立于 `EngineInstance` record 存取（`PolicyStore` 新增 UDF CRUD，`instance_udf` 表）；`PolicyValidator.validatePolicy` 增加注册表参数做四步解析（存在性→参数个数→标量类型矩阵→列类型精确匹配）；`PolicyService` 提供守恒守卫（删/改 UDF 不得使启用中的策略失效）；REST 挂 mask-core web 应用。改写链路、YAML/CLI、`EffectiveConfigCompiler` 一行不动。

**Tech Stack:** Java 17 records、Spring Boot（MockMvc 测试）、Spring JdbcTemplate（`JdbcPolicyStore`）、Jackson。

**Spec:** `docs/superpowers/specs/2026-09-15-udf-registry-design.md`

## Global Constraints

- 不修改：YAML/CLI 配置路径、`UnknownFunctionTable`、`EffectiveConfigCompiler`、`mask-lite`、`mask-metadata`。
- 错误码只用现有两枚：`SqlMaskException.Code.CONFIG_ERROR`、`POLICY_INSTANCE_NOT_FOUND`（HTTP 侧由 `ApiExceptionHandler` 统一映射 400）。
- 每次 UDF 变更（create/replace/delete）在同一事务内推进实例 `config_version` +1。
- 首参约定：签名 `params[0]` 绑定被脱敏列的值，`params[1..]` 按序对应策略 `arguments`。
- 类型匹配为精确相等：`(sqlTypeName, precision, scale)` 三元组相等（经实例方言 `TypeResolver.parseColumn` 解析），无隐式转换。
- 标量矩阵：JSON number → SMALLINT/INTEGER/BIGINT/REAL/DOUBLE/DECIMAL；string → VARCHAR/CHAR；boolean → BOOLEAN；跨族拒绝。
- `policy.udf()` 与注册表按 `String.equals` 精确匹配（不做大小写归一）。
- UDF 名沿用 `PolicyValidator.NAME` 正则 `[A-Za-z0-9_.\-]+`。
- 测试命令在仓库根 `C:/Users/yhh/orca/mask` 下执行：`mvn -pl mask-core test -Dtest=<类名>`。
- 代码风格：2 空格缩进、records、javadoc 一句话起头（对照 `PolicyEntity.java`）。

---

### Task 1: UdfDefinition 模型与 UDF 写入校验

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/policyserver/model/UdfDefinition.java`
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`

**Interfaces:**
- Consumes: `DialectProfiles.byName(String).typeResolver()`（现有）、`TypeResolver.parseColumn(String, String)`（现有）、`PolicyValidator.error(...)` / `requireName(...)`（现有私有静态方法）。
- Produces: `record UdfDefinition(String name, List<UdfSignature> signatures)`、`record UdfDefinition.UdfSignature(List<String> params, String returns)`、`void PolicyValidator.validateUdf(EngineInstance instance, UdfDefinition udf)`（Task 3/4 依赖）。

- [ ] **Step 1: 写失败测试**

在 `PolicyValidatorTest` 追加（沿用文件已有的 `INSTANCE` fixture 与静态导入）：

```java
  // ---- UDF 写入校验 ----

  private static UdfDefinition udf(String name, UdfDefinition.UdfSignature... signatures) {
    return new UdfDefinition(name, List.of(signatures));
  }

  @Test
  void acceptsValidUdfDefinition() {
    assertDoesNotThrow(() -> validator.validateUdf(INSTANCE, udf("mask_phone",
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar"))));
  }

  @Test
  void rejectsUdfWithoutSignaturesOrParams() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("empty"))).getMessage().contains("at least one signature"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("no_params",
            new UdfDefinition.UdfSignature(List.of(), "varchar"))))
        .getMessage().contains("column-value parameter"));
  }

  @Test
  void rejectsUdfBadNameAndBadTypeDeclarations() {
    assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("bad name!",
            new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("strng", "integer"), "varchar"))))
        .getMessage().contains("strng"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("varchar"), "blob"))))
        .getMessage().contains("blob"));
  }

  @Test
  void rejectsDuplicateUdfSignature() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("varchar", "integer"), "varchar"),
            new UdfDefinition.UdfSignature(List.of("varchar", "integer"), "text"))))
        .getMessage().contains("duplicate signature"));
  }
```

顶部补 import：`import io.sqlmask.policyserver.model.UdfDefinition;`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: COMPILATION ERROR（`UdfDefinition` 不存在）。

- [ ] **Step 3: 写模型与校验**

新建 `UdfDefinition.java`：

```java
package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * A registered masking UDF of one engine instance: its name plus the overload
 * signatures installed in the engine (PG identifies functions by name +
 * argument types). The first parameter of every signature binds the masked
 * column's value; the remaining ones bind the policy's ordered scalar
 * arguments.
 */
public record UdfDefinition(String name, List<UdfSignature> signatures) {

  public UdfDefinition {
    signatures = List.copyOf(signatures);
  }

  /** One overload: ordered positional parameter type declarations and the return type. */
  public record UdfSignature(List<String> params, String returns) {

    public UdfSignature {
      params = List.copyOf(params);
    }
  }
}
```

`PolicyValidator` 追加（放在 `validatePolicy` 之后、`validateFilterExpression` 之前）：

```java
  /** Config-time gate for udf writes: names, non-empty signatures, dialect-valid
   * type declarations and signature deduplication. */
  public void validateUdf(EngineInstance instance, UdfDefinition udf) {
    requireName(udf.name(), "udf name");
    if (udf.signatures().isEmpty()) {
      throw error("udf '" + udf.name() + "' requires at least one signature");
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    Set<List<String>> seenParams = new LinkedHashSet<>();
    for (int i = 0; i < udf.signatures().size(); i++) {
      UdfDefinition.UdfSignature signature = udf.signatures().get(i);
      if (signature.params().isEmpty()) {
        throw error("udf '" + udf.name() + "' signature #" + i
            + " requires at least the column-value parameter");
      }
      for (String declaration : signature.params()) {
        requireParsableType(typeResolver, instance, udf.name(), declaration);
      }
      requireParsableType(typeResolver, instance, udf.name(), signature.returns());
      if (!seenParams.add(signature.params())) {
        throw error("udf '" + udf.name() + "': duplicate signature " + signature.params());
      }
    }
  }

  private static void requireParsableType(TypeResolver typeResolver, EngineInstance instance,
      String udfName, String declaration) {
    try {
      typeResolver.parseColumn(declaration, declaration);
    } catch (SqlMaskException | IllegalArgumentException e) {
      throw error("udf '" + udfName + "' in instance '" + instance.name()
          + "': invalid type declaration '" + declaration + "' (" + e.getMessage() + ")");
    }
  }
```

import 补：`import io.sqlmask.policyserver.model.UdfDefinition;`（`TypeResolver`/`DialectProfiles`/`Set`/`LinkedHashSet` 文件里已有则不重复）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: PASS（新增 4 个用例 + 既有用例全部通过）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/model/UdfDefinition.java \
        mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java \
        mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java
git commit -m "feat(policyserver): UdfDefinition 模型与 UDF 写入校验"
```

---

### Task 2: PolicyStore 接口扩展与 InMemory 实现

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/store/PolicyStore.java`
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/store/InMemoryPolicyStore.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `UdfDefinition`。
- Produces（`PolicyStore` 新增五方法，Task 4/5 依赖）:
  - `UdfDefinition createUdf(String instanceName, UdfDefinition udf)` — 重名 `CONFIG_ERROR`
  - `UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf)` — 名字不可变，未找到 `CONFIG_ERROR`
  - `Optional<UdfDefinition> findUdf(String instanceName, String udfName)` — 未知实例抛 `POLICY_INSTANCE_NOT_FOUND`
  - `List<UdfDefinition> listUdfs(String instanceName)` — 按名排序
  - `void deleteUdf(String instanceName, String udfName)` — 未找到 `CONFIG_ERROR`

- [ ] **Step 1: 写失败测试**

在 `InMemoryPolicyStoreTest` 追加（复用已有 `INSTANCE` fixture）：

```java
  // ---- UDF CRUD ----

  private static UdfDefinition udf(String name, String... params) {
    return new UdfDefinition(name, List.of(
        new UdfDefinition.UdfSignature(List.of(params), "varchar")));
  }

  @Test
  void udfCrudRoundTripAndVersionBumps() {
    store.createInstance(INSTANCE);
    assertEquals(1, store.currentVersion("pg_prod"));
    store.createUdf("pg_prod", udf("mask_phone", "varchar", "integer", "integer"));
    assertEquals(2, store.currentVersion("pg_prod"));
    assertEquals("mask_phone", store.findUdf("pg_prod", "mask_phone").orElseThrow().name());
    store.replaceUdf("pg_prod", "mask_phone",
        new UdfDefinition("mask_phone", List.of(
            new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"),
            new UdfDefinition.UdfSignature(List.of("bigint"), "varchar"))));
    assertEquals(2, store.findUdf("pg_prod", "mask_phone").orElseThrow().signatures().size());
    assertEquals(3, store.currentVersion("pg_prod"));
    assertEquals(1, store.listUdfs("pg_prod").size());
    store.deleteUdf("pg_prod", "mask_phone");
    assertTrue(store.findUdf("pg_prod", "mask_phone").isEmpty());
    assertEquals(4, store.currentVersion("pg_prod"));
  }

  @Test
  void udfErrorsFollowStoreContract() {
    store.createInstance(INSTANCE);
    store.createUdf("pg_prod", udf("mask_phone", "varchar"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.createUdf("pg_prod", udf("mask_phone", "varchar"))).getCode());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.replaceUdf("pg_prod", "mask_phone", udf("renamed", "varchar")))
            .getCode());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.deleteUdf("pg_prod", "nope")).getCode());
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> store.createUdf("nope", udf("u", "varchar")))
            .getCode());
    assertEquals(2, store.currentVersion("pg_prod")); // 失败变更不推进版本
  }
```

顶部补 import：`io.sqlmask.policyserver.model.UdfDefinition`、`org.junit.jupiter.api.Assertions.assertTrue`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=InMemoryPolicyStoreTest`
Expected: COMPILATION ERROR（接口无 `createUdf` 等方法）。

- [ ] **Step 3: 实现**

`PolicyStore` 接口追加（`deletePolicy` 声明之后、`currentVersion` 之前）：

```java
  /** Registers a udf definition; a duplicate name within the instance is a {@code CONFIG_ERROR}. */
  UdfDefinition createUdf(String instanceName, UdfDefinition udf);

  /** Replaces the definition stored under {@code udfName} (the name is immutable). */
  UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf);

  /** Looks up one udf definition; empty for an unknown name. */
  Optional<UdfDefinition> findUdf(String instanceName, String udfName);

  /** All udf definitions of the instance ordered by name. */
  List<UdfDefinition> listUdfs(String instanceName);

  /** Removes one udf definition; an unknown name is a {@code CONFIG_ERROR}. */
  void deleteUdf(String instanceName, String udfName);
```

import 补 `io.sqlmask.policyserver.model.UdfDefinition`。

`InMemoryPolicyStore` 追加：新字段 `private final Map<String, Map<String, UdfDefinition>> udfsByInstance = new LinkedHashMap<>();`；`createInstance` 里 `udfsByInstance.put(instance.name(), new LinkedHashMap<>());`；`deleteInstance` 里 `udfsByInstance.remove(name);`；新增方法：

```java
  @Override
  public synchronized UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    requireInstance(instanceName);
    Map<String, UdfDefinition> udfs = udfsByInstance.get(instanceName);
    if (udfs.containsKey(udf.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udf.name() + "' already exists in instance '" + instanceName + "'");
    }
    udfs.put(udf.name(), udf);
    bump(instanceName);
    return udf;
  }

  @Override
  public synchronized UdfDefinition replaceUdf(String instanceName, String udfName,
      UdfDefinition udf) {
    requireInstance(instanceName);
    if (!udf.name().equals(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "udf name mismatch: '"
          + udfName + "' cannot be renamed to '" + udf.name() + "'");
    }
    Map<String, UdfDefinition> udfs = udfsByInstance.get(instanceName);
    if (!udfs.containsKey(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udfName + "' not found in instance '" + instanceName + "'");
    }
    udfs.put(udfName, udf);
    bump(instanceName);
    return udf;
  }

  @Override
  public synchronized Optional<UdfDefinition> findUdf(String instanceName, String udfName) {
    requireInstance(instanceName);
    return Optional.ofNullable(udfsByInstance.get(instanceName).get(udfName));
  }

  @Override
  public synchronized List<UdfDefinition> listUdfs(String instanceName) {
    requireInstance(instanceName);
    return udfsByInstance.get(instanceName).values().stream()
        .sorted(java.util.Comparator.comparing(UdfDefinition::name))
        .toList();
  }

  @Override
  public synchronized void deleteUdf(String instanceName, String udfName) {
    requireInstance(instanceName);
    if (udfsByInstance.get(instanceName).remove(udfName) == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udfName + "' not found in instance '" + instanceName + "'");
    }
    bump(instanceName);
  }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-core test -Dtest=InMemoryPolicyStoreTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/store/PolicyStore.java \
        mask-core/src/main/java/io/sqlmask/policyserver/store/InMemoryPolicyStore.java \
        mask-core/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java
git commit -m "feat(policyserver): PolicyStore UDF CRUD 与 InMemory 实现"
```

---

### Task 3: 策略校验接入注册表（四步解析）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`（新增矩阵用例 + 既有用例改传注册表）

**Interfaces:**
- Consumes: Task 1 的 `UdfDefinition`。
- Produces:
  - `validatePolicy` 签名变更为 `validatePolicy(EngineInstance instance, List<UdfDefinition> udfs, PolicyEntity policy, List<PolicyEntity> otherEnabledPolicies)`（Task 4 依赖）
  - `List<String> policiesFailingUdfResolution(EngineInstance instance, List<UdfDefinition> udfs, List<PolicyEntity> policies)`（Task 4 守恒守卫依赖；返回启用中且 udf 引用不再可解析的 DATAMASK 策略名）

- [ ] **Step 1: 更新既有测试并写失败测试**

`PolicyValidatorTest` 顶部加注册表 fixture（放在 `INSTANCE` 之后）：

```java
  private static final List<UdfDefinition> UDFS = List.of(
      new UdfDefinition("mask_phone", List.of(
          new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
          new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar"))),
      new UdfDefinition("mask_ssn", List.of(
          new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
```

文件内**所有** `validator.validatePolicy(INSTANCE, ...)` 调用改为 `validator.validatePolicy(INSTANCE, UDFS, ...)`。

追加矩阵用例：

```java
  // ---- DATAMASK 策略 udf 四步解析 ----

  @Test
  void rejectsUnknownUdfName() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_nonexistent", List.of(3, 4), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("unknown udf 'mask_nonexistent'"));
  }

  @Test
  void rejectsArityMismatch() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_ssn", List.of(1), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("mask_ssn"));
  }

  @Test
  void rejectsCrossFamilyScalarArgument() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of("abc", 4), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("argument"));
  }

  @Test
  void rejectsColumnWithoutMatchingOverload() {
    // status 是 varchar 但 id 在 fixture 里也是 varchar；借 bigint 列构造失配：
    // phone(varchar) 有重载，改为选 id 列并把策略指到 mask_ssn（1 参签名）不行——
    // 用一个 bigint 列的实例直接验证。
    EngineInstance bigintInstance = new EngineInstance("pg_b", "postgresql",
        List.of(new TableDef("crm", "public", "ledger", List.of(
            new ColumnDef("acct", "bigint")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "ledger", List.of("acct")),
        "mask_ssn", List.of(), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(bigintInstance, UDFS, p, List.of()))
        .getMessage().contains("column 'acct'"));
  }

  @Test
  void resolvesOverloadPerColumn() {
    // 一条策略同时选 varchar 列（走第一签名）与 bigint 列（走第二签名）。
    EngineInstance mixed = new EngineInstance("pg_m", "postgresql",
        List.of(new TableDef("crm", "public", "t", List.of(
            new ColumnDef("phone", "varchar"), new ColumnDef("uid", "bigint")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "t", List.of("phone", "uid")),
        "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() -> validator.validatePolicy(mixed, UDFS, p, List.of()));
  }

  @Test
  void rejectsPrecisionMismatchAsNoOverload() {
    // varchar(10) ≠ varchar（precision 参与精确匹配）。
    EngineInstance sized = new EngineInstance("pg_s", "postgresql",
        List.of(new TableDef("crm", "public", "t", List.of(
            new ColumnDef("phone", "varchar(10)")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "t", List.of("phone")),
        "mask_ssn", List.of(), null);
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(sized, UDFS, p, List.of()));
  }

  @Test
  void policiesFailingUdfResolutionNamesEnabledDatamaskOnly() {
    PolicyEntity enabledMask = datamask("phone_mask", "customer", List.of("phone"));
    PolicyEntity disabledMask = new PolicyEntity("off", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null);
    PolicyEntity rowFilter = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        null, List.of(), "status = 'active'");
    // 空注册表下只有 enabled 的 datamask 失效。
    assertEquals(List.of("phone_mask"),
        validator.policiesFailingUdfResolution(INSTANCE, List.of(),
            List.of(enabledMask, disabledMask, rowFilter)));
    // 注册表齐备时无人失效。
    assertTrue(validator.policiesFailingUdfResolution(INSTANCE, UDFS,
        List.of(enabledMask, disabledMask, rowFilter)).isEmpty());
  }
```

import 补：`static org.junit.jupiter.api.Assertions.assertEquals;`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: COMPILATION ERROR（`validatePolicy` 无 4 参重载）。

- [ ] **Step 3: 实现校验**

`PolicyValidator` 改动：

1. `validatePolicy` 签名加参数 `List<UdfDefinition> udfs`（放 `instance` 之后）；
2. `DATAMASK` 分支末尾（既有列存在性循环之后）追加：

```java
        String udfError = udfResolutionError(instance, udfs, policy);
        if (udfError != null) {
          throw error(udfError);
        }
```

3. 新增方法（放 `validateFilterExpression` 之后）：

```java
  /**
   * Udf-removal guard input: names of enabled DATAMASK policies whose udf
   * reference no longer resolves against the given registry.
   */
  public List<String> policiesFailingUdfResolution(EngineInstance instance,
      List<UdfDefinition> udfs, List<PolicyEntity> policies) {
    List<String> failing = new ArrayList<>();
    for (PolicyEntity policy : policies) {
      if (!policy.enabled() || policy.policyType() != PolicyType.DATAMASK) {
        continue;
      }
      if (udfResolutionError(instance, udfs, policy) != null) {
        failing.add(policy.name());
      }
    }
    return failing;
  }

  /** Null when the policy's udf reference resolves against the registry. */
  private String udfResolutionError(EngineInstance instance, List<UdfDefinition> udfs,
      PolicyEntity policy) {
    UdfDefinition udf = udfs.stream()
        .filter(u -> u.name().equals(policy.udf())).findFirst().orElse(null);
    if (udf == null) {
      return "policy '" + policy.name() + "': unknown udf '" + policy.udf()
          + "' in instance '" + instance.name() + "'";
    }
    int expectedParams = policy.arguments().size() + 1;
    List<UdfDefinition.UdfSignature> byArity = udf.signatures().stream()
        .filter(s -> s.params().size() == expectedParams).toList();
    if (byArity.isEmpty()) {
      return "policy '" + policy.name() + "': udf '" + udf.name() + "' declares signatures "
          + signatureShapes(udf) + " but the policy binds 1 column value + "
          + policy.arguments().size() + " argument(s)";
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    List<UdfDefinition.UdfSignature> byScalarTypes = byArity.stream()
        .filter(s -> argumentsMatch(typeResolver, s, policy.arguments())).toList();
    if (byScalarTypes.isEmpty()) {
      return "policy '" + policy.name() + "': argument types " + describeScalars(policy.arguments())
          + " match no arity-" + expectedParams + " " + udf.name() + " signature";
    }
    TableMetadata target = findTable(instance, policy);
    for (String columnName : policy.resource().columns()) {
      TableMetadata.Column column = target.columns().stream()
          .filter(c -> ColumnKey.normalize(c.name(), "column")
              .equals(ColumnKey.normalize(columnName, "column")))
          .findFirst().orElse(null);
      if (column == null) {
        return "policy '" + policy.name() + "': unknown column '" + columnName + "'";
      }
      boolean overloadExists = byScalarTypes.stream()
          .anyMatch(s -> sameType(typeResolver, s.params().get(0), column));
      if (!overloadExists) {
        return "policy '" + policy.name() + "': column '" + columnName + "' ("
            + column.typeDeclaration() + ") has no matching " + udf.name()
            + " overload; declare a signature whose first parameter is "
            + column.typeDeclaration();
      }
    }
    return null;
  }

  private static boolean argumentsMatch(TypeResolver typeResolver,
      UdfDefinition.UdfSignature signature, List<Object> arguments) {
    for (int i = 0; i < arguments.size(); i++) {
      TableMetadata.Column param = typeResolver.parseColumn("p", signature.params().get(i + 1));
      if (!scalarFits(arguments.get(i), param)) {
        return false;
      }
    }
    return true;
  }

  /** Strict scalar matrix — no cross-family coercion (spec §4.2). */
  private static boolean scalarFits(Object scalar, TableMetadata.Column param) {
    if (scalar instanceof Number) {
      return param.sqlTypeName() == SqlTypeName.SMALLINT
          || param.sqlTypeName() == SqlTypeName.INTEGER
          || param.sqlTypeName() == SqlTypeName.BIGINT
          || param.sqlTypeName() == SqlTypeName.REAL
          || param.sqlTypeName() == SqlTypeName.DOUBLE
          || param.sqlTypeName() == SqlTypeName.DECIMAL;
    }
    if (scalar instanceof Boolean) {
      return param.sqlTypeName() == SqlTypeName.BOOLEAN;
    }
    if (scalar instanceof String) {
      return param.sqlTypeName() == SqlTypeName.VARCHAR || param.sqlTypeName() == SqlTypeName.CHAR;
    }
    return false;
  }

  /** Exact match on the parsed triple — declarations only normalize spellings. */
  private static boolean sameType(TypeResolver typeResolver, String declaration,
      TableMetadata.Column column) {
    TableMetadata.Column parsed = typeResolver.parseColumn("x", declaration);
    return parsed.sqlTypeName() == column.sqlTypeName()
        && Objects.equals(parsed.precision(), column.precision())
        && Objects.equals(parsed.scale(), column.scale());
  }

  private static String signatureShapes(UdfDefinition udf) {
    return udf.signatures().stream()
        .map(s -> "(" + String.join(", ", s.params()) + ")")
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String describeScalars(List<Object> arguments) {
    return arguments.stream().map(scalar -> scalar instanceof Number ? "number"
        : scalar instanceof Boolean ? "boolean"
        : scalar instanceof String ? "string" : String.valueOf(scalar))
        .collect(Collectors.joining(", ", "[", "]"));
  }
```

import 补：`io.sqlmask.metadata.TableMetadata`、`org.apache.calcite.sql.type.SqlTypeName`、`java.util.Objects`、`java.util.stream.Collectors`（`ArrayList` 已有）。

- [ ] **Step 4: 跑测试确认通过（此时 PolicyServiceTest 会编译失败——属预期，Task 4 修复）**

Run: `mvn -pl mask-core test -Dtest=PolicyValidatorTest`
Expected: PASS。

Run: `mvn -pl mask-core test -Dtest=PolicyServiceTest`
Expected: COMPILATION ERROR（PolicyService 仍调旧签名）——**不要在本任务修**，Task 4 一并接线。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/PolicyValidator.java \
        mask-core/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java
git commit -m "feat(policyserver): DATAMASK 策略按 UDF 注册表四步解析校验"
```

注：本任务提交后 `PolicyServiceTest` 编译不过属中间态；若执行者在意每个提交可编译，可把本任务与 Task 4 合并为一次提交序列（先本文件后接线，最后一次 commit）。推荐做法：本任务改动 `PolicyService.createPolicy/updatePolicy` 的调用点同步改成新签名（一行改动 ×2），使全仓可编译——改动内容见 Task 4 Step 3 第一条。

---

### Task 4: PolicyService UDF 方法、守恒守卫与编译回归

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/PolicyService.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 store 五方法、Task 3 的 `validateUdf` / `validatePolicy`（4 参）/ `policiesFailingUdfResolution`。
- Produces（Task 6 REST 依赖）:
  - `UdfDefinition createUdf(String instanceName, UdfDefinition udf)`
  - `UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf)`
  - `Optional<UdfDefinition> udf(String instanceName, String udfName)`
  - `List<UdfDefinition> udfs(String instanceName)`
  - `void deleteUdf(String instanceName, String udfName)`

- [ ] **Step 1: 更新既有测试 + 写失败测试**

`PolicyServiceTest` 顶部加 fixture：

```java
  private static final UdfDefinition MASK_PHONE = new UdfDefinition("mask_phone", List.of(
      new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")));
```

两个既有测试补注册（`createInstance` 之后、`createPolicy` 之前各插一行）：

```java
    service.createUdf("pg_prod", MASK_PHONE);
```

（`updateMetadataRejectsRemovingReferencedTable` 与 `effectiveReturnsCompiledResponse` 都要；后者策略是 `arguments [3, 4]`，故其注册换成三参签名：

```java
    service.createUdf("pg_prod", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"))));
```

）

新增用例：

```java
  // ---- UDF 服务面 ----

  @Test
  void createPolicyRejectsUnregisteredUdf() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "mask_phone", List.of(), null)));
    assertTrue(e.getMessage().contains("unknown udf 'mask_phone'"));
  }

  @Test
  void deleteUdfBlockedWhileEnabledPolicyReferencesIt() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    SqlMaskException blocked = assertThrows(SqlMaskException.class,
        () -> service.deleteUdf("pg_prod", "mask_phone"));
    assertTrue(blocked.getMessage().contains("disable them first"));
    // 替换为不兼容签名同样被拒
    SqlMaskException replaced = assertThrows(SqlMaskException.class,
        () -> service.replaceUdf("pg_prod", "mask_phone", new UdfDefinition("mask_phone",
            List.of(new UdfDefinition.UdfSignature(List.of("bigint"), "varchar")))));
    assertTrue(replaced.getMessage().contains("disable them first"));
    // 禁用后放行
    service.updatePolicy("pg_prod", "p", new PolicyEntity("p", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    service.deleteUdf("pg_prod", "mask_phone");
    assertTrue(service.udf("pg_prod", "mask_phone").isEmpty());
  }

  @Test
  void udfRegistrationDoesNotChangeEffectiveConfig() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    var before = service.effective("pg_prod");
    long versionBefore = before.configVersion();
    service.replaceUdf("pg_prod", "mask_phone", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("text"), "varchar"))));
    var after = service.effective("pg_prod");
    assertEquals(before.config(), after.config());
    assertEquals(versionBefore + 1, after.configVersion());
  }
```

import 补：`io.sqlmask.policyserver.model.UdfDefinition`。

**命名说明**：`EffectiveConfigResponse` 已有嵌套记录 `UdfDefinition(String udf, List<Object> arguments)`（编译产物的策略绑定形态）。它与新建的顶层 `policyserver.model.UdfDefinition(name, signatures)` 语义不同但同名——嵌套记录只能以 `EffectiveConfigResponse.UdfDefinition` 限定形式使用，永不裸 import，编译不冲突；新增代码中引用注册表一律 import 顶层类。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyServiceTest`
Expected: COMPILATION ERROR（`createUdf` 等不存在）。

- [ ] **Step 3: 实现**

`PolicyService`：

1. `createPolicy` / `updatePolicy` 把校验调用改为：

```java
    validator.validatePolicy(instance, store.listUdfs(instanceName), policy,
        enabledOthers(instanceName, null));      // createPolicy；updatePolicy 传 policyName
```

2. 新增方法（放 `policies(...)` 之后、`effective(...)` 之前）：

```java
  public UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    EngineInstance instance = requireInstance(instanceName);
    validator.validateUdf(instance, udf);
    return store.createUdf(instanceName, udf);
  }

  public UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf) {
    requireInstance(instanceName);
    if (!udf.name().equals(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "udf name mismatch: '"
          + udfName + "' cannot be renamed to '" + udf.name() + "'");
    }
    validator.validateUdf(requireInstance(instanceName), udf);
    ensureEnabledPoliciesResolveUdfs(instanceName, withReplaced(store.listUdfs(instanceName), udf));
    return store.replaceUdf(instanceName, udfName, udf);
  }

  public Optional<UdfDefinition> udf(String instanceName, String udfName) {
    requireInstance(instanceName);
    return store.findUdf(instanceName, udfName);
  }

  public List<UdfDefinition> udfs(String instanceName) {
    requireInstance(instanceName);
    return store.listUdfs(instanceName);
  }

  public void deleteUdf(String instanceName, String udfName) {
    requireInstance(instanceName);
    ensureEnabledPoliciesResolveUdfs(instanceName,
        store.listUdfs(instanceName).stream()
            .filter(u -> !u.name().equals(udfName)).toList());
    store.deleteUdf(instanceName, udfName);
  }

  /**
   * Udf-removal guard, mirroring {@link #ensureEnabledPoliciesResolve}: enabled
   * DATAMASK policies must still resolve against the registry after the change
   * (disabled ones may dangle — they are re-validated if ever re-enabled).
   */
  private void ensureEnabledPoliciesResolveUdfs(String instanceName, List<UdfDefinition> udfsAfter) {
    List<String> dangling = validator.policiesFailingUdfResolution(
        requireInstance(instanceName), udfsAfter, store.listPolicies(instanceName));
    if (!dangling.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot change udfs of instance '" + instanceName + "': enabled policies " + dangling
              + " reference udf signatures that no longer resolve; disable them first");
    }
  }

  private static List<UdfDefinition> withReplaced(List<UdfDefinition> udfs, UdfDefinition udf) {
    return udfs.stream().map(u -> u.name().equals(udf.name()) ? udf : u).toList();
  }
```

import 补：`io.sqlmask.policyserver.model.UdfDefinition`、`java.util.Optional`。

- [ ] **Step 4: 全模块回归**

Run: `mvn -pl mask-core test`
Expected: PASS（含 Task 1–3 全部用例）。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/java/io/sqlmask/policyserver/PolicyService.java \
        mask-core/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java
git commit -m "feat(policyserver): UDF 服务面与删改守恒守卫（启用策略引用不可断）"
```

---

### Task 5: schema.sql 与 JdbcPolicyStore

**Files:**
- Modify: `mask-core/src/main/resources/schema.sql`
- Modify: `mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java`
- Test: `mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreIT.java`

**Interfaces:**
- Consumes: Task 2 的 `PolicyStore` 新五方法（本类 implements 必须补齐）。
- Produces: `instance_udf` 表；JDBC 语义与 InMemory 一致（含 `config_version` 同事务推进）。

- [ ] **Step 1: 写失败测试**

`JdbcPolicyStoreIT` 追加（该 IT 仅在 `POLICY_PG_URL` 环境变量存在时运行；沿用文件已有 `instanceName()` / `instance()` helper）：

```java
  // ---- UDF CRUD（镜像 InMemory 场景） ----

  private static UdfDefinition udf() {
    return new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar")));
  }

  @Test
  void udfCrudRoundTripAndVersionBumps() {
    store.createInstance(instance());
    long v1 = store.currentVersion(instanceName());
    store.createUdf(instanceName(), udf());
    assertEquals(v1 + 1, store.currentVersion(instanceName()));
    UdfDefinition loaded = store.findUdf(instanceName(), "mask_phone").orElseThrow();
    assertEquals(2, loaded.signatures().size());
    assertEquals(List.of("varchar", "integer", "integer"), loaded.signatures().get(0).params());
    assertEquals("bigint", loaded.signatures().get(1).params().get(0));
    store.replaceUdf(instanceName(), "mask_phone", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    assertEquals(1, store.findUdf(instanceName(), "mask_phone").orElseThrow().signatures().size());
    assertEquals(v1 + 2, store.currentVersion(instanceName()));
    assertEquals(1, store.listUdfs(instanceName()).size());
    store.deleteUdf(instanceName(), "mask_phone");
    assertTrue(store.findUdf(instanceName(), "mask_phone").isEmpty());
    assertEquals(v1 + 3, store.currentVersion(instanceName()));
  }

  @Test
  void udfDuplicateAndMissingFollowErrorContract() {
    store.createInstance(instance());
    store.createUdf(instanceName(), udf());
    assertThrows(SqlMaskException.class,
        () -> store.createUdf(instanceName(), udf()));
    assertThrows(SqlMaskException.class,
        () -> store.deleteUdf(instanceName(), "nope"));
    assertThrows(SqlMaskException.class,
        () -> store.replaceUdf(instanceName(), "mask_phone",
            new UdfDefinition("renamed", List.of(
                new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")))));
  }
```

import 补：`io.sqlmask.policyserver.model.UdfDefinition`、`static org.junit.jupiter.api.Assertions.assertTrue`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=JdbcPolicyStoreIT`（无 `POLICY_PG_URL` 时静默跳过——编译期就会因缺方法失败，足够作为红灯）
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现**

`schema.sql` 末尾追加：

```sql
CREATE TABLE IF NOT EXISTS instance_udf (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  param_types TEXT NOT NULL,
  return_type VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name, param_types)
);
```

`JdbcPolicyStore` 追加：

```java
  @Override
  @Transactional
  public UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (udfExists(instanceId, udf.name())) {
      throw duplicateUdf(udf.name(), instanceName);
    }
    try {
      insertUdfSignatures(instanceId, udf);
    } catch (DuplicateKeyException e) {
      throw duplicateUdf(udf.name(), instanceName);
    }
    bumpVersion(instanceName);
    return udf;
  }

  @Override
  @Transactional
  public UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (!udf.name().equals(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "udf name mismatch: '"
          + udfName + "' cannot be renamed to '" + udf.name() + "'");
    }
    if (!udfExists(instanceId, udfName)) {
      throw missingUdf(udfName, instanceName);
    }
    jdbc.update("DELETE FROM instance_udf WHERE instance_id = ? AND name = ?", instanceId, udfName);
    insertUdfSignatures(instanceId, udf);
    bumpVersion(instanceName);
    return udf;
  }

  @Override
  public Optional<UdfDefinition> findUdf(String instanceName, String udfName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return loadUdfs(instanceId, udfName).stream().findFirst();
  }

  @Override
  public List<UdfDefinition> listUdfs(String instanceName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return loadUdfs(instanceId, null);
  }

  @Override
  @Transactional
  public void deleteUdf(String instanceName, String udfName) {
    long instanceId = requireInstanceRow(instanceName).id();
    int deleted = jdbc.update("DELETE FROM instance_udf WHERE instance_id = ? AND name = ?",
        instanceId, udfName);
    if (deleted == 0) {
      throw missingUdf(udfName, instanceName);
    }
    bumpVersion(instanceName);
  }

  private boolean udfExists(long instanceId, String udfName) {
    return !jdbc.queryForList("SELECT id FROM instance_udf WHERE instance_id = ? AND name = ?",
        Long.class, instanceId, udfName).isEmpty();
  }

  private void insertUdfSignatures(long instanceId, UdfDefinition udf) {
    for (int i = 0; i < udf.signatures().size(); i++) {
      UdfDefinition.UdfSignature signature = udf.signatures().get(i);
      jdbc.update("INSERT INTO instance_udf (instance_id, name, param_types, return_type, position)"
              + " VALUES (?, ?, ?, ?, ?)",
          instanceId, udf.name(), toJson(signature.params()), signature.returns(), i);
    }
  }

  private List<UdfDefinition> loadUdfs(long instanceId, String onlyName) {
    StringBuilder sql = new StringBuilder(
        "SELECT name, param_types, return_type FROM instance_udf WHERE instance_id = ?");
    List<Object> args = new ArrayList<>(List.of(instanceId));
    if (onlyName != null) {
      sql.append(" AND name = ?");
      args.add(onlyName);
    }
    sql.append(" ORDER BY name, position");
    Map<String, List<UdfDefinition.UdfSignature>> byName = new LinkedHashMap<>();
    jdbc.query(sql.toString(), (rs, n) -> {
      byName.computeIfAbsent(rs.getString("name"),
              k -> new ArrayList<>())
          .add(new UdfDefinition.UdfSignature(stringsFrom(rs.getString("param_types")),
              rs.getString("return_type")));
      return null;
    }, args.toArray());
    return byName.entrySet().stream()
        .map(e -> new UdfDefinition(e.getKey(), List.copyOf(e.getValue())))
        .collect(java.util.stream.Collectors.toList());
  }

  private List<String> stringsFrom(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize udf param types: " + e.getMessage());
    }
  }

  private SqlMaskException duplicateUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' already exists in instance '" + instanceName + "'");
  }

  private SqlMaskException missingUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' not found in instance '" + instanceName + "'");
  }
```

import 补：`io.sqlmask.policyserver.model.UdfDefinition`、`java.util.LinkedHashMap`、`java.util.Map`。

- [ ] **Step 4: 跑测试**

Run: `mvn -pl mask-core test -Dtest=JdbcPolicyStoreIT`（本地有 PG 时：`POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/sqlmask_policy mvn ...`，可由 `docker-compose.metadata.yml` 的 postgres 提供）
Expected: 无 env 时 SKIPPED；有 env 时 PASS。随后 `mvn -pl mask-core test` 全绿。

- [ ] **Step 5: 提交**

```bash
git add mask-core/src/main/resources/schema.sql \
        mask-core/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java \
        mask-core/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreIT.java
git commit -m "feat(policyserver): instance_udf 表与 JdbcPolicyStore UDF CRUD"
```

---

### Task 6: REST 端点、Spring 装配与文档回写

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/UdfController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/UdfEndpointTest.java`
- Modify: `README.md`（UDF 注册表小节）
- Modify: `docs/superpowers/specs/2026-09-15-udf-registry-design.md`（状态行）

**Interfaces:**
- Consumes: Task 4 的 `PolicyService` 五方法、既有 `ApiExceptionHandler`（`SqlMaskException`→400 + code/message JSON）。
- Produces: REST 面 `POST/GET /api/instances/{instance}/udfs`、`GET/PUT/DELETE /api/instances/{instance}/udfs/{name}`；Spring beans `PolicyStore`（InMemory）/`PolicyValidator`/`PolicyService`。

- [ ] **Step 1: 写失败测试**

新建 `UdfEndpointTest`：

```java
package io.sqlmask.server;

import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
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

/** UDF registry CRUD over REST, backed by the in-memory policy store bean. */
@SpringBootTest
@AutoConfigureMockMvc
class UdfEndpointTest {

  private static final String BODY = """
      {
        "name": "mask_phone",
        "signatures": [
          {"params": ["varchar", "integer", "integer"], "returns": "varchar"},
          {"params": ["bigint", "integer", "integer"], "returns": "varchar"}
        ]
      }
      """;

  private static final String DUP_BODY = """
      {"name": "dup_check", "signatures": [{"params": ["varchar"], "returns": "varchar"}]}
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUpInstance() {
    try {
      service.createInstance("pg_prod", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    } catch (io.sqlmask.error.SqlMaskException alreadyExists) {
      // 上下文复用时实例已存在
    }
    try {
      service.deleteUdf("pg_prod", "mask_phone");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 清理上一个用例的遗留
    }
  }

  @Test
  void udfCrudRoundTrip() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("mask_phone"))
        .andExpect(jsonPath("$.signatures.length()").value(2));

    mvc.perform(get("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signatures[0].params[0]").value("varchar"));

    mvc.perform(get("/api/instances/pg_prod/udfs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    mvc.perform(put("/api/instances/pg_prod/udfs/mask_phone")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signatures.length()").value(1));

    mvc.perform(delete("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void unknownInstanceAndDuplicateFollowErrorContract() throws Exception {
    mvc.perform(post("/api/instances/nope/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));

    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(DUP_BODY))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(DUP_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"bad\",\"signatures\":"
                + "[{\"params\":[\"strng\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-core test -Dtest=UdfEndpointTest`
Expected: FAILURE（404，无 controller/bean：`PolicyService` bean 不存在导致上下文启动失败或请求 404）。

- [ ] **Step 3: 实现 controller 与装配**

`SqlMaskServiceApplication` 追加 beans（import `io.sqlmask.policyserver.PolicyService`、`PolicyValidator`、`store.InMemoryPolicyStore`、`store.PolicyStore`）：

```java
  @Bean
  PolicyStore policyStore() {
    return new InMemoryPolicyStore();
  }

  @Bean
  PolicyValidator policyValidator() {
    return new PolicyValidator();
  }

  @Bean
  PolicyService policyService(PolicyStore store, PolicyValidator validator) {
    return new PolicyService(store, validator);
  }
```

新建 `UdfController.java`：

```java
package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * UDF registry CRUD of one engine instance — the policy service's first admin
 * surface. Errors flow through {@link ApiExceptionHandler} (SqlMaskException
 * → 400 + code/message).
 */
@RestController
@RequestMapping("/api/instances/{instance}/udfs")
public class UdfController {

  public record UdfSignatureDto(List<String> params, String returns) {
  }

  public record UdfDto(String name, List<UdfSignatureDto> signatures) {
  }

  private final PolicyService service;

  public UdfController(PolicyService service) {
    this.service = service;
  }

  @PostMapping
  public UdfDto create(@PathVariable String instance, @RequestBody UdfDto request) {
    return toDto(service.createUdf(instance, toModel(request)));
  }

  @GetMapping
  public List<UdfDto> list(@PathVariable String instance) {
    return service.udfs(instance).stream().map(UdfController::toDto).toList();
  }

  @GetMapping("/{name}")
  public UdfDto get(@PathVariable String instance, @PathVariable String name) {
    return service.udf(instance, name).map(UdfController::toDto)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "udf '" + name + "' not found in instance '" + instance + "'"));
  }

  @PutMapping("/{name}")
  public UdfDto replace(@PathVariable String instance, @PathVariable String name,
      @RequestBody UdfDto request) {
    return toDto(service.replaceUdf(instance, name, toModel(request)));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable String instance, @PathVariable String name) {
    service.deleteUdf(instance, name);
  }

  private static UdfDefinition toModel(UdfDto dto) {
    return new UdfDefinition(dto.name(), dto.signatures() == null ? List.of()
        : dto.signatures().stream()
            .map(s -> new UdfDefinition.UdfSignature(
                s.params() == null ? List.of() : s.params(), s.returns()))
            .toList());
  }

  private static UdfDto toDto(UdfDefinition udf) {
    return new UdfDto(udf.name(), udf.signatures().stream()
        .map(s -> new UdfSignatureDto(s.params(), s.returns()))
        .toList());
  }
}
```

- [ ] **Step 4: 跑测试与全模块回归**

Run: `mvn -pl mask-core test -Dtest=UdfEndpointTest` → PASS
Run: `mvn -pl mask-core test` → PASS
Run: `mvn test`（全仓）→ PASS

- [ ] **Step 5: 文档回写并提交**

`README.md` 在策略服务相关章节后追加小节（措辞紧凑，与 README 风格一致）：

```markdown
## UDF 注册表（策略服务）

策略微服务的实例可登记脱敏 UDF 签名（名称 + 有序参数类型 + 返回类型，
首参数绑定被脱敏列的值，对齐 PG 以「名字+参数类型」标识函数、同名重载
按调用点解析）。REST：`POST/GET /api/instances/{instance}/udfs`、
`GET/PUT/DELETE /api/instances/{instance}/udfs/{name}`（web 应用内置
InMemory 存储，部署侧可换 JdbcPolicyStore + schema.sql 的 instance_udf 表）。

DATAMASK 策略写入时按注册表校验：UDF 存在、参数个数（= arguments + 1
个列值）、标量类型（number→整数/浮点/numeric 族，string→字符族，
boolean→boolean，无跨族转换）、每个选中列的类型与某重载首参精确相等
（无隐式转换，需要 `mask_phone(bigint, …)` 这类重载）。删除或替换使
启用中策略失效的 UDF 会被拒绝（先禁用策略）。YAML/CLI 路径不受影响。
```

spec 状态行改为：`状态：已实现（见 docs/superpowers/plans/2026-09-16-udf-registry.md）`。

```bash
git add mask-core/src/main/java/io/sqlmask/server/UdfController.java \
        mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
        mask-core/src/test/java/io/sqlmask/server/UdfEndpointTest.java \
        README.md docs/superpowers/specs/2026-09-15-udf-registry-design.md
git commit -m "feat(server): UDF 注册表 REST 端点与文档（/api/instances/{i}/udfs）"
```
