# P0 热修批次实施计划（2026-09-28 全项目走查 · 立即批次）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-09-28 全项目走查确认的六项 P0：inheritColumns 导出/导入丢失、/api/metadata/pull 无鉴权、ldaps 明文连接、risk-server 内置默认密钥、Trino `decimal(p)` NPE、前端查询页实例全部禁用。

**Architecture:** 六个互相独立的修复，各自带回归测试，不引入新依赖、不改任何 API 契约（唯一新增的是可选环境变量与前端内部聚合函数）。每个任务独立可测试、可单独提交、可单独回滚。

**Tech Stack:** Java 17 / Maven（JUnit 5 + Spring Boot Test + MockMvc）、UnboundID LDAP SDK（已在 mask-auth 依赖中）、Vue 3 + TypeScript + Vitest。

**Spec:** 本计划自含规格——所有问题均在 2026-09-28 走查会话中以 `文件:行号` 复核确认。执行者只需本文件，无需回溯会话。

## Global Constraints

- Java 17，构建命令在仓库根 `C:\Users\yhh\orca\mask` 执行；单模块测试统一用 `mvn -pl <module> -am test -Dtest='<测试类>' -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 连带构建依赖模块，`failIfNoSpecifiedTests=false` 避免依赖模块因无匹配测试而失败）。
- 前端命令在 `frontend/` 目录执行；测试跑单文件 `npx vitest run tests/<file>`，类型检查 `npm run build`（内含 `vue-tsc --noEmit`）。
- 不新增任何第三方依赖（LDAPS 所需的 `com.unboundid.util.ssl.SSLUtil`/`TrustAllTrustManager` 由既有 unboundid-ldapsdk 提供；`TrustAllTrustManager` 只允许出现在 test 代码）。
- 安全方向一律 fail-closed：密钥未配置时端点/功能拒绝服务，而不是放行。
- 代码注释与提交信息跟随仓库现行风格：中文注释说明"为什么"，conventional commit + 中文摘要（如 `fix(transfer): ...`）。
- 每个 Task 完成即 commit（只 `git add` 本任务触及的文件），不合并跨任务提交。
- 严禁修改 mask-lite 模块与 `.worktrees/` 下任何内容。

---

### Task 1: policies.yaml 导出/导入往返保留 inheritColumns

**背景（已核实）：** `PolicyExportMapper.java:27` 只用 4 参 `PolicyResource.column(c,s,t,column)`（inheritOnCopy 恒 false），`PolicyImportMapper.tableResource` 也不读 `resource.inheritOnCopy()`。`ResourceSelector.inheritColumns`（管理面实体字段）在导出→导入往返后变成空列表——"CTAS 复制继承脱敏"静默失效。YAML 层（`PolicyYamlLoader.java:111-118` / `PolicyYamlWriter.java:77-78`）已完整支持 `inheritOnCopy`，缺口只在两个 transfer mapper。

**Files:**
- Modify: `mask-policy-server/src/main/java/io/sqlmask/policyserver/transfer/PolicyExportMapper.java:23-41`
- Modify: `mask-policy-server/src/main/java/io/sqlmask/policyserver/transfer/PolicyImportMapper.java:59-82`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/transfer/PolicyExportMapperTest.java`
- Test: `mask-policy-server/src/test/java/io/sqlmask/policyserver/transfer/PolicyImportMapperTest.java`

**Interfaces:**
- Consumes: `PolicyResource.column(String,String,String,String,boolean)`（已存在，`mask-policy/.../model/PolicyResource.java:31`）；`ResourceSelector` 5 参规范构造（record 自带，`model/ResourceSelector.java:12`）；`PolicyYamlWriter.write(List<Policy>)`（已存在）。
- Produces: 无新 API——纯修 bug，行为变化仅为 `toPolicy`/`toEntities` 的继承标志正确往返。

- [ ] **Step 1: 写失败测试（导出方向）**

在 `PolicyExportMapperTest` 追加（import 区需补 `import static org.junit.jupiter.api.Assertions.assertFalse;`）：

```java
  @Test
  void exportsInheritColumnsAsInheritOnCopyResources() {
    PolicyEntity entity = new PolicyEntity("mask-phone", io.sqlmask.policyserver.model.PolicyType.DATAMASK,
        true, 5, new ResourceSelector("crm", "public", "customer",
            List.of("phone", "email"), List.of("phone")),
        new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of(3, 4), null);

    Policy policy = mapper.toPolicy(entity);

    assertEquals(2, policy.resources().size());
    assertTrue(policy.resources().get(0).inheritOnCopy(), "phone 应带继承标志");
    assertFalse(policy.resources().get(1).inheritOnCopy(), "email 不在 inheritColumns 中");
  }
```

- [ ] **Step 2: 写失败测试（导入方向 + 全链路往返）**

在 `PolicyImportMapperTest` 追加（import 区仅补 `import io.sqlmask.policy.store.PolicyYamlWriter;`；`PolicyExportMapper` 与测试类同包，直接 `new`）：

```java
  @Test
  void importsInheritOnCopyBackIntoSelector() {
    // 两个资源节点:phone 带继承、email 不带——粒度必须逐列保留
    List<PolicyEntity> entities = importYaml("""
        policies:
          - name: mask-phone
            enabled: true
            priority: 5
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: phone
                inheritOnCopy: true
              - catalog: crm
                schema: public
                table: customer
                column: email
            dataMaskItems:
              - groups: ["*"]
                udf: mask_phone
                arguments: [3, 4]
        """);

    assertEquals(1, entities.size());
    assertEquals(List.of("phone", "email"), entities.get(0).resource().columns());
    assertEquals(List.of("phone"), entities.get(0).resource().inheritColumns());
  }

  @Test
  void exportImportRoundTripPreservesInheritColumns() {
    PolicyEntity original = new PolicyEntity("mask-phone", PolicyType.DATAMASK,
        true, 5, new ResourceSelector("crm", "public", "customer",
            List.of("phone", "email"), List.of("phone")),
        new io.sqlmask.policy.model.SubjectSelector(java.util.Set.of(), java.util.Set.of("*")),
        "mask_phone", List.of(3, 4), null);

    String yaml = new PolicyYamlWriter().write(List.of(
        new io.sqlmask.policyserver.transfer.PolicyExportMapper().toPolicy(original)));
    PolicyEntity reimported = importYaml(yaml).get(0);

    assertEquals(List.of("phone", "email"), reimported.resource().columns());
    assertEquals(List.of("phone"), reimported.resource().inheritColumns());
  }
```

（round-trip 里的 `new io.sqlmask.policyserver.transfer.PolicyExportMapper().toPolicy(original)` 与测试类同包，可简写 `new PolicyExportMapper()`——保留 FQN 也能编译，二选一。）

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -pl mask-policy-server -am test -Dtest='PolicyExportMapperTest,PolicyImportMapperTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`exportsInheritColumnsAsInheritOnCopyResources` 断言 `inheritOnCopy` 为 true 失败；两个导入方向测试断言 `inheritColumns` 不等于 `[phone]`。

- [ ] **Step 4: 实现导出修复**

`PolicyExportMapper.java`——`toPolicy` 的 DATAMASK 分支改为（import 区补 `import java.util.Set;`）：

```java
    if (entity.policyType() == io.sqlmask.policyserver.model.PolicyType.DATAMASK) {
      Set<String> inherit = Set.copyOf(entity.resource().inheritColumns());
      for (String column : entity.resource().columns()) {
        resources.add(PolicyResource.column(entity.resource().catalog(), entity.resource().schema(),
            entity.resource().table(), column, inherit.contains(column)));
      }
```

（其余行不变。`Set.copyOf` 忽略重复并支持 O(1) contains；`inheritColumns` 列表里出现不在 `columns` 中的名字时自然无害——导出只遍历 `columns`。）

- [ ] **Step 5: 实现导入修复**

`PolicyImportMapper.tableResource` 改为（import 区补 `import java.util.ArrayList;` 已有，追加无需）：

```java
  /** One entity per table: columns are the distinct column-level resource names, in order.
   * inheritColumns 同序收集带 inheritOnCopy 的列;同一列重复声明时首见生效（与 columns 去重口径一致）。 */
  private static ResourceSelector tableResource(Policy policy) {
    String catalog = null;
    String schema = null;
    String table = null;
    Set<String> seen = new LinkedHashSet<>();
    List<String> columns = new ArrayList<>();
    List<String> inheritColumns = new ArrayList<>();
    for (PolicyResource resource : policy.resources()) {
      if (catalog == null) {
        catalog = resource.catalog();
        schema = resource.schema();
        table = resource.table();
      } else if (!catalog.equals(resource.catalog()) || !schema.equals(resource.schema())
          || !table.equals(resource.table())) {
        throw new PolicyException("policy '" + policy.name()
            + "': resources span multiple tables; import requires a single table per policy"
            + " (split the policy first)");
      }
      if (resource.column() != null && seen.add(resource.column())) {
        columns.add(resource.column());
        if (resource.inheritOnCopy()) {
          inheritColumns.add(resource.column());
        }
      }
    }
    return new ResourceSelector(catalog, schema, table, columns, inheritColumns);
  }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl mask-policy-server -am test -Dtest='PolicyExportMapperTest,PolicyImportMapperTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（全部用例，含既有 3+2 个）。

- [ ] **Step 7: 跑模块全量测试防回归**

Run: `mvn -pl mask-policy-server -am test`
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add mask-policy-server/src/main/java/io/sqlmask/policyserver/transfer/PolicyExportMapper.java \
        mask-policy-server/src/main/java/io/sqlmask/policyserver/transfer/PolicyImportMapper.java \
        mask-policy-server/src/test/java/io/sqlmask/policyserver/transfer/PolicyExportMapperTest.java \
        mask-policy-server/src/test/java/io/sqlmask/policyserver/transfer/PolicyImportMapperTest.java
git commit -m "fix(transfer): policies.yaml 导出/导入往返保留 inheritColumns"
```

---

### Task 2: /api/metadata/pull 补 fail-closed admin 网关

**背景（已核实）：** `SqlMaskServiceApplication` 只给 `/api/audit/*`、`/api/rewrite/instances/*`、`/admin/cache/refresh` 挂了过滤器（:80,:106,:129），`MetadataController`（`@RequestMapping("/api/metadata")` + `@PostMapping("/pull")`）无任何 API-key 门——任意可达者可驱使服务端凭据连任意主机（SSRF 面）。内置页面弹窗本就要求输入 Admin API Key 并随请求发送 `X-Api-Key`（`static/index.html:702-706`），服务端补上网关与 UI 契约正好闭合。

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（在 `cacheRefreshApiKeyFilter` bean 之后新增一个 bean）
- Test: `mask-core/src/test/java/io/sqlmask/server/MetadataPullGateTest.java`（新建）

**Interfaces:**
- Consumes: `io.sqlmask.common.web.ApiKeyFilter.failClosed(String)`（已存在，`/admin/cache/refresh` 同款）；`Environment.getProperty(String, String)`。
- Produces: 无新 Java API。部署契约变化：`/api/metadata/pull` 现在要求请求头 `X-Api-Key` 等于 `SQLMASK_ADMIN_API_KEY`；未配置该环境变量时端点一律 401（与 `/admin/cache/refresh` 行为一致；内置 UI 弹窗本就发送该头，契约闭合）。

- [ ] **Step 1: 写失败测试**

新建 `mask-core/src/test/java/io/sqlmask/server/MetadataPullGateTest.java`（镜像 `AdminCacheRefreshGateTest` 的写法；用 `engine:"bogus"` 让请求在通过网关后停在 CONFIG_ERROR，绝不触发真实外连）：

```java
package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The metadata pull dials out with operator credentials: it is an admin
 * action behind the same fail-closed gate as /admin/cache/refresh. No
 * SQLMASK_ADMIN_API_KEY ⇒ reject instead of letting anyone probe intranet
 * hosts through the service.
 */
@SpringBootTest(properties = "SQLMASK_ADMIN_API_KEY=metadata-secret")
@AutoConfigureMockMvc
class MetadataPullGateTest {

  @Autowired
  MockMvc mockMvc;

  private static final String BODY =
      "{\"engine\":\"bogus\",\"database\":\"d\",\"user\":\"u\",\"password\":\"p\"}";

  @Test
  void pullWithoutKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithWrongKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "nope")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithAdminKeyPassesTheGate() throws Exception {
    // 网关放行后停在 CONFIG_ERROR(未知引擎)而非 401——证明鉴权已过、且不会外连
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "metadata-secret")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-core -am test -Dtest='MetadataPullGateTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——前两个用例得到 200/400 而非 401（没有网关拦）。

- [ ] **Step 3: 实现过滤器注册**

在 `SqlMaskServiceApplication.java` 的 `cacheRefreshApiKeyFilter` bean（:117-132）之后新增：

```java
  /**
   * Admin gate for the metadata pull: the endpoint dials out to an
   * operator-supplied host with operator-supplied credentials, so it is a
   * management action and fails closed exactly like the cache-refresh
   * endpoint (the bundled UI already sends X-Api-Key with the admin key).
   */
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter>
      metadataPullApiKeyFilter(org.springframework.core.env.Environment env) {
    String adminKey = env.getProperty("SQLMASK_ADMIN_API_KEY", "");
    if (adminKey.isBlank()) {
      org.slf4j.LoggerFactory.getLogger(SqlMaskServiceApplication.class).warn(
          "SQLMASK_ADMIN_API_KEY is not configured: POST /api/metadata/pull is "
              + "REJECTED until the key is set (send it as X-Api-Key)");
    }
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            io.sqlmask.common.web.ApiKeyFilter.failClosed(adminKey));
    registration.addUrlPatterns("/api/metadata/pull");
    registration.setOrder(4);
    return registration;
  }
```

注意：用 `env.getProperty`（而非 `System.getenv`），与 `cacheRefreshApiKeyFilter` 一致，保证 `@SpringBootTest(properties=...)` 测试注入生效。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl mask-core -am test -Dtest='MetadataPullGateTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 跑 mask-core 全量测试防回归**

Run: `mvn -pl mask-core -am test`
Expected: PASS（若既有测试直接 POST /api/metadata/pull 且未带 key 而失败，为那些测试的 SpringBootTest properties 补 `SQLMASK_ADMIN_API_KEY=test-admin` 并在请求上加 `.header("X-Api-Key", "test-admin")`——这属于本修复的预期契约收紧，只改测试装配不改断言）。

- [ ] **Step 6: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
        mask-core/src/test/java/io/sqlmask/server/MetadataPullGateTest.java
git commit -m "fix(core): /api/metadata/pull 补 fail-closed admin 网关(堵 SSRF 面)"
```

---

### Task 3: mask-auth ldaps 真正走 TLS + 默认端口回退

**背景（已核实）：** `LdapAuthenticator.java:63` 对 `ldap://` 与 `ldaps://` 一律 `new LDAPConnection(options, uri.getHost(), uri.getPort())`——未传 SSLSocketFactory，`ldaps://` 也走明文，用户密码裸奔；且 URL 无端口时 `uri.getPort()==-1` 直接连 -1。

**Files:**
- Modify: `mask-auth/src/main/java/io/sqlmask/auth/AuthConfig.java`（新增两个可选环境变量的读取与访问器）
- Modify: `mask-auth/src/main/java/io/sqlmask/auth/LdapAuthenticator.java:51-102,176-190`
- Test: `mask-auth/src/test/java/io/sqlmask/auth/LdapAuthenticatorTest.java`

**Interfaces:**
- Consumes: UnboundID `SSLUtil()`/`SSLUtil(TrustManager[])`/`createSSLSocketFactory()`、`LDAPConnection(SocketFactory, LDAPConnectionOptions, String, int)`（`SSLSocketFactory` 是 `SocketFactory` 子类，直接传入；以上签名均经本地 unboundid-ldapsdk 6.0.11 `javap` 核实）。
- Produces:
  - `AuthConfig.ldapTruststorePath()` → `String|null`（env `MASK_AUTH_LDAP_TRUSTSTORE_PATH`，缺省 null ⇒ 用 JVM 默认信任库）
  - `AuthConfig.ldapTruststorePassword()` → `String|null`（env `MASK_AUTH_LDAP_TRUSTSTORE_PASSWORD`）
  - `LdapAuthenticator` 包私有构造 `LdapAuthenticator(AuthConfig, SSLSocketFactory)`（测试注入用，公开构造委托 `this(config, null)`）
  - 包私有静态 `int portOf(URI)`（端口回退：ldaps→636、ldap→389）

- [ ] **Step 1: 写失败测试（TLS 判别 + 端口回退）**

测试策略（无需生成自签证书）：对**明文** in-memory LDAP 服务器使用 `ldaps://` URL + 注入的 trust-all SSL 工厂——若实现真走 TLS，ClientHello 打到明文监听器必然握手失败（LDAP_UNAVAILABLE）；若仍是明文连接（当前 bug），amy 会认证成功、无异常抛出。该用例因此精确判别 bug。

在 `LdapAuthenticatorTest` 追加（import 区补 `com.unboundid.util.ssl.SSLUtil`、`com.unboundid.util.ssl.TrustAllTrustManager`、`java.net.URI`）：

```java
  @Test
  void ldapsUrlDialsTlsNotPlaintext() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy\nsn: Admin\nuserPassword: amy-secret");

    // 明文监听器 + ldaps URL:TLS ClientHello 必然失败 ⇒ LDAP_UNAVAILABLE。
    // (bug 在场时走明文连接、amy 认证成功,断言失败)
    LdapAuthenticator authenticator = new LdapAuthenticator(
        config(java.util.Map.of("MASK_AUTH_LDAP_URL",
            "ldaps://127.0.0.1:" + server.getListenPort())),
        new SSLUtil(new TrustAllTrustManager()).createSSLSocketFactory());

    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("amy", "amy-secret".toCharArray()));
    assertEquals(AuthException.Code.LDAP_UNAVAILABLE, e.code());
  }

  @Test
  void portOfFallsBackToWellKnownPorts() {
    assertEquals(636, LdapAuthenticator.portOf(URI.create("ldaps://host")));
    assertEquals(389, LdapAuthenticator.portOf(URI.create("ldap://host")));
    assertEquals(1389, LdapAuthenticator.portOf(URI.create("ldap://host:1389")));
  }
```

（`AuthException.code()` 与 `AuthPrincipal.username()` 访问器均已核实存在。）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-auth -am test -Dtest='LdapAuthenticatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败——`portOf`/双参构造不存在（第一处失败信号）。给两个新成员补最小空桩（`portOf` 暂返回 `uri.getPort()`；双参构造暂忽略注入工厂、连接逻辑不动）后再跑：`ldapsUrlDialsTlsNotPlaintext` 失败——明文连接下 amy 认证成功，`assertThrows` 落空。

- [ ] **Step 3: 实现 AuthConfig 扩展**

`AuthConfig.java`：字段区（:57 附近）加两行、Builder（:228 附近）加两行、`fromEnv`（:98 附近）加两行、访问器区加：

```java
  /** Optional path to a JKS/PKCS12 trust store for ldaps (self-signed AD CAs); null ⇒ JVM default. */
  public String ldapTruststorePath() {
    return ldapTruststorePath;
  }

  /** Password for the trust store at {@link #ldapTruststorePath()}; null ⇒ none. */
  public String ldapTruststorePassword() {
    return ldapTruststorePassword;
  }
```

`fromEnv` 中追加：

```java
    b.ldapTruststorePath = env.get("MASK_AUTH_LDAP_TRUSTSTORE_PATH");
    b.ldapTruststorePassword = env.get("MASK_AUTH_LDAP_TRUSTSTORE_PASSWORD");
```

类 javadoc 的 `<ul>` 清单里同步补一条：`MASK_AUTH_LDAP_TRUSTSTORE_PATH / _PASSWORD — ldaps 自签 CA 的信任库(可选;缺省用 JVM 默认信任库)`。

- [ ] **Step 4: 实现 LdapAuthenticator 修复**

`LdapAuthenticator.java`：

4a. import 区补：

```java
import com.unboundid.util.ssl.SSLUtil;
import java.security.KeyStore;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
```

4b. 字段与构造（替换现有 :41-49）：

```java
  private final AuthConfig config;
  private final SSLSocketFactory injectedLdapsFactory;

  public LdapAuthenticator(AuthConfig config) {
    this(config, null);
  }

  /** Tests inject a trust-all factory here; production passes null (config/JVM trust). */
  LdapAuthenticator(AuthConfig config, SSLSocketFactory ldapsFactoryForTests) {
    if (!config.ldapEnabled()) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "MASK_AUTH_LDAP_URL and MASK_AUTH_LDAP_BASE_DN are required");
    }
    this.config = config;
    this.injectedLdapsFactory = ldapsFactoryForTests;
  }
```

4c. `authenticate` 中连接建立（替换 :58-63；`LDAPConnection(SocketFactory, options, host, port)` 重载已核实，`SSLSocketFactory` 可作 `SocketFactory` 传入）：

```java
    URI uri = parseLdapUrl(config.ldapUrl());
    boolean tls = "ldaps".equals(uri.getScheme().toLowerCase(java.util.Locale.ROOT));
    int port = portOf(uri);
    LDAPConnectionOptions options = new LDAPConnectionOptions();
    options.setConnectTimeoutMillis((int) config.connectTimeout().toMillis());
    options.setResponseTimeoutMillis((int) config.responseTimeout().toMillis());

    try (LDAPConnection connection = tls
        ? new LDAPConnection(ldapsFactory(), options, uri.getHost(), port)
        : new LDAPConnection(options, uri.getHost(), port)) {
```

（后续行原样保留。）

4d. 新增私有/静态成员（放在 `parseLdapUrl` 之前）。注意 unboundid 6.0.11 的 `SSLUtil` **没有** `(KeyStore, char[])` 构造器（已 javap 核实）——自定义信任库必须经 `TrustManagerFactory` 转 `TrustManager[]`：

```java
  /** ldaps ⇒ 636, ldap ⇒ 389 when the URL carries no explicit port. */
  static int portOf(URI uri) {
    boolean tls = "ldaps".equals(uri.getScheme().toLowerCase(java.util.Locale.ROOT));
    return uri.getPort() == -1 ? (tls ? 636 : 389) : uri.getPort();
  }

  private SSLSocketFactory ldapsFactory() {
    if (injectedLdapsFactory != null) {
      return injectedLdapsFactory;
    }
    try {
      if (config.ldapTruststorePath() == null || config.ldapTruststorePath().isBlank()) {
        return new SSLUtil().createSSLSocketFactory(); // JVM 默认信任库
      }
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      char[] pass = config.ldapTruststorePassword() == null
          ? null : config.ldapTruststorePassword().toCharArray();
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(
          java.nio.file.Path.of(config.ldapTruststorePath()))) {
        trustStore.load(in, pass);
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      return new SSLUtil(tmf.getTrustManagers()).createSSLSocketFactory();
    } catch (Exception e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "cannot build ldaps socket factory (check MASK_AUTH_LDAP_TRUSTSTORE_PATH): "
              + e.getMessage());
    }
  }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl mask-auth -am test -Dtest='LdapAuthenticatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（含既有 34 用例——`ldap://` 路径行为不变）。

- [ ] **Step 6: 跑全模块测试（五个服务都依赖 mask-auth）**

Run: `mvn -pl mask-auth -am test && mvn -pl mask-core test`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add mask-auth/src/main/java/io/sqlmask/auth/AuthConfig.java \
        mask-auth/src/main/java/io/sqlmask/auth/LdapAuthenticator.java \
        mask-auth/src/test/java/io/sqlmask/auth/LdapAuthenticatorTest.java
git commit -m "fix(auth): ldaps 真正走 TLS(可配信任库)+默认端口 636/389 回退"
```

---

### Task 4: risk-server 移除内置默认密钥，block 未配置即停用

**背景（已核实）：** `mask-risk-server/src/main/resources/application.yml:18` 写死 `${RISK_BLOCK_POLICY_API_KEY:local-admin-key}`——漏配时风控服务持全网已知 admin key 调策略服务管理面。`BlockService.configured()`（:65-67）只看 `baseUrl`，不看密钥。

**Files:**
- Modify: `mask-risk-server/src/main/resources/application.yml:18`
- Modify: `mask-risk-server/src/main/java/io/sqlmask/riskserver/block/BlockService.java:65-67,341-345`
- Test: `mask-risk-server/src/test/java/io/sqlmask/riskserver/BlockServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `RiskProperties.Block`（getter/setter 齐备：`getBaseUrl()/getApiKey()`）。
- Produces: 语义变化——`BlockService.configured()` 现要求 `baseUrl` 与 `apiKey` 均非空；`risk.block.api-key` 无默认值。`BlockController` 的 `GET /api/risk/block/status` 已返回 `configured` 布尔（:44），前端自适应，无需改。

- [ ] **Step 1: 写失败测试**

在 `BlockServiceTest` 追加（先看该文件既有构造方式；若它用 `@TempDir` 存 state-path 则照抄，重点是 `statePath` 置空避免文件 IO）：

```java
  @Test
  void blockingRequiresApiKeyNotJustBaseUrl() {
    io.sqlmask.riskserver.config.RiskProperties.Block cfg =
        new io.sqlmask.riskserver.config.RiskProperties.Block();
    cfg.setBaseUrl("http://127.0.0.1:8081");
    cfg.setApiKey("");           // 修复前:内置 local-admin-key;修复后:默认空
    cfg.setStatePath("");
    BlockService noKey = new BlockService(cfg);
    org.junit.jupiter.api.Assertions.assertFalse(noKey.configured(),
        "无 api-key 时封禁必须整体停用(fail-closed),不得持内置密钥调管理面");

    cfg.setApiKey("real-key");
    org.junit.jupiter.api.Assertions.assertTrue(new BlockService(cfg).configured());
  }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-risk-server -am test -Dtest='BlockServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`configured()` 只看 baseUrl，第一个断言为 false 失败（返回 true）。
（注意：本步在 application.yml 改动前后行为一致，因为 `RiskProperties.Block.apiKey` 的 Java 默认本就是 `""`（`RiskProperties.java:120`）；yml 默认值只影响 Spring 装配路径。所以真正让单测红的是 `configured()` 的判定。）

- [ ] **Step 3: 实现**

3a. `application.yml:18`：

```yaml
    api-key: ${RISK_BLOCK_POLICY_API_KEY:}
```

3b. `BlockService.java`（:65-67）：

```java
  public boolean configured() {
    return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
        && config.getApiKey() != null && !config.getApiKey().isBlank();
  }
```

3c. `requireConfigured()`（:341-345）错误信息同步：

```java
  private void requireConfigured() {
    if (!configured()) {
      throw new IllegalArgumentException(
          "blocking is not configured: set risk.block.base-url and risk.block.api-key "
              + "(RISK_BLOCK_POLICY_BASE_URL / RISK_BLOCK_POLICY_API_KEY) for the policy service");
    }
  }
```

- [ ] **Step 4: 运行测试确认通过 + 全模块回归**

Run: `mvn -pl mask-risk-server -am test -Dtest='BlockServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS
Run: `mvn -pl mask-risk-server -am test` → 若 `BlockServiceTest`/`BlockServicePersistenceTest` 既有用例因未设 apiKey 而 `configured()==false` 失败，给那些用例的 RiskProperties.Block 装配补 `cfg.setApiKey("test-key")`（只补装配，不改断言）。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-risk-server/src/main/resources/application.yml \
        mask-risk-server/src/main/java/io/sqlmask/riskserver/block/BlockService.java \
        mask-risk-server/src/test/java/io/sqlmask/riskserver/BlockServiceTest.java
git commit -m "fix(risk): 移除内置 local-admin-key 默认值,block 未配密钥即停用"
```

---

### Task 5: Trino `decimal(p)` fail-closed（对齐 Hive I2 修复）

**背景（已核实）：** `TrinoTypeResolver.java:29-33` 只拒"有 scale 无 precision"，`decimal(10)` 漏过；`YamlCalciteSchemaFactory.java:62-63` 的 `createSqlType(DECIMAL, precision, column.scale())` 对 null scale 拆箱 NPE。Hive 侧同类问题已按"批2终审修复 I2"修为要求 `(p,s)` 双参（`HiveTypeResolver.java:27-33` + `HiveDialectProfileTest.decimalRequiresBothPrecisionAndScale`），Trino 照抄口径。

**Files:**
- Modify: `mask-engine/src/main/java/io/sqlmask/dialect/TrinoTypeResolver.java:29-33`
- Test: `mask-engine/src/test/java/io/sqlmask/dialect/TrinoTypeResolverTest.java`（新建）

**Interfaces:**
- Consumes/Produces: 无接口变化——`decimal(10)` 从"NPE 崩溃"变为 CONFIG_ERROR（HTTP 400），`decimal(p,s)` 行为不变。

- [ ] **Step 1: 写失败测试**

新建 `mask-engine/src/test/java/io/sqlmask/dialect/TrinoTypeResolverTest.java`：

```java
package io.sqlmask.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trino 类型声明守卫(对照 HiveDialectProfileTest.decimalRequiresBothPrecisionAndScale 的 I2 口径)。 */
class TrinoTypeResolverTest {

  private final TrinoTypeResolver resolver = new TrinoTypeResolver();

  @Test
  void decimalRequiresBothPrecisionAndScale() {
    // decimal(10)(precision=10, scale=null)曾漏过守卫、在下游 schema 构建
    // (YamlCalciteSchemaFactory#createSqlType 拆箱 scale)时 NPE——对齐 Hive I2:
    // fail-closed 要求 (p,s) 双参,报支持清单
    assertThat(resolver.parseColumn("a", "decimal(10, 2)").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "decimal(10)"))
        .isInstanceOf(io.sqlmask.error.SqlMaskException.class)
        .hasMessageContaining("decimal(10)")
        .hasMessageContaining("decimal(p,s)");
    assertThatThrownBy(() -> resolver.parseColumn("a", "decimal(,2)"))
        .isInstanceOf(io.sqlmask.error.SqlMaskException.class);
  }

  @Test
  void acceptsUnparameterizedScalarsAndTimezoneVariants() {
    assertThat(resolver.parseColumn("a", "bigint").sqlTypeName()).isNotNull();
    assertThat(resolver.parseColumn("a", "varchar").sqlTypeName()).isNotNull();
    assertThat(resolver.parseColumn("a", "timestamp(3) with time zone").sqlTypeName()).isNotNull();
  }
}
```

（`decimal(,2)` 断言已核实成立：`TypeResolver.PARAMS` 正则要求 precision 为 `\d+`，不匹配时整个串落入 default 分支抛 SqlMaskException。mask-engine 测试已用 assertj——`HiveDialectProfileTest` 同款。）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-engine -am test -Dtest='TrinoTypeResolverTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——`decimal(10)` 不抛异常（NPE 在 schema 工厂才发生，resolver 层静默通过），断言失败。

- [ ] **Step 3: 实现**

`TrinoTypeResolver.java:29-33` 的 `case "decimal"` 改为：

```java
      case "decimal" -> {
        // decimal 声明必须 (p,s) 双精度:单参 decimal(10) 的 scale=null 会在下游
        // schema 构建(YamlCalciteSchemaFactory#createSqlType 拆箱)时 NPE——
        // 这里 fail-closed 报支持清单(对齐 Hive I2 修复口径)
        if (precision == null || scale == null) {
          throw parseError(raw);
        }
      }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl mask-engine -am test -Dtest='TrinoTypeResolverTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 跑 mask-engine 全量防回归**

Run: `mvn -pl mask-engine -am test`
Expected: PASS（现有测试没有依赖 `decimal(p)` 单参的用例；若有 golden 文件含单参 decimal，属预期契约收紧，同步改测试数据）。

- [ ] **Step 6: Commit**

```bash
git add mask-engine/src/main/java/io/sqlmask/dialect/TrinoTypeResolver.java \
        mask-engine/src/test/java/io/sqlmask/dialect/TrinoTypeResolverTest.java
git commit -m "fix(dialect): Trino decimal(p) fail-closed,对齐 Hive I2 口径"
```

---

### Task 6: 前端查询页实例连接状态改经 detail 聚合

**背景（已核实）：** `QueryConsole.vue:104,139` 把 `listMetaInstances()` 的 `MetaInstanceSummary[]` 强转为带 `connection` 的类型，但 summary 契约（`api/meta.ts:21-26`）不含该字段——`:disabled="!i.connection"`（:17）导致所有实例恒禁用、页面不可用。`MetadataManager.vue:238-240` 已有正确的聚合模式（逐实例拉 detail 取 connection）。

**Files:**
- Modify: `frontend/src/api/meta.ts`（新增聚合函数）
- Modify: `frontend/src/views/queryconsole/QueryConsole.vue:93,104,135-143`
- Test: `frontend/tests/meta-api.spec.ts`（追加用例）

**Interfaces:**
- Consumes: 既有 `listMetaInstances()` / `getMetaInstance(name)`。
- Produces: `meta.ts` 新导出
  `listMetaInstancesWithConnection(): Promise<(MetaInstanceSummary & { connection?: MetaConnection | null })[]>`
  ——summary 列表 + 每实例一次 detail（失败降级为 `connection: undefined`，即"不可执行"）。

- [ ] **Step 1: 写失败测试**

在 `frontend/tests/meta-api.spec.ts` 的 `describe` 内追加：

```ts
  it("listMetaInstancesWithConnection aggregates connection from per-instance detail", async () => {
    const { listMetaInstancesWithConnection } = await import("@/api/meta");
    fetchMock.mockImplementation(async (input: unknown) => {
      const url = String(input);
      if (url === "/api/meta/instances") {
        return new Response(JSON.stringify([
          { name: "a", dialect: "postgresql", engine: "postgresql", metadataVersion: 1 },
          { name: "b", dialect: "mysql", engine: "mysql", metadataVersion: 2 },
        ]), { status: 200 });
      }
      if (url === "/api/meta/instances/a") {
        return new Response(JSON.stringify({
          name: "a", dialect: "postgresql", engine: "postgresql", metadataVersion: 1,
          connection: { host: "db1", port: 5432, database: "crm", dbUser: "u", passwordRef: "SQLMASK_X" },
          tables: [],
        }), { status: 200 });
      }
      return new Response("boom", { status: 500 }); // 实例 b 的 detail 失败
    });

    const rows = await listMetaInstancesWithConnection();

    expect(rows).toHaveLength(2);
    expect(rows.find((r) => r.name === "a")?.connection?.host).toBe("db1");
    expect(rows.find((r) => r.name === "b")?.connection).toBeUndefined();
  });
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npx vitest run tests/meta-api.spec.ts`
Expected: FAIL——`listMetaInstancesWithConnection` 不存在（import 报 undefined is not a function）。

- [ ] **Step 3: 实现 meta.ts 聚合函数**

在 `meta.ts` 的 `listMetaInstances` 之后追加：

```ts
export interface MetaInstanceSummaryWithConnection extends MetaInstanceSummary {
  connection?: MetaConnection | null;
}

/**
 * Summary 列表契约不含 connection 字段,而查询页需要按「是否有连接」禁用实例;
 * 逐实例拉 detail 聚合(MetadataManager 同款模式)。detail 失败的实例降级为
 * connection: undefined——宁可禁用也不放行(与后端 fail-closed 口径一致)。
 */
export function listMetaInstancesWithConnection(): Promise<MetaInstanceSummaryWithConnection[]> {
  return listMetaInstances().then((list) =>
    Promise.all(list.map((s) =>
      getMetaInstance(s.name)
        .then((d) => ({ ...s, connection: d.connection }))
        .catch(() => ({ ...s, connection: undefined })))));
}
```

- [ ] **Step 4: 改 QueryConsole 使用聚合函数**

`QueryConsole.vue`：

4a. import 行（:93）改为：

```ts
import { listMetaInstancesWithConnection, type MetaInstanceSummary } from "@/api/meta";
```

4b. `onMounted`（:135-143）改为：

```ts
onMounted(async () => {
  loadingInstances.value = true;
  try {
    instances.value = await listMetaInstancesWithConnection();
  } catch (e) {
    error.value = "实例列表加载失败(元数据服务):" + (e as Error).message;
  } finally { loadingInstances.value = false; }
});
```

（`instances` 的 ref 类型 `(MetaInstanceSummary & { connection?: unknown })[]` 保持不变——聚合返回类型可直接赋值；模板 `:disabled="!i.connection"` 语义从此真实。）

- [ ] **Step 5: 运行测试与类型检查确认通过**

Run: `cd frontend && npx vitest run tests/meta-api.spec.ts` → PASS（含既有 4 用例）
Run: `cd frontend && npm run build` → 通过（vue-tsc 无错误）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/meta.ts frontend/src/views/queryconsole/QueryConsole.vue \
        frontend/tests/meta-api.spec.ts
git commit -m "fix(frontend): 查询页实例连接状态改经 detail 聚合,修复全实例被禁用"
```

---

## 收尾验证（全部任务完成后）

- [ ] 仓库根 `mvn test`（全模块，含 mask-lite——它不在本次修改范围但须证明未被破坏）
- [ ] `cd frontend && npm test`
- [ ] `git log --oneline` 应看到 6 个 fix 提交，`git status` 干净

## 明确不做（防执行者顺手扩权）

- 不动 `/api/rewrite` 主体伪装（P1，另立计划：principal 优先 + 可选 key 门）
- 不动 risk-server 的 demo 端点角色、regex 预编译、webhook 重试（P0-2/3/4 的其余部分）
- 不动 InstanceRewriteConfig 缓存不刷新问题（另立计划）
- 不新增限流/请求体大小限制（短期批次）
- 不改 nginx/CSP/localStorage（前端安全另立计划）
