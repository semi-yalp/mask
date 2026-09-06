# 元数据微服务实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `mask-metadata` 微服务（实例 + 表结构唯一事实源：采集/存储/提供），并在 mask-policy 落地 `MetadataClient` 拉取前置件。

**Architecture:** 独立 Spring Boot 服务（默认 8082，自有 PG 库），依赖 mask-core 复用 `introspect` 采集库、方言 `TypeResolver`、`SqlMaskException`；数据面产物为现 YAML `tables` 段等价 JSON。策略侧接线（MetadataClient 的缓存/轮询/configVersion 传导）依赖尚未建成的策略编译栈，归策略微服务实现计划（见 spec §9 修订清单）。

**Tech Stack:** Java 17、Spring Boot 3.3.5（web + jdbc）、PostgreSQL、snakeyaml 2.2、JUnit 5、Mockito（spring-boot-starter-test）、`com.sun.net.httpserver.HttpServer`（HTTP 桩，不引新依赖）。

**Spec:** `docs/superpowers/specs/2026-09-07-metadata-service-design.md`

## Global Constraints

- Java 17；Spring Boot 版本由 parent `dependencyManagement` 的 `spring-boot-dependencies:3.3.5` 管理，Boot 组件不写版本；
- 模块依赖方向：`mask-metadata → mask-core`，`mask-policy → mask-core`，两服务互不依赖；
- 服务端口 8082；鉴权 header 为 `X-Api-Key`（与 core `PolicyServiceConfigSource` 发送端一致），Key 来自 `metadata.api-key` 配置属性或环境变量 `METADATA_API_KEY`，**未配置时 fail-closed 全 401**；
- 密码三条硬线：密码只存在于环境变量（库里存 `password_ref` 变量名）；日志、API 响应、错误信息不出现密码与 JDBC URL；采集失败不覆盖存量表结构；
- 错误码：`METADATA_INSTANCE_NOT_FOUND`(404)、`METADATA_INSTANCE_EXISTS`(409)、`METADATA_CREDENTIAL_UNAVAILABLE`(400)、`INTROSPECT_ERROR`(502)、`CONFIG_ERROR`(400)，响应体 `{code, message, details[]}`；
- 列类型存该引擎的类型名原始文本（即采集映射出的 YAML type），写入时按 `DialectProfiles.byName(dialect).typeResolver().parseColumn(...)` 校验；
- 导入遇表声明 `rowFilter` 字段 → 400 `CONFIG_ERROR` 并指路（不静默丢弃）；
- 任何实例变更在同一事务内 `metadata_version + 1`；
- 包名用 `io.sqlmask.metaserver.*`（core 已占用 `io.sqlmask.metadata` 包，避免 split package）；
- 每个任务结束时 `mvn test` 相关模块全绿后 commit。

---

### Task 1: mask-metadata 模块骨架与启动

**Files:**
- Modify: `pom.xml`（parent `<modules>`）
- Create: `mask-metadata/pom.xml`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/MetadataServerApplication.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/config/MetadataServerConfig.java`（本任务先空置鉴权，仅占位包）
- Create: `mask-metadata/src/main/resources/application.yml`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/MetadataServerApplicationTest.java`

**Interfaces:**
- Consumes: parent pom 的 `spring-boot-dependencies` BOM 与版本属性。
- Produces: 可启动的 Boot 应用（8082），后续所有任务的组件挂在其组件扫描下（根包 `io.sqlmask.metaserver`）。

- [ ] **Step 1: parent pom 注册模块**

`pom.xml` 的 `<modules>` 改为：

```xml
  <modules>
    <module>mask-core</module>
    <module>mask-policy</module>
    <module>mask-metadata</module>
  </modules>
```

- [ ] **Step 2: 写模块 pom**

`mask-metadata/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.sqlmask</groupId>
    <artifactId>sql-mask-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>mask-metadata</artifactId>
  <packaging>jar</packaging>

  <name>mask-metadata</name>
  <description>Metadata microservice: engine instance registry, table structures, multi-engine collection.</description>

  <dependencies>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
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
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
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

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire.version}</version>
      </plugin>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <goals><goal>repackage</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 写启动类与配置**

`mask-metadata/src/main/java/io/sqlmask/metaserver/MetadataServerApplication.java`：

```java
package io.sqlmask.metaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Metadata microservice entry point: instance registry, table structures and collection. */
@SpringBootApplication
public class MetadataServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(MetadataServerApplication.class, args);
  }
}
```

`mask-metadata/src/main/resources/application.yml`：

```yaml
server:
  port: 8082

spring:
  application:
    name: mask-metadata
  datasource:
    url: ${METADATA_PG_URL:jdbc:postgresql://127.0.0.1:5432/mask_metadata}
    username: ${METADATA_PG_USER:postgres}
    password: ${METADATA_PG_PASSWORD:postgres}
  sql:
    init:
      mode: always

metadata:
  api-key: ${METADATA_API_KEY:}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/config/MetadataServerConfig.java`（本任务只建占位配置类，Task 2 填充）：

```java
package io.sqlmask.metaserver.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {
}
```

- [ ] **Step 4: 写启动冒烟测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/MetadataServerApplicationTest.java`：

```java
package io.sqlmask.metaserver;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class MetadataServerApplicationTest {

  @Autowired
  private ApplicationContext context;

  @Test
  void contextLoads() {
    assertNotNull(context.getBean(MetadataServerApplication.class));
  }
}
```

- [ ] **Step 5: 运行验证**

Run: `mvn -pl mask-metadata -am test`
Expected: BUILD SUCCESS，`MetadataServerApplicationTest.contextLoads` PASS（无 PG 时 Hikari 懒连接，不阻塞启动）。

- [ ] **Step 6: Commit**

```bash
git add pom.xml mask-metadata
git commit -m "feat(metadata): mask-metadata 模块骨架（Boot 8082 + PG 数据源）"
```

---

### Task 2: API Key 过滤器、错误码与异常映射

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/error/SqlMaskException.java`（Code 枚举追加 4 值）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/config/ApiKeyFilter.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/config/MetadataServerConfig.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataApiExceptionHandler.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/config/ApiKeyFilterTest.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: core `SqlMaskException(Code, message)`。
- Produces: 所有 `/api/*` 请求要求 `X-Api-Key`；`SqlMaskException.Code` 新增 `METADATA_INSTANCE_NOT_FOUND`、`METADATA_INSTANCE_EXISTS`、`METADATA_CREDENTIAL_UNAVAILABLE`、`METADATA_SERVICE_UNAVAILABLE`；`MetadataApiExceptionHandler.handle(SqlMaskException)` 的状态映射（404/409/400/502/400）被后续所有 controller 任务依赖。

- [ ] **Step 1: core 枚举追加错误码**

`SqlMaskException.Code` 追加（放在 `INTROSPECT_ERROR` 之后）：

```java
  public enum Code {
    CONFIG_ERROR,
    PARSE_ERROR,
    VALIDATION_ERROR,
    UNSUPPORTED_STATEMENT,
    LINEAGE_UNKNOWN,
    REWRITE_ERROR,
    IO_ERROR,
    POLICY_SERVICE_UNAVAILABLE,
    POLICY_INSTANCE_NOT_FOUND,
    INTROSPECT_ERROR,
    METADATA_INSTANCE_NOT_FOUND,
    METADATA_INSTANCE_EXISTS,
    METADATA_CREDENTIAL_UNAVAILABLE,
    METADATA_SERVICE_UNAVAILABLE
  }
```

- [ ] **Step 2: 写过滤器失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/config/ApiKeyFilterTest.java`：

```java
package io.sqlmask.metaserver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

class ApiKeyFilterTest {

  private final MockFilterChain chain = new MockFilterChain();

  private MockHttpServletResponse run(ApiKeyFilter filter, String key) throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void rejectsMissingKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), null);
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
  }

  @Test
  void rejectsWrongKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "wrong");
    assertEquals(401, response.getStatus());
  }

  @Test
  void rejectsWhenServerKeyUnconfigured() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter(null), "anything");
    assertEquals(401, response.getStatus());
  }

  @Test
  void passesMatchingKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "secret");
    assertEquals(200, response.getStatus());
  }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -pl mask-metadata test -Dtest=ApiKeyFilterTest`
Expected: COMPILATION ERROR（`ApiKeyFilter` 不存在）。

- [ ] **Step 4: 实现过滤器并注册**

`mask-metadata/src/main/java/io/sqlmask/metaserver/config/ApiKeyFilter.java`：

```java
package io.sqlmask.metaserver.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Static API key gate over /api/*. Fail-closed: an unconfigured server key
 * rejects every request rather than opening the service.
 */
public class ApiKeyFilter implements Filter {

  private final String expectedKey;

  public ApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String provided = request.getHeader("X-Api-Key");
    if (expectedKey == null || expectedKey.isBlank() || !expectedKey.equals(provided)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }
}
```

`MetadataServerConfig.java` 替换为：

```java
package io.sqlmask.metaserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {

  @Bean
  public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${metadata.api-key:}") String configuredKey) {
    ApiKeyFilter filter = new ApiKeyFilter(configuredKey);
    FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
    return registration;
  }
}
```

- [ ] **Step 5: 运行过滤器测试确认通过**

Run: `mvn -pl mask-metadata test -Dtest=ApiKeyFilterTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 写异常映射失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataApiExceptionHandlerTest.java`：

```java
package io.sqlmask.metaserver.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MetadataApiExceptionHandlerTest {

  private final MetadataApiExceptionHandler handler = new MetadataApiExceptionHandler();

  @Test
  void mapsInstanceNotFoundTo404() {
    assertEquals(HttpStatus.NOT_FOUND, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, "nope")).getStatusCode());
  }

  @Test
  void mapsInstanceExistsTo409() {
    assertEquals(HttpStatus.CONFLICT, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_INSTANCE_EXISTS, "dup")).getStatusCode());
  }

  @Test
  void mapsCredentialUnavailableTo400() {
    assertEquals(HttpStatus.BAD_REQUEST, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE, "env")).getStatusCode());
  }

  @Test
  void mapsIntrospectErrorTo502() {
    assertEquals(HttpStatus.BAD_GATEWAY, handler.handle(new SqlMaskException(
        SqlMaskException.Code.INTROSPECT_ERROR, "db down")).getStatusCode());
  }

  @Test
  void mapsConfigErrorTo400() {
    assertEquals(HttpStatus.BAD_REQUEST, handler.handle(new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "bad input")).getStatusCode());
  }

  @Test
  void errorBodyCarriesCodeMessageDetails() {
    MetadataApiExceptionHandler.ApiError body = handler.handle(new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "bad input")).getBody();
    assertEquals("CONFIG_ERROR", body.code());
    assertEquals("bad input", body.message());
    assertEquals(java.util.List.of(), body.details());
  }
}
```

- [ ] **Step 7: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=MetadataApiExceptionHandlerTest`
Expected: COMPILATION ERROR（`MetadataApiExceptionHandler` 不存在）。

- [ ] **Step 8: 实现异常处理器**

`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataApiExceptionHandler.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps failures to {code, message, details[]} with spec §4.3 status mapping. */
@RestControllerAdvice
public class MetadataApiExceptionHandler {

  public record ApiError(String code, String message, List<String> details) {
  }

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.status(statusFor(e.getCode()))
        .body(new ApiError(e.getCode().name(), e.getMessage(), List.of()));
  }

  private static HttpStatus statusFor(SqlMaskException.Code code) {
    return switch (code) {
      case METADATA_INSTANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case METADATA_INSTANCE_EXISTS -> HttpStatus.CONFLICT;
      case METADATA_CREDENTIAL_UNAVAILABLE -> HttpStatus.BAD_REQUEST;
      case INTROSPECT_ERROR -> HttpStatus.BAD_GATEWAY;
      default -> HttpStatus.BAD_REQUEST;
    };
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
        "request body is not valid JSON: " + e.getMessage(), List.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
        "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
        List.of()));
  }
}
```

- [ ] **Step 9: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest='ApiKeyFilterTest,MetadataApiExceptionHandlerTest'`
Expected: PASS。

- [ ] **Step 10: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/error/SqlMaskException.java mask-metadata
git commit -m "feat(metadata): X-Api-Key 过滤器（fail-closed）与错误码/状态映射"
```

---

### Task 3: 元数据模型、MetaStore 接口、JdbcMetaStore 与 schema.sql

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/model/ConnectionInfo.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/model/InstanceRow.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/model/TableStructure.java`（含 `ColumnStructure` record）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/store/MetaStore.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/store/JdbcMetaStore.java`
- Create: `mask-metadata/src/main/resources/schema.sql`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/store/InMemoryMetaStore.java`（测试夹具，后续任务复用）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/store/JdbcMetaStoreIT.java`（`METADATA_PG_URL` 门控）

**Interfaces:**
- Consumes: Spring `JdbcTemplate`/`TransactionTemplate`。
- Produces:
  - `record ConnectionInfo(String host, int port, String database, String dbUser, String passwordRef, String sslmode, int connectTimeoutSeconds, List<String> schemas, boolean includeViews)`
  - `record InstanceRow(String name, String dialect, ConnectionInfo connection, long metadataVersion)`（`connection` 整组可空）
  - `record TableStructure(String catalog, String schema, String name, List<ColumnStructure> columns)`、`record ColumnStructure(String name, String type)`
  - `interface MetaStore { void createInstance(InstanceRow); Optional<InstanceRow> findInstance(String); List<InstanceRow> listInstances(); void updateInstance(String, ConnectionInfo); void deleteInstance(String); void replaceStructure(String, List<TableStructure>); List<TableStructure> loadStructure(String); }`（`updateInstance`/`replaceStructure` 实现内同事务 bump version）

- [ ] **Step 1: 写模型 records**

`mask-metadata/src/main/java/io/sqlmask/metaserver/model/ConnectionInfo.java`：

```java
package io.sqlmask.metaserver.model;

import java.util.List;

/**
 * Connection and collection-scope settings of an instance. Absent as a whole
 * (null) for YAML-imported instances without connection settings.
 */
public record ConnectionInfo(String host, int port, String database, String dbUser,
    String passwordRef, String sslmode, int connectTimeoutSeconds, List<String> schemas,
    boolean includeViews) {

  public ConnectionInfo {
    schemas = List.copyOf(schemas == null ? List.of() : schemas);
    sslmode = sslmode == null || sslmode.isBlank() ? "disable" : sslmode;
    if (connectTimeoutSeconds <= 0) {
      connectTimeoutSeconds = 10;
    }
  }
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/model/InstanceRow.java`：

```java
package io.sqlmask.metaserver.model;

/** Stored instance: identity + dialect + optional connection group + version anchor. */
public record InstanceRow(String name, String dialect, ConnectionInfo connection,
    long metadataVersion) {
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/model/TableStructure.java`：

```java
package io.sqlmask.metaserver.model;

import java.util.List;

/**
 * One table of an instance: ordered columns whose types are the engine's own
 * type name text (the YAML type per the dialect), never a parsed form.
 */
public record TableStructure(String catalog, String schema, String name,
    List<ColumnStructure> columns) {

  public TableStructure {
    columns = List.copyOf(columns);
  }

  public String qualifiedName() {
    return catalog + "." + schema + "." + name;
  }

  public record ColumnStructure(String name, String type) {
  }
}
```

- [ ] **Step 2: 写 MetaStore 接口**

`mask-metadata/src/main/java/io/sqlmask/metaserver/store/MetaStore.java`：

```java
package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.List;
import java.util.Optional;

/** Persistence port for instances and their table structures. */
public interface MetaStore {

  void createInstance(InstanceRow row);

  Optional<InstanceRow> findInstance(String name);

  List<InstanceRow> listInstances();

  /** Updates the mutable connection group; bumps metadata_version in the same transaction. */
  void updateInstance(String name, ConnectionInfo connection);

  void deleteInstance(String name);

  /** Replaces the whole table structure; bumps metadata_version in the same transaction. */
  void replaceStructure(String name, List<TableStructure> tables);

  List<TableStructure> loadStructure(String name);
}
```

- [ ] **Step 3: 写 schema.sql**

`mask-metadata/src/main/resources/schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS meta_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
  host VARCHAR(255),
  port INT,
  database VARCHAR(255),
  db_user VARCHAR(255),
  password_ref VARCHAR(255),
  sslmode VARCHAR(32),
  connect_timeout_seconds INT,
  schemas JSONB,
  include_views BOOLEAN NOT NULL DEFAULT FALSE,
  metadata_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meta_table (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES meta_instance(id) ON DELETE CASCADE,
  catalog VARCHAR(255) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  UNIQUE (instance_id, catalog, schema_name, table_name)
);

CREATE TABLE IF NOT EXISTS meta_column (
  id BIGSERIAL PRIMARY KEY,
  table_id BIGINT NOT NULL REFERENCES meta_table(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  type_declaration TEXT NOT NULL,
  position INT NOT NULL
);
```

- [ ] **Step 4: 写内存夹具**

`mask-metadata/src/test/java/io/sqlmask/metaserver/store/InMemoryMetaStore.java`：

```java
package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fixture: in-memory {@link MetaStore} with version bumps like the JDBC store. */
public class InMemoryMetaStore implements MetaStore {

  public final Map<String, InstanceRow> instances = new LinkedHashMap<>();
  public final Map<String, List<TableStructure>> structures = new LinkedHashMap<>();
  public final Map<String, Integer> versionBumps = new LinkedHashMap<>();

  @Override
  public void createInstance(InstanceRow row) {
    instances.put(row.name(), row);
    structures.put(row.name(), new ArrayList<>());
  }

  @Override
  public Optional<InstanceRow> findInstance(String name) {
    return Optional.ofNullable(instances.get(name));
  }

  @Override
  public List<InstanceRow> listInstances() {
    return new ArrayList<>(instances.values());
  }

  @Override
  public void updateInstance(String name, ConnectionInfo connection) {
    InstanceRow row = instances.get(name);
    instances.put(name, new InstanceRow(row.name(), row.dialect(), connection,
        row.metadataVersion() + 1));
    versionBumps.merge(name, 1, Integer::sum);
  }

  @Override
  public void deleteInstance(String name) {
    instances.remove(name);
    structures.remove(name);
  }

  @Override
  public void replaceStructure(String name, List<TableStructure> tables) {
    structures.put(name, new ArrayList<>(tables));
    InstanceRow row = instances.get(name);
    instances.put(name, new InstanceRow(row.name(), row.dialect(), row.connection(),
        row.metadataVersion() + 1));
    versionBumps.merge(name, 1, Integer::sum);
  }

  @Override
  public List<TableStructure> loadStructure(String name) {
    return new ArrayList<>(structures.getOrDefault(name, List.of()));
  }
}
```

- [ ] **Step 5: 写 JdbcMetaStore 门控 IT**

`mask-metadata/src/test/java/io/sqlmask/metaserver/store/JdbcMetaStoreIT.java`：

```java
package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ColumnStructure;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs only when METADATA_PG_URL is set; schema is applied idempotently. */
class JdbcMetaStoreIT {

  private static JdbcTemplate jdbc;
  private static JdbcMetaStore store;

  @BeforeAll
  static void setUp() throws Exception {
    String url = System.getenv("METADATA_PG_URL");
    Assumptions.assumeTrue(url != null, "METADATA_PG_URL not set; skipping JDBC store IT");
    SingleConnectionDataSource ds = new SingleConnectionDataSource(url,
        System.getenv().getOrDefault("METADATA_PG_USER", "postgres"),
        System.getenv().getOrDefault("METADATA_PG_PASSWORD", "postgres"), true);
    jdbc = new JdbcTemplate(ds);
    try (Connection connection = ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("schema.sql", JdbcMetaStoreIT.class));
    }
    store = new JdbcMetaStore(jdbc, new DataSourceTransactionManager(ds));
  }

  private static InstanceRow row(String name) {
    return new InstanceRow(name, "postgresql",
        new ConnectionInfo("127.0.0.1", 5432, "db", "user", "SQLMASK_TEST_PASSWORD",
            "disable", 10, List.of("public"), false), 1);
  }

  @Test
  void crudLifecycleBumpsVersion() {
    String name = "it_" + UUID.randomUUID();
    store.createInstance(row(name));
    assertEquals(1, store.findInstance(name).orElseThrow().metadataVersion());

    store.updateInstance(name, row(name).connection());
    assertEquals(2, store.findInstance(name).orElseThrow().metadataVersion());

    TableStructure table = new TableStructure("crm", "public", "customer", List.of(
        new ColumnStructure("id", "bigint"), new ColumnStructure("phone", "varchar")));
    store.replaceStructure(name, List.of(table));
    assertEquals(3, store.findInstance(name).orElseThrow().metadataVersion());

    List<TableStructure> loaded = store.loadStructure(name);
    assertEquals(1, loaded.size());
    assertEquals(table, loaded.get(0));

    store.deleteInstance(name);
    assertTrue(store.findInstance(name).isEmpty());
  }
}
```

- [ ] **Step 6: 写 JdbcMetaStore 实现**

`mask-metadata/src/main/java/io/sqlmask/metaserver/store/JdbcMetaStore.java`：

```java
package io.sqlmask.metaserver.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.metaserver.model.ColumnStructure;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link MetaStore}. Every mutation and its
 * metadata_version bump share one transaction (spec §3 version mechanism).
 */
@Repository
public class JdbcMetaStore implements MetaStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public JdbcMetaStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  private static final org.springframework.jdbc.core.RowMapper<InstanceRow> INSTANCE_ROW =
      (ResultSet rs, int i) -> new InstanceRow(
          rs.getString("name"),
          rs.getString("dialect"),
          connectionOf(rs),
          rs.getLong("metadata_version"));

  private static ConnectionInfo connectionOf(ResultSet rs) throws SQLException {
    String host = rs.getString("host");
    if (host == null) {
      return null;
    }
    List<String> schemas;
    String schemasJson = rs.getString("schemas");
    try {
      schemas = schemasJson == null ? List.of()
          : JSON.readValue(schemasJson, new TypeReference<List<String>>() {
          });
    } catch (Exception e) {
      throw new IllegalStateException("unreadable schemas JSON", e);
    }
    return new ConnectionInfo(host, rs.getInt("port"), rs.getString("database"),
        rs.getString("db_user"), rs.getString("password_ref"), rs.getString("sslmode"),
        rs.getInt("connect_timeout_seconds"), schemas, rs.getBoolean("include_views"));
  }

  @Override
  public void createInstance(InstanceRow row) {
    ConnectionInfo c = row.connection();
    jdbc.update("""
        INSERT INTO meta_instance (name, dialect, host, port, database, db_user, password_ref,
                                   sslmode, connect_timeout_seconds, schemas, include_views)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
        row.name(), row.dialect(),
        c == null ? null : c.host(), c == null ? null : c.port(),
        c == null ? null : c.database(), c == null ? null : c.dbUser(),
        c == null ? null : c.passwordRef(), c == null ? null : c.sslmode(),
        c == null ? null : c.connectTimeoutSeconds(),
        c == null ? null : toJson(c.schemas()), c != null && c.includeViews());
  }

  @Override
  public Optional<InstanceRow> findInstance(String name) {
    List<InstanceRow> rows = jdbc.query(
        "SELECT name, dialect, host, port, database, db_user, password_ref, sslmode, "
            + "connect_timeout_seconds, schemas::text AS schemas, include_views, metadata_version "
            + "FROM meta_instance WHERE name = ?", INSTANCE_ROW, name);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public List<InstanceRow> listInstances() {
    return jdbc.query(
        "SELECT name, dialect, host, port, database, db_user, password_ref, sslmode, "
            + "connect_timeout_seconds, schemas::text AS schemas, include_views, metadata_version "
            + "FROM meta_instance ORDER BY name", INSTANCE_ROW);
  }

  @Override
  public void updateInstance(String name, ConnectionInfo c) {
    tx.execute(status -> {
      jdbc.update("""
          UPDATE meta_instance SET host = ?, port = ?, database = ?, db_user = ?, password_ref = ?,
                   sslmode = ?, connect_timeout_seconds = ?, schemas = ?::jsonb, include_views = ?,
                   metadata_version = metadata_version + 1, updated_at = now()
          WHERE name = ?
          """,
          c == null ? null : c.host(), c == null ? null : c.port(),
          c == null ? null : c.database(), c == null ? null : c.dbUser(),
          c == null ? null : c.passwordRef(), c == null ? null : c.sslmode(),
          c == null ? null : c.connectTimeoutSeconds(),
          c == null ? null : toJson(c.schemas()), c != null && c.includeViews(), name);
      return null;
    });
  }

  @Override
  public void deleteInstance(String name) {
    jdbc.update("DELETE FROM meta_instance WHERE name = ?", name);
  }

  @Override
  public void replaceStructure(String name, List<TableStructure> tables) {
    tx.execute(status -> {
      Long id = jdbc.queryForObject("SELECT id FROM meta_instance WHERE name = ?", Long.class, name);
      jdbc.update(
          "UPDATE meta_instance SET metadata_version = metadata_version + 1, updated_at = now() "
              + "WHERE id = ?", id);
      jdbc.update(
          "DELETE FROM meta_column WHERE table_id IN "
              + "(SELECT id FROM meta_table WHERE instance_id = ?)", id);
      jdbc.update("DELETE FROM meta_table WHERE instance_id = ?", id);
      int tablePosition = 0;
      for (TableStructure table : tables) {
        Long tableId = jdbc.queryForObject(
            "INSERT INTO meta_table (instance_id, catalog, schema_name, table_name, position) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, id, table.catalog(), table.schema(), table.name(), tablePosition++);
        int columnPosition = 0;
        for (ColumnStructure column : table.columns()) {
          jdbc.update(
              "INSERT INTO meta_column (table_id, name, type_declaration, position) "
                  + "VALUES (?, ?, ?, ?)",
              tableId, column.name(), column.type(), columnPosition++);
        }
      }
      return null;
    });
  }

  @Override
  public List<TableStructure> loadStructure(String name) {
    Long id = jdbc.queryForObject("SELECT id FROM meta_instance WHERE name = ?", Long.class, name);
    List<TableStructure> tables = jdbc.query(
        "SELECT id, catalog, schema_name, table_name FROM meta_table "
            + "WHERE instance_id = ? ORDER BY position", (rs, i) -> new TableStructure(
            rs.getString("catalog"), rs.getString("schema_name"), rs.getString("table_name"),
            jdbc.query(
                "SELECT name, type_declaration FROM meta_column WHERE table_id = ? ORDER BY position",
                (crs, ci) -> new ColumnStructure(crs.getString("name"),
                    crs.getString("type_declaration")),
                rs.getLong("id"))),
        id);
    return tables;
  }

  private static String toJson(List<String> schemas) {
    try {
      return JSON.writeValueAsString(schemas);
    } catch (Exception e) {
      throw new IllegalStateException("unwritable schemas JSON", e);
    }
  }
}
```

- [ ] **Step 7: 运行验证（IT 默认 skip + 编译通过）**

Run: `mvn -pl mask-metadata test`
Expected: BUILD SUCCESS；`JdbcMetaStoreIT` 输出 "METADATA_PG_URL not set; skipping"（assumption skip），其余测试 PASS。

- [ ] **Step 8: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): meta_* 三表 schema、MetaStore 接口与 JdbcMetaStore（版本同事务递增）"
```

---

### Task 4: ConnectionInfo 整组校验与 MetadataService（实例 CRUD）

**Files:**
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/model/ConnectionInfo.java`（追加 `ofNullable` 工厂）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataService.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/model/ConnectionInfoTest.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/MetadataServiceTest.java`

**Interfaces:**
- Consumes: `MetaStore`（Task 3）、core `DialectProfiles.byName`、`SqlMaskException`。
- Produces:
  - `static ConnectionInfo ofNullable(String host, Integer port, String database, String dbUser, String passwordRef, String sslmode, Integer connectTimeoutSeconds, List<String> schemas, boolean includeViews)`：全空返回 `null`，任一非空则要求 host/port/database/dbUser/passwordRef 完整（否则 `SqlMaskException(CONFIG_ERROR)`），默认 sslmode=`disable`、timeout=10；
  - `MetadataService(MetaStore)`：`InstanceRow create(String name, String dialect, ConnectionInfo)`、`InstanceRow get(String)`、`List<InstanceRow> list()`、`InstanceRow updateConnection(String, ConnectionInfo)`、`void delete(String)`。create 落库 version=1；重复名 → `METADATA_INSTANCE_EXISTS`；dialect 非法 → `CONFIG_ERROR`（消息列支持值）。

- [ ] **Step 1: 写 ConnectionInfo.ofNullable 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/model/ConnectionInfoTest.java`：

```java
package io.sqlmask.metaserver.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

class ConnectionInfoTest {

  @Test
  void allBlankYieldsNull() {
    assertNull(ConnectionInfo.ofNullable(null, null, null, null, null, null, null, null, false));
  }

  @Test
  void completeGroupNormalizesDefaults() {
    ConnectionInfo info = ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", "user",
        "REF", "", null, List.of("public"), true);
    assertEquals("disable", info.sslmode());
    assertEquals(10, info.connectTimeoutSeconds());
    assertEquals(List.of("public"), info.schemas());
    assertEquals("REF", info.passwordRef());
  }

  @Test
  void partialGroupRejected() {
    SqlMaskException e = org.junit.jupiter.api.Assertions.assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", null, null, null, null, null, false));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void blankPasswordRefRejectedInsideGroup() {
    SqlMaskException e = org.junit.jupiter.api.Assertions.assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", "user", " ", null, null, null, false));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=ConnectionInfoTest`
Expected: COMPILATION ERROR（`ofNullable` 不存在）。

- [ ] **Step 3: 实现 ofNullable**

在 `ConnectionInfo` record 内追加：

```java
  /**
   * Builds the connection group from request fields: all-blank yields null
   * (no connection), any present field requires the complete group.
   */
  public static ConnectionInfo ofNullable(String host, Integer port, String database,
      String dbUser, String passwordRef, String sslmode, Integer connectTimeoutSeconds,
      List<String> schemas, boolean includeViews) {
    boolean any = (host != null && !host.isBlank()) || port != null
        || (database != null && !database.isBlank()) || (dbUser != null && !dbUser.isBlank())
        || (passwordRef != null && !passwordRef.isBlank())
        || (sslmode != null && !sslmode.isBlank()) || connectTimeoutSeconds != null
        || (schemas != null && !schemas.isEmpty());
    if (!any) {
      return null;
    }
    if (host == null || host.isBlank() || port == null || port <= 0
        || database == null || database.isBlank() || dbUser == null || dbUser.isBlank()
        || passwordRef == null || passwordRef.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "connection settings must be provided as a complete group "
              + "(host, port, database, user, passwordRef)");
    }
    return new ConnectionInfo(host, port, database, dbUser, passwordRef, sslmode,
        connectTimeoutSeconds == null ? 0 : connectTimeoutSeconds, schemas, includeViews);
  }
```

（`SqlMaskException` import：`import io.sqlmask.error.SqlMaskException;`）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=ConnectionInfoTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 写 MetadataService 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/service/MetadataServiceTest.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final MetadataService service = new MetadataService(store);

  private static ConnectionInfo conn() {
    return new ConnectionInfo("127.0.0.1", 5432, "db", "user", "REF", "disable", 10,
        List.of(), false);
  }

  @Test
  void createStoresRowWithVersionOne() {
    InstanceRow row = service.create("pg_prod", "PostgreSQL", conn());
    assertEquals("pg_prod", row.name());
    assertEquals("postgresql", row.dialect());
    assertEquals(1, row.metadataVersion());
  }

  @Test
  void duplicateCreateRejected() {
    service.create("pg_prod", "postgresql", conn());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("pg_prod", "postgresql", conn()));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_EXISTS, e.getCode());
  }

  @Test
  void unknownDialectRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("x", "oracle", conn()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void blankNameRejected() {
    assertThrows(SqlMaskException.class, () -> service.create("  ", "postgresql", conn()));
  }

  @Test
  void getUnknownReturnsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.get("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void updateConnectionBumpsVersion() {
    service.create("pg_prod", "postgresql", conn());
    InstanceRow updated = service.updateConnection("pg_prod", null);
    assertEquals(2, updated.metadataVersion());
    assertEquals(null, updated.connection());
  }

  @Test
  void deleteRemovesInstance() {
    service.create("pg_prod", "postgresql", conn());
    service.delete("pg_prod");
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> service.get("pg_prod")).getCode());
  }
}
```

- [ ] **Step 6: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=MetadataServiceTest`
Expected: COMPILATION ERROR（`MetadataService` 不存在）。

- [ ] **Step 7: 实现 MetadataService**

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataService.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.store.MetaStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Instance CRUD with spec §4.1 rules: immutable name/dialect, versioned mutations. */
@Service
public class MetadataService {

  private final MetaStore store;

  public MetadataService(MetaStore store) {
    this.store = store;
  }

  public InstanceRow create(String name, String dialect, ConnectionInfo connection) {
    String trimmed = requireName(name);
    String normalizedDialect = normalizeDialect(dialect);
    if (store.findInstance(trimmed).isPresent()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_EXISTS,
          "instance '" + trimmed + "' already exists");
    }
    store.createInstance(new InstanceRow(trimmed, normalizedDialect, connection, 1));
    return store.findInstance(trimmed).orElseThrow();
  }

  public InstanceRow get(String name) {
    return store.findInstance(requireName(name))
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
            "instance '" + name + "' does not exist"));
  }

  public List<InstanceRow> list() {
    return store.listInstances();
  }

  public InstanceRow updateConnection(String name, ConnectionInfo connection) {
    get(name);
    store.updateInstance(requireName(name), connection);
    return get(name);
  }

  public void delete(String name) {
    get(name);
    store.deleteInstance(requireName(name));
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance name is required");
    }
    return name.trim();
  }

  private static String normalizeDialect(String dialect) {
    try {
      DialectProfiles.byName(dialect);
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + dialect + "' (supported: postgresql, mysql, trino)");
    }
    return dialect.trim().toLowerCase(Locale.ROOT);
  }
}
```

- [ ] **Step 8: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=MetadataServiceTest`
Expected: PASS（7 个用例）。

- [ ] **Step 9: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): 连接组整组校验与实例 CRUD（不可变 name/dialect，version 锚点）"
```

---

### Task 5: StructureService（写时类型校验 + 表结构覆盖）

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/StructureService.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/StructureServiceTest.java`

**Interfaces:**
- Consumes: `MetaStore.replaceStructure/loadStructure/findInstance`、`DialectProfiles.byName(dialect).typeResolver().parseColumn(name, type)`、`TableStructure`/`ColumnStructure`。
- Produces: `StructureService(MetaStore)`：`long replace(String instanceName, List<TableStructure> tables)` —— 逐表逐列按实例 dialect 校验类型（抛 `SqlMaskException(CONFIG_ERROR)` 带路径化消息）、实例内重复表 → `CONFIG_ERROR`、允许空列表（采集 0 表场景）；成功返回覆盖后的新 `metadataVersion`。

- [ ] **Step 1: 写失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/service/StructureServiceTest.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final StructureService service = new StructureService(store);

  StructureServiceTest() {
    store.createInstance(new InstanceRow("pg_prod", "postgresql",
        new ConnectionInfo("h", 5432, "d", "u", "R", "disable", 10, List.of(), false), 1));
    store.createInstance(new InstanceRow("my_prod", "mysql", null, 1));
  }

  private static TableStructure table(String name, String[][] columns) {
    return new TableStructure("crm", "public", name,
        java.util.Arrays.stream(columns)
            .map(c -> new TableStructure.ColumnStructure(c[0], c[1])).toList());
  }

  @Test
  void validTablesReplaceAndBumpVersion() {
    long version = service.replace("pg_prod", List.of(
        table("customer", new String[][]{{"id", "bigint"}, {"phone", "varchar"}})));
    assertEquals(2, version);
    assertEquals(1, store.loadStructure("pg_prod").size());
  }

  @Test
  void emptyListAllowedForCollectionScenario() {
    assertEquals(2, service.replace("pg_prod", List.of()));
    assertEquals(0, store.loadStructure("pg_prod").size());
  }

  @Test
  void unresolvableTypeRejectedWithPath() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.replace("pg_prod",
        List.of(table("customer", new String[][]{{"id", "not-a-type"}}))));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertEquals(true, e.getMessage().contains("crm.public.customer.id"));
  }

  @Test
  void duplicateTableRejected() {
    assertThrows(SqlMaskException.class, () -> service.replace("pg_prod",
        List.of(table("customer", new String[][]{{"id", "bigint"}}),
            table("CUSTOMER", new String[][]{{"id", "bigint"}}))));
  }

  @Test
  void mysqlTypesValidatedAgainstMysqlResolver() {
    long version = service.replace("my_prod", List.of(
        table("orders", new String[][]{{"id", "bigint"}, {"amount", "decimal(10,2)"}})));
    assertEquals(2, version);
  }

  @Test
  void unknownInstanceRejected() {
    assertThrows(SqlMaskException.class, () -> service.replace("ghost", List.of()));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=StructureServiceTest`
Expected: COMPILATION ERROR（`StructureService` 不存在）。

- [ ] **Step 3: 实现 StructureService**

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/StructureService.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.MetaStore;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a table structure against the instance's dialect type resolver
 * (write-time round-trip guarantee) and replaces it atomically in the store.
 * An empty list is legal: the collection scenario may legitimately capture
 * zero tables (warning surfaced by the caller).
 */
@Service
public class StructureService {

  private final MetaStore store;

  public StructureService(MetaStore store) {
    this.store = store;
  }

  public long replace(String instanceName, List<TableStructure> tables) {
    InstanceRow instance = store.findInstance(instanceName)
        .orElseThrow(() -> new SqlMaskException(
            SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
            "instance '" + instanceName + "' does not exist"));
    Set<String> seen = new HashSet<>();
    for (TableStructure table : tables) {
      if (table.columns().isEmpty()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            table.qualifiedName() + ": must declare at least one column");
      }
      if (!seen.add(normalizeQualifiedName(table))) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "duplicate table '" + table.qualifiedName() + "'");
      }
      for (TableStructure.ColumnStructure column : table.columns()) {
        validateColumn(instance.dialect(), table, column);
      }
    }
    store.replaceStructure(instanceName, tables);
    return store.findInstance(instanceName).orElseThrow().metadataVersion();
  }

  private static void validateColumn(String dialect, TableStructure table,
      TableStructure.ColumnStructure column) {
    try {
      DialectProfiles.byName(dialect).typeResolver().parseColumn(column.name(), column.type());
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          table.qualifiedName() + "." + column.name() + ": " + e.getMessage());
    }
  }

  private static String normalizeQualifiedName(TableStructure table) {
    return table.catalog().toLowerCase(Locale.ROOT) + "."
        + table.schema().toLowerCase(Locale.ROOT) + "."
        + table.name().toLowerCase(Locale.ROOT);
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=StructureServiceTest`
Expected: PASS（6 个用例；若 `not-a-type` 被 PG resolver 接受，换一个确认被拒的形态如 `unsigned big int`，以解析器实际行为为准——跑失败时按报错调整该用例的类型文本，其余断言不动）。

- [ ] **Step 5: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): 写时类型校验（方言 TypeResolver）与表结构原子覆盖"
```

---

### Task 6: YAML 导入器与实例导入端点

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataYamlImporter.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java`（本任务先落 create/list/import 三个端点）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDtos.java`（请求/响应 records 集中放置）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/MetadataYamlImporterTest.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataAdminControllerTest.java`

**Interfaces:**
- Consumes: `MetadataService`、`StructureService`、`MetadataYamlImporter`、`ApiKeyFilter`（`X-Api-Key`）、`MetadataApiExceptionHandler`。
- Produces:
  - `MetadataYamlImporter.parse(String yaml, String sourceName)` → `List<TableStructure>`；要求 `metadata.tables` 列表；表内 `rowFilter` 字段 → `SqlMaskException(CONFIG_ERROR)`，消息含 `"rowFilter is not accepted here"` 与 `"configure a row_filter policy on the policy service"`；
  - DTO（`MetadataDtos`）：`InstanceCreateRequest(String name, String dialect, ConnectionRequest connection)`、`ConnectionRequest(String host, Integer port, String database, String dbUser, String passwordRef, String sslmode, Integer connectTimeoutSeconds, List<String> schemas, boolean includeViews)`、`InstanceImportRequest(String name, String dialect, ConnectionRequest connection, String metadataYaml)`、`InstanceSummaryResponse(String name, String dialect, long metadataVersion)`、`InstanceDetailResponse(String name, String dialect, long metadataVersion, ConnectionInfo connection, List<TableStructure> tables)`、`ImportResponse(String name, int tableCount, int columnCount, long metadataVersion)`；
  - 端点：`POST /api/instances`、`GET /api/instances`、`POST /api/instances/import`（均需 API Key）。

- [ ] **Step 1: 写导入器失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/service/MetadataYamlImporterTest.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataYamlImporterTest {

  private final MetadataYamlImporter importer = new MetadataYamlImporter();

  @Test
  void parsesMetadataTables() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: varchar }
        """;
    List<TableStructure> tables = importer.parse(yaml, "test.yaml");
    assertEquals(1, tables.size());
    assertEquals("crm", tables.get(0).catalog());
    assertEquals(2, tables.get(0).columns().size());
    assertEquals("varchar", tables.get(0).columns().get(1).type());
  }

  @Test
  void rowFilterFieldRejectedWithGuidance() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("rowFilter is not accepted here"));
    assertTrue(e.getMessage().contains("row_filter policy on the policy service"));
  }

  @Test
  void missingTablesRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse("metadata: {}", "test.yaml"));
    assertTrue(e.getMessage().contains("must be a list"));
  }

  @Test
  void emptyColumnsRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns: []
        """;
    assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=MetadataYamlImporterTest`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现导入器**

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataYamlImporter.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the tables section of the legacy YAML into structures. Row filters
 * are a policy-domain concern: a rowFilter field is rejected outright (never
 * silently dropped) with guidance to the policy service.
 */
@Service
public class MetadataYamlImporter {

  private final Yaml yaml = new Yaml();

  public List<TableStructure> parse(String source, String sourceName) {
    Object root = yaml.load(source);
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          sourceName + ": YAML must be a mapping");
    }
    Object metadataNode = rootMap.get("metadata");
    Object tablesNode = metadataNode instanceof Map<?, ?> metadataMap
        ? metadataMap.get("tables")
        : null;
    if (!(tablesNode instanceof List<?> tableList)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          sourceName + ": 'metadata.tables' must be a list");
    }
    List<TableStructure> tables = new ArrayList<>();
    for (int i = 0; i < tableList.size(); i++) {
      tables.add(parseTable(tableList.get(i), sourceName + ": metadata.tables[" + i + "]"));
    }
    return tables;
  }

  private static TableStructure parseTable(Object node, String path) {
    if (!(node instanceof Map<?, ?> tableMap)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, path + " must be a mapping");
    }
    if (tableMap.get("rowFilter") != null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + ".rowFilter is not accepted here: row filters are policies now; "
              + "configure a row_filter policy on the policy service");
    }
    String catalog = requiredString(tableMap, "catalog", path);
    String schema = requiredString(tableMap, "schema", path);
    String name = requiredString(tableMap, "name", path);
    if (!(tableMap.get("columns") instanceof List<?> columnList) || columnList.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + ".columns must be a non-empty list");
    }
    List<TableStructure.ColumnStructure> columns = new ArrayList<>();
    for (int j = 0; j < columnList.size(); j++) {
      Object columnNode = columnList.get(j);
      String columnPath = path + ".columns[" + j + "]";
      if (!(columnNode instanceof Map<?, ?> columnMap)) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            columnPath + " must be a mapping");
      }
      columns.add(new TableStructure.ColumnStructure(
          requiredString(columnMap, "name", columnPath),
          requiredString(columnMap, "type", columnPath)));
    }
    return new TableStructure(catalog, schema, name, columns);
  }

  private static String requiredString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + "." + key + " is required");
    }
    return s;
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=MetadataYamlImporterTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 写 controller 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataAdminControllerTest.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.MetadataYamlImporter;
import io.sqlmask.metaserver.service.StructureService;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "metadata.api-key=test-key")
@AutoConfigureMockMvc
class MetadataAdminControllerTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private InMemoryMetaStore store;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }
  }

  @BeforeEach
  void resetStore() {
    store.instances.clear();
    store.structures.clear();
    store.versionBumps.clear();
  }

  @Test
  void requiresApiKey() throws Exception {
    mockMvc.perform(get("/api/instances")).andExpect(status().isUnauthorized());
  }

  @Test
  void createListAndImportFlow() throws Exception {
    mockMvc.perform(post("/api/instances")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_prod","dialect":"postgresql",
                 "connection":{"host":"127.0.0.1","port":5432,"database":"db",
                               "dbUser":"user","passwordRef":"REF",
                               "sslmode":"disable","connectTimeoutSeconds":10,
                               "schemas":[],"includeViews":false}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(1));

    mockMvc.perform(get("/api/instances").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].dialect").value("postgresql"));

    MvcResult importResult = mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_imported","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      columns:\\n        - { name: id, type: bigint }\\n"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.metadataVersion").value(2))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertNotEquals("", importResult.getResponse()
        .getContentAsString());
  }

  @Test
  void importRejectsRowFilterWith400() throws Exception {
    mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_bad","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      rowFilter: \\"status = 'active'\\"\\n      columns:\\n        - { name: id, type: bigint }\\n"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("row_filter policy on the policy service")));
  }

  @Test
  void importRejectsUnresolvableType() throws Exception {
    mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_bad_type","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      columns:\\n        - { name: id, type: definitely-not-a-type }\\n"}
                """))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 6: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=MetadataAdminControllerTest`
Expected: 失败（`/api/instances` 无映射 → 404/401 断言不满足）。

- [ ] **Step 7: 写 DTO 与 controller**

`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDtos.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.List;

/** Request/response records of the metadata admin and data planes. */
public final class MetadataDtos {

  private MetadataDtos() {
  }

  public record ConnectionRequest(String host, Integer port, String database, String dbUser,
      String passwordRef, String sslmode, Integer connectTimeoutSeconds, List<String> schemas,
      boolean includeViews) {
  }

  public record InstanceCreateRequest(String name, String dialect, ConnectionRequest connection) {
  }

  public record InstanceUpdateRequest(ConnectionRequest connection) {
  }

  public record InstanceImportRequest(String name, String dialect, ConnectionRequest connection,
      String metadataYaml) {
  }

  public record InstanceSummaryResponse(String name, String dialect, long metadataVersion) {
  }

  public record InstanceDetailResponse(String name, String dialect, long metadataVersion,
      ConnectionInfo connection, List<TableStructure> tables) {
  }

  public record ImportResponse(String name, int tableCount, int columnCount,
      long metadataVersion) {
  }
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.MetadataYamlImporter;
import io.sqlmask.metaserver.service.StructureService;
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
 * Admin plane: instance CRUD and YAML import (spec §4.1). Collection lives in
 * CollectController; data plane in MetadataDataController.
 */
@RestController
@RequestMapping("/api/instances")
public class MetadataAdminController {

  private final MetadataService instances;
  private final StructureService structures;
  private final MetadataYamlImporter importer;

  public MetadataAdminController(MetadataService instances, StructureService structures,
      MetadataYamlImporter importer) {
    this.instances = instances;
    this.structures = structures;
    this.importer = importer;
  }

  @PostMapping
  public MetadataDtos.InstanceDetailResponse create(
      @RequestBody MetadataDtos.InstanceCreateRequest request) {
    if (request == null || request.name() == null || request.dialect() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name and dialect are required");
    }
    InstanceRow row = instances.create(request.name(), request.dialect(),
        ofNullable(request.connection()));
    return detail(row);
  }

  @GetMapping
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.metadataVersion()))
        .toList();
  }

  @GetMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse get(@PathVariable String name) {
    return detail(instances.get(name));
  }

  @PutMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse update(@PathVariable String name,
      @RequestBody MetadataDtos.InstanceUpdateRequest request) {
    ConnectionInfo connection = ofNullable(request == null ? null : request.connection());
    return detail(instances.updateConnection(name, connection));
  }

  @DeleteMapping("/{name}")
  public MetadataDtos.InstanceSummaryResponse delete(@PathVariable String name) {
    InstanceRow row = instances.get(name);
    instances.delete(name);
    return new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
        row.metadataVersion());
  }

  @PostMapping("/import")
  public MetadataDtos.ImportResponse importYaml(
      @RequestBody MetadataDtos.InstanceImportRequest request) {
    if (request == null || request.name() == null || request.dialect() == null
        || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name, dialect and metadataYaml are required");
    }
    List<TableStructure> tables = importer.parse(request.metadataYaml(), request.name());
    if (tables.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml declares no tables");
    }
    instances.create(request.name(), request.dialect(),
        ofNullable(request.connection()));
    long version = structures.replace(request.name().trim(), tables);
    int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
    return new MetadataDtos.ImportResponse(request.name().trim(), tables.size(), columnCount,
        version);
  }

  static ConnectionInfo ofNullable(MetadataDtos.ConnectionRequest request) {
    return request == null ? null : ConnectionInfo.ofNullable(request.host(), request.port(),
        request.database(), request.dbUser(), request.passwordRef(), request.sslmode(),
        request.connectTimeoutSeconds(), request.schemas(), request.includeViews());
  }

  private MetadataDtos.InstanceDetailResponse detail(InstanceRow row) {
    return new MetadataDtos.InstanceDetailResponse(row.name(), row.dialect(),
        row.metadataVersion(), row.connection(), structures.load(row.name()));
  }
}
```

注意：`ConnectionInfo.ofNullable(request.connection())` 这个重载不存在——上面 controller 的 `create`/`update`/`importYaml` 一律调用本类私有静态 `ofNullable(MetadataDtos.ConnectionRequest)`。

- [ ] **Step 8: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=MetadataAdminControllerTest`
Expected: PASS（5 个用例）。注意 `@SpringBootTest` 上下文里 `InMemoryMetaStore` 以 `@Primary` 覆盖 `JdbcMetaStore` 注入 `MetadataService`/`StructureService`（构造注入按类型收敛到 @Primary bean）。

- [ ] **Step 9: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): YAML 导入器（rowFilter 拒绝指路）与实例管理端点"
```

---

### Task 7: CredentialResolver 与采集端点

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/CredentialResolver.java`（接口）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/EnvCredentialResolver.java`（默认实现）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/IntrospectorFactory.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/config/MetadataServerConfig.java`（追加 `IntrospectorFactory` bean）
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/CollectController.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDtos.java`（追加 `CollectResponse`）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/CollectControllerTest.java`

**Interfaces:**
- Consumes: core `ConnectionSpec(engine, host, port, database, user, password, schemas, includeViews, strict, sslmode, connectTimeoutSeconds)`、`MetadataIntrospector.introspect(spec)` → `IntrospectionResult(catalog, tables[TableInfo(catalog, schema, name, columns[ColumnInfo(name, yamlType, originalPgType, degraded)])], warnings)`、`MetadataIntrospectors.byEngine`、`StructureService.replace`、`MetadataService.get`。
- Produces:
  - `interface CredentialResolver { String resolve(String passwordRef); }`（`EnvCredentialResolver` 为 `@Service` 实现：`System.getenv` 解析；变量未设置 → `SqlMaskException(METADATA_CREDENTIAL_UNAVAILABLE)`，消息含变量名，不含值）；
  - `interface IntrospectorFactory { MetadataIntrospector byEngine(String engine); }`（默认实现委托 `MetadataIntrospectors::byEngine`，测试注入 fake）；
  - `CollectService.collect(String name)` → `CollectResponse(int tableCount, int columnCount, List<String> warnings, long metadataVersion)`；无连接信息 → `CONFIG_ERROR`；采集异常原样冒泡（`INTROSPECT_ERROR` → 502），**不覆盖存量表结构**；
  - 端点 `POST /api/instances/{name}/collect`。

- [ ] **Step 1: 写 DTO 追加**

`MetadataDtos` 追加：

```java
  public record CollectResponse(int tableCount, int columnCount, List<String> warnings,
      long metadataVersion) {
  }
```

- [ ] **Step 2: 写 CollectService 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataIntrospector;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final MetadataService instances = new MetadataService(store);
  private final StructureService structures = new StructureService(store);

  private static IntrospectionResult result = new IntrospectionResult("db",
      List.of(new IntrospectionResult.TableInfo("db", "public", "customer",
          List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "int8", false),
              new IntrospectionResult.ColumnInfo("phone", "varchar", "text", false)))),
      List.of("column db.public.customer.phone: PG type text is not representable, degraded to varchar"));

  private final CollectService service = new CollectService(instances, structures,
      ref -> "resolved-password", engine -> result);

  CollectServiceTest() {
    store.createInstance(new InstanceRow("pg_prod", "postgresql",
        new ConnectionInfo("127.0.0.1", 5432, "db", "user", "SQLMASK_PG_PASSWORD", "disable",
            10, List.of("public"), false), 1));
    store.createInstance(new InstanceRow("no_conn", "postgresql", null, 1));
  }

  @Test
  void collectReplacesStructureAndReportsCounts() {
    CollectResponse response = service.collect("pg_prod");
    assertEquals(1, response.tableCount());
    assertEquals(2, response.columnCount());
    assertEquals(1, response.warnings().size());
    assertEquals(2, response.metadataVersion());
    List<TableStructure> stored = store.loadStructure("pg_prod");
    assertEquals("bigint", stored.get(0).columns().get(0).type());
  }

  @Test
  void collectFailureLeavesStoredStructureUntouched() {
    service.collect("pg_prod");
    long versionBefore = store.findInstance("pg_prod").orElseThrow().metadataVersion();
    CollectService failing = new CollectService(instances, structures, ref -> "pw",
        engine -> spec -> {
          throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR, "db down");
        });
    assertThrows(SqlMaskException.class, () -> failing.collect("pg_prod"));
    assertEquals(versionBefore, store.findInstance("pg_prod").orElseThrow().metadataVersion());
    assertEquals(1, store.loadStructure("pg_prod").size());
  }

  @Test
  void instanceWithoutConnectionRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.collect("no_conn"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void connectionSpecBuiltWithStoredScope() {
    List<ConnectionSpec> captured = new java.util.ArrayList<>();
    CollectService capturing = new CollectService(instances, structures, ref -> "pw",
        engine -> spec -> {
          captured.add(spec);
          return result;
        });
    capturing.collect("pg_prod");
    ConnectionSpec spec = captured.get(0);
    assertEquals("postgresql", spec.engine());
    assertEquals("resolved-password", spec.password());
    assertEquals(false, spec.strict());
    assertEquals(List.of("public"), spec.schemas());
  }
}
```

（`CollectResponse` 在 `io.sqlmask.metaserver.service` 包可见性不足时，直接 import `io.sqlmask.metaserver.web.MetadataDtos.CollectResponse`；本计划将 `CollectResponse` 放在 `MetadataDtos`，测试相应改为 `import io.sqlmask.metaserver.web.MetadataDtos.CollectResponse;` 并以 `MetadataDtos.CollectResponse` 引用。）

- [ ] **Step 3: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=CollectServiceTest`
Expected: COMPILATION ERROR。

- [ ] **Step 4: 实现 CredentialResolver、IntrospectorFactory、CollectService**

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/CredentialResolver.java`（接口）：

```java
package io.sqlmask.metaserver.service;

/** Resolves a stored password reference into the credential used for collection. */
@FunctionalInterface
public interface CredentialResolver {

  String resolve(String passwordRef);
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/EnvCredentialResolver.java`（默认实现）：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import org.springframework.stereotype.Service;

/**
 * Resolves a password by environment variable reference. The reference (the
 * variable name) may appear in errors; the resolved value never leaves this
 * call chain and is never logged.
 */
@Service
public class EnvCredentialResolver implements CredentialResolver {

  @Override
  public String resolve(String passwordRef) {
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; collection aborted");
    }
    return value;
  }
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/IntrospectorFactory.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.introspect.MetadataIntrospector;

/** Indirection over the static engine registry so tests can inject fakes. */
@FunctionalInterface
public interface IntrospectorFactory {

  MetadataIntrospector byEngine(String engine);
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java`：

```java
package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.web.MetadataDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls live metadata through the stored connection reference and replaces the
 * instance structure atomically. A failed collection never touches the stored
 * structure (spec §6: the source of the data-plane self-consistency).
 */
@Service
public class CollectService {

  private final MetadataService instances;
  private final StructureService structures;
  private final CredentialResolver credentials;
  private final IntrospectorFactory introspectors;

  public CollectService(MetadataService instances, StructureService structures,
      CredentialResolver credentials, IntrospectorFactory introspectors) {
    this.instances = instances;
    this.structures = structures;
    this.credentials = credentials;
    this.introspectors = introspectors;
  }

  public MetadataDtos.CollectResponse collect(String name) {
    InstanceRow row = instances.get(name);
    ConnectionInfo connection = row.connection();
    if (connection == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "' has no connection settings; PUT the connection first");
    }
    ConnectionSpec spec = new ConnectionSpec(row.dialect(), connection.host(), connection.port(),
        connection.database(), connection.dbUser(), credentials.resolve(connection.passwordRef()),
        connection.schemas(), connection.includeViews(), false, connection.sslmode(),
        connection.connectTimeoutSeconds());
    IntrospectionResult result = introspectors.byEngine(row.dialect()).introspect(spec);
    List<TableStructure> tables = toStructures(result);
    long version = structures.replace(name, tables);
    return new MetadataDtos.CollectResponse(tables.size(),
        tables.stream().mapToInt(t -> t.columns().size()).sum(), result.warnings(), version);
  }

  private static List<TableStructure> toStructures(IntrospectionResult result) {
    List<TableStructure> tables = new ArrayList<>();
    for (IntrospectionResult.TableInfo table : result.tables()) {
      List<TableStructure.ColumnStructure> columns = new ArrayList<>();
      for (IntrospectionResult.ColumnInfo column : table.columns()) {
        columns.add(new TableStructure.ColumnStructure(column.name(), column.yamlType()));
      }
      tables.add(new TableStructure(table.catalog(), table.schema(), table.name(), columns));
    }
    return tables;
  }
}
```

`MetadataServerConfig` 追加默认工厂 bean：

```java
  @Bean
  public io.sqlmask.metaserver.service.IntrospectorFactory introspectorFactory() {
    return io.sqlmask.introspect.MetadataIntrospectors::byEngine;
  }
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=CollectServiceTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 写 controller 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/CollectControllerTest.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.metaserver.service.CredentialResolver;
import io.sqlmask.metaserver.service.IntrospectorFactory;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "metadata.api-key=test-key")
@AutoConfigureMockMvc
class CollectControllerTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private InMemoryMetaStore store;
  @MockBean
  private IntrospectorFactory introspectorFactory;
  @MockBean
  private CredentialResolver credentialResolver;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }
  }

  @BeforeEach
  void seed() {
    when(credentialResolver.resolve(any())).thenReturn("pw");
    store.instances.clear();
    store.structures.clear();
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_prod", "postgresql",
        new io.sqlmask.metaserver.model.ConnectionInfo("127.0.0.1", 5432, "db", "user",
            "SQLMASK_PG_PASSWORD", "disable", 10, List.of(), false), 1));
  }

  @Test
  void collectReturnsCountsAndWarnings() throws Exception {
    when(introspectorFactory.byEngine("postgresql")).thenReturn(spec ->
        new IntrospectionResult("db",
            List.of(new IntrospectionResult.TableInfo("db", "public", "customer",
                List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "int8", false)))),
            List.of()));
    mockMvc.perform(post("/api/instances/pg_prod/collect").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.metadataVersion").value(2));
  }

  @Test
  void collectFailureMapsTo502AndKeepsStructure() throws Exception {
    store.replaceStructure("pg_prod", List.of(new io.sqlmask.metaserver.model.TableStructure(
        "db", "public", "customer",
        List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint")))));
    when(introspectorFactory.byEngine("postgresql")).thenReturn(spec -> {
      throw new io.sqlmask.error.SqlMaskException(
          io.sqlmask.error.SqlMaskException.Code.INTROSPECT_ERROR, "db down");
    });
    mockMvc.perform(post("/api/instances/pg_prod/collect").header("X-Api-Key", KEY))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
    org.junit.jupiter.api.Assertions.assertEquals(1, store.loadStructure("pg_prod").size());
  }
}
```

- [ ] **Step 7: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=CollectControllerTest`
Expected: 失败（`/collect` 无映射）。

- [ ] **Step 8: 实现 CollectController**

`mask-metadata/src/main/java/io/sqlmask/metaserver/web/CollectController.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.service.CollectService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Collection trigger: uses the stored connection reference, never request-borne passwords. */
@RestController
@RequestMapping("/api/instances")
public class CollectController {

  private final CollectService collectService;

  public CollectController(CollectService collectService) {
    this.collectService = collectService;
  }

  @PostMapping("/{name}/collect")
  public MetadataDtos.CollectResponse collect(@PathVariable String name) {
    return collectService.collect(name);
  }
}
```

- [ ] **Step 9: 运行确认通过 + 全模块回归**

Run: `mvn -pl mask-metadata test`
Expected: BUILD SUCCESS（全部 mask-metadata 测试 PASS；`CredentialResolver` 的失败路径已由 `CollectServiceTest.instanceWithoutConnectionRejected` 与密码解析路径覆盖——补一条：`CollectServiceTest` 中构造 `CollectService(instances, structures, ref -> { throw new SqlMaskException(Code.METADATA_CREDENTIAL_UNAVAILABLE, "env missing"); }, ...)` 断言采集抛 `METADATA_CREDENTIAL_UNAVAILABLE` 且 version 不变）。

- [ ] **Step 10: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): 密码引用解析与采集端点（失败不覆盖存量结构）"
```

---

### Task 8: 数据面端点与契约测试

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDataController.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDtos.java`（追加数据面 DTO）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataDataControllerTest.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/DataPlaneContractTest.java`

**Interfaces:**
- Consumes: `MetadataService`、`StructureService.load`、core `DialectProfiles`、`TableMetadata`、`MaskingConfig`、`LoadedConfig`、`YamlCalciteSchemaFactory`。
- Produces:
  - `GET /api/metadata/instances` → `[{name, dialect, metadataVersion}]`；
  - `GET /api/metadata/instances/{name}` → `{instance, dialect, metadataVersion, tables: [{catalog, schema, name, columns: [{name, type}]}]}`（`rowFilter` 故意不出现在产物中）；
  - `GET /api/metadata/instances/{name}/version` → `{instance, metadataVersion}`；
  - 契约测试固定：产物 JSON 每列通过对应 dialect `TypeResolver.parseColumn`，并能组装成 core `LoadedConfig` 且 `YamlCalciteSchemaFactory.create` 可建 schema。

- [ ] **Step 1: 写 DTO 追加**

`MetadataDtos` 追加：

```java
  public record MetadataResponse(String instance, String dialect, long metadataVersion,
      List<TablePayload> tables) {
  }

  public record TablePayload(String catalog, String schema, String name,
      List<ColumnPayload> columns) {
  }

  public record ColumnPayload(String name, String type) {
  }

  public record VersionResponse(String instance, long metadataVersion) {
  }
```

- [ ] **Step 2: 写 controller 失败测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataDataControllerTest.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "metadata.api-key=test-key")
@AutoConfigureMockMvc
class MetadataDataControllerTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private InMemoryMetaStore store;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }
  }

  @BeforeEach
  void seed() {
    store.instances.clear();
    store.structures.clear();
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_prod", "postgresql",
        null, 1));
    store.replaceStructure("pg_prod", List.of(
        new io.sqlmask.metaserver.model.TableStructure("crm", "public", "customer",
            List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint"),
                new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("phone", "varchar"))),
        new io.sqlmask.metaserver.model.TableStructure("crm", "public", "orders",
            List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint")))));
    store.instances.put("pg_prod", store.instances.get("pg_prod").withVersion(7));
  }

  @Test
  void servesInstanceList() throws Exception {
    mockMvc.perform(get("/api/metadata/instances").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("pg_prod"))
        .andExpect(jsonPath("$[0].dialect").value("postgresql"))
        .andExpect(jsonPath("$[0].metadataVersion").value(7));
  }

  @Test
  void servesStructureWithoutRowFilter() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/pg_prod").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(7))
        .andExpect(jsonPath("$.tables.length()").value(2))
        .andExpect(jsonPath("$.tables[0].columns[1].type").value("varchar"))
        .andExpect(jsonPath("$.rowFilter").doesNotExist())
        .andExpect(jsonPath("$.tables[0].rowFilter").doesNotExist());
  }

  @Test
  void servesVersionOnly() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/pg_prod/version").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(7));
  }

  @Test
  void unknownInstanceIs404() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/ghost").header("X-Api-Key", KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
  }
}
```

为支持 `withVersion`，给 `InstanceRow` 追加一个便捷方法（`mask-metadata/src/main/java/io/sqlmask/metaserver/model/InstanceRow.java`）：

```java
  public InstanceRow withVersion(long newVersion) {
    return new InstanceRow(name, dialect, connection, newVersion);
  }
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -pl mask-metadata test -Dtest=MetadataDataControllerTest`
Expected: 失败（`/api/metadata` 无映射）。

- [ ] **Step 4: 实现 MetadataDataController**

`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDataController.java`：

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.StructureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Data plane for consumers such as the policy service (spec §4.2). The payload
 * intentionally carries no rowFilter: row filters are policy-domain data.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataDataController {

  private final MetadataService instances;
  private final StructureService structures;

  public MetadataDataController(MetadataService instances, StructureService structures) {
    this.instances = instances;
    this.structures = structures;
  }

  @GetMapping("/instances")
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.metadataVersion()))
        .toList();
  }

  @GetMapping("/instances/{name}")
  public MetadataDtos.MetadataResponse structure(@PathVariable String name) {
    InstanceRow row = instances.get(name);
    List<TableStructure> tables = structures.load(name);
    return new MetadataDtos.MetadataResponse(row.name(), row.dialect(), row.metadataVersion(),
        tables.stream().map(MetadataDataController::toPayload).toList());
  }

  @GetMapping("/instances/{name}/version")
  public MetadataDtos.VersionResponse version(@PathVariable String name) {
    InstanceRow row = instances.get(name);
    return new MetadataDtos.VersionResponse(row.name(), row.metadataVersion());
  }

  private static MetadataDtos.TablePayload toPayload(TableStructure table) {
    return new MetadataDtos.TablePayload(table.catalog(), table.schema(), table.name(),
        table.columns().stream()
            .map(c -> new MetadataDtos.ColumnPayload(c.name(), c.type()))
            .toList());
  }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=MetadataDataControllerTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 写契约测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/DataPlaneContractTest.java`：

```java
package io.sqlmask.metaserver.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The served JSON must deserialize into the core configuration model and feed
 * the existing rewrite pipeline (schema construction) unchanged.
 */
@SpringBootTest(properties = "metadata.api-key=test-key")
@AutoConfigureMockMvc
class DataPlaneContractTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper json;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      InMemoryMetaStore store = new InMemoryMetaStore();
      store.createInstance(new InstanceRow("pg_prod", "postgresql", null, 3));
      store.replaceStructure("pg_prod", List.of(new TableStructure("crm", "public", "customer",
          List.of(new TableStructure.ColumnStructure("id", "bigint"),
              new TableStructure.ColumnStructure("phone", "varchar(20)")))));
      store.instances.put("pg_prod", store.instances.get("pg_prod").withVersion(3));
      return store;
    }
  }

  @Test
  void servedPayloadFeedsCorePipeline() throws Exception {
    String body = mockMvc.perform(get("/api/metadata/instances/pg_prod")
            .header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    MetadataDtos.MetadataResponse payload = json.readValue(body,
        MetadataDtos.MetadataResponse.class);
    assertEquals("postgresql", payload.dialect());

    List<TableMetadata> tables = new ArrayList<>();
    for (MetadataDtos.TablePayload table : payload.tables()) {
      List<TableMetadata.Column> columns = new ArrayList<>();
      for (MetadataDtos.ColumnPayload column : table.columns()) {
        columns.add(DialectProfiles.byName(payload.dialect()).typeResolver()
            .parseColumn(column.name(), column.type()));
      }
      tables.add(new TableMetadata(table.catalog(), table.schema(), table.name(), columns));
    }
    LoadedConfig loaded = new LoadedConfig(
        new MaskingConfig(tables, List.of(), Map.of()));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    assertNotNull(schema.getSubSchema("crm"));
  }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `mvn -pl mask-metadata test -Dtest=DataPlaneContractTest`
Expected: PASS（若 core 对 `MaskingConfig`/`LoadedConfig` 构造有非空约束导致 `List.of()` 不可用，按 core 构造器要求补最小合法值——以编译/运行报错为准调整，不改断言语义）。

- [ ] **Step 8: Commit**

```bash
git add mask-metadata mask-metadata/src/main/java/io/sqlmask/metaserver/model/InstanceRow.java
git commit -m "feat(metadata): 数据面端点（tables 段等价 JSON）与 core 管线契约测试"
```

---

### Task 9: mask-policy 的 MetadataClient（拉取原语）

**Files:**
- Modify: `mask-policy/pom.xml`（追加 `mask-core` 依赖）
- Create: `mask-policy/src/main/java/io/sqlmask/policy/metadata/MetadataClient.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policy/metadata/MetadataClientTest.java`

**Interfaces:**
- Consumes: Task 8 的数据面 JSON（`{instance, dialect, metadataVersion, tables}`）、core `SqlMaskException`（`METADATA_*` 码已由 Task 2 加入）。
- Produces（策略服务后续接线依赖的原语；缓存/轮询/传导按 spec §5 归策略微服务实现计划）:
  - `MetadataClient(String baseUrl, String apiKey)`；
  - `OptionalLong versionOf(String instance)`：200 → 版本；404 → 空；401 → `SqlMaskException(CONFIG_ERROR)`；其他/不可达 → `SqlMaskException(METADATA_SERVICE_UNAVAILABLE)`；
  - `MetadataSnapshot fetch(String instance)`：404 → `SqlMaskException(METADATA_INSTANCE_NOT_FOUND)`；
  - `record MetadataSnapshot(String instance, String dialect, long metadataVersion, List<TableSnapshot> tables)`、`record TableSnapshot(String catalog, String schema, String name, List<ColumnSnapshot> columns)`、`record ColumnSnapshot(String name, String type)`（JSON 字段名与 Task 8 产物逐字对齐）。

- [ ] **Step 1: mask-policy pom 追加 core 依赖**

`mask-policy/pom.xml` 的 `<dependencies>` 追加：

```xml
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
```

- [ ] **Step 2: 写失败测试**

`mask-policy/src/test/java/io/sqlmask/policy/metadata/MetadataClientTest.java`：

```java
package io.sqlmask.policy.metadata;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataClientTest {

  private HttpServer server;
  private MetadataClient client;
  private String lastApiKey;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/metadata/instances/pg_prod/version", exchange -> {
      lastApiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
      if (!"secret".equals(lastApiKey)) {
        exchange.sendResponseHeaders(401, -1);
        return;
      }
      byte[] body = "{\"instance\":\"pg_prod\",\"metadataVersion\":7}".getBytes(
          StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.createContext("/api/metadata/instances/pg_prod", exchange -> {
      byte[] body = """
          {"instance":"pg_prod","dialect":"postgresql","metadataVersion":7,
           "tables":[{"catalog":"crm","schema":"public","name":"customer",
                      "columns":[{"name":"id","type":"bigint"}]}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.createContext("/api/metadata/instances/ghost/version", exchange ->
        exchange.sendResponseHeaders(404, -1));
    server.createContext("/api/metadata/instances/ghost", exchange ->
        exchange.sendResponseHeaders(404, -1));
    server.start();
    client = new MetadataClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void versionOfReturnsVersion() {
    assertEquals(OptionalLong.of(7), client.versionOf("pg_prod"));
  }

  @Test
  void versionOfUnknownInstanceIsEmpty() {
    assertEquals(OptionalLong.empty(), client.versionOf("ghost"));
  }

  @Test
  void fetchReturnsSnapshot() {
    MetadataClient.MetadataSnapshot snapshot = client.fetch("pg_prod");
    assertEquals("postgresql", snapshot.dialect());
    assertEquals(7, snapshot.metadataVersion());
    assertEquals("customer", snapshot.tables().get(0).name());
    assertEquals("bigint", snapshot.tables().get(0).columns().get(0).type());
  }

  @Test
  void fetchUnknownInstanceThrowsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> client.fetch("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void wrongApiKeyFailsClosed() {
    MetadataClient bad = new MetadataClient(
        "http://127.0.0.1:" + server.getAddress().getPort(), "wrong");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> bad.fetch("pg_prod"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void unreachableServiceFailsClosed() {
    MetadataClient dead = new MetadataClient("http://127.0.0.1:1", "secret");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> dead.fetch("pg_prod"));
    assertEquals(SqlMaskException.Code.METADATA_SERVICE_UNAVAILABLE, e.getCode());
    assertTrue(e.getMessage().contains("metadata service"));
  }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -pl mask-policy test -Dtest=MetadataClientTest`
Expected: COMPILATION ERROR。

- [ ] **Step 4: 实现 MetadataClient**

`mask-policy/src/main/java/io/sqlmask/policy/metadata/MetadataClient.java`：

```java
package io.sqlmask.policy.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/**
 * Pull primitive over the metadata service data plane. Mirrors
 * PolicyServiceConfigSource's transport discipline (X-Api-Key, mapped status
 * codes, fail-closed on unreachable). Caching/polling/propagation live with
 * the policy compiler, not here.
 */
public final class MetadataClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public MetadataClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** 200 → version; 404 → empty; 401/other/unreachable → fail-closed exceptions. */
  public OptionalLong versionOf(String instance) {
    HttpResponse<String> response = send(request(URI.create(
        base + "/api/metadata/instances/" + instance + "/version")));
    if (response.statusCode() == 404) {
      return OptionalLong.empty();
    }
    requireOk(response, instance);
    try {
      VersionPayload payload = JSON.readValue(response.body(), VersionPayload.class);
      return OptionalLong.of(payload.metadataVersion());
    } catch (IOException e) {
      throw unavailable("metadata service returned an unreadable version payload", e);
    }
  }

  public MetadataSnapshot fetch(String instance) {
    HttpResponse<String> response = send(request(URI.create(
        base + "/api/metadata/instances/" + instance)));
    if (response.statusCode() == 404) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
          "metadata instance '" + instance + "' does not exist on the metadata service");
    }
    requireOk(response, instance);
    try {
      return JSON.readValue(response.body(), MetadataSnapshot.class);
    } catch (IOException e) {
      throw unavailable("metadata service returned an unreadable structure payload", e);
    }
  }

  private HttpRequest request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw unavailable("metadata service unreachable at '" + request.uri() + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted while calling the metadata service", e);
    }
  }

  private static void requireOk(HttpResponse<String> response, String instance) {
    int code = response.statusCode();
    if (code == 401) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadata service rejected the configured API key (HTTP 401)");
    }
    if (code != 200) {
      throw unavailable("metadata service returned HTTP " + code
          + " for instance '" + instance + "'", null);
    }
  }

  private static SqlMaskException unavailable(String message, Throwable cause) {
    return new SqlMaskException(SqlMaskException.Code.METADATA_SERVICE_UNAVAILABLE, message, cause);
  }

  public record MetadataSnapshot(String instance, String dialect, long metadataVersion,
      List<TableSnapshot> tables) {
  }

  public record TableSnapshot(String catalog, String schema, String name,
      List<ColumnSnapshot> columns) {
  }

  public record ColumnSnapshot(String name, String type) {
  }

  record VersionPayload(String instance, long metadataVersion) {
  }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl mask-policy test`
Expected: BUILD SUCCESS（新 6 用例 PASS，既有 mask-policy 测试零回归）。

- [ ] **Step 6: Commit**

```bash
git add mask-policy
git commit -m "feat(policy): MetadataClient 拉取原语（X-Api-Key、404/401/不可达 fail-closed）"
```

---

### Task 10: 全量回归、compose 与文档

**Files:**
- Create: `docker-compose.metadata.yml`
- Modify: `README.md`（追加"元数据微服务"章节）

**Interfaces:**
- Consumes: Task 1–9 全部产物。
- Produces: 一键本地起 metadata + PG 的 compose 文件；README 使用说明（含 `password_ref` 环境变量注入示例）。

- [ ] **Step 1: 全仓回归**

Run: `mvn test`
Expected: BUILD SUCCESS；mask-core、mask-policy、mask-metadata 三模块全部测试 PASS（core 的 CLI `--pull-metadata`、`/api/metadata/pull`、golden 零变化——这是 spec §8.1 的回归证据）。

- [ ] **Step 2: 写 compose 文件**

`docker-compose.metadata.yml`：

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: mask_metadata
    ports:
      - "5432:5432"

  metadata:
    build:
      context: .
      dockerfile: docker/metadata.Dockerfile
    environment:
      METADATA_API_KEY: local-dev-key
      METADATA_PG_URL: jdbc:postgresql://postgres:5432/mask_metadata
      METADATA_PG_USER: postgres
      METADATA_PG_PASSWORD: postgres
      # password_ref 指向的环境变量由部署侧注入，例如：
      # SQLMASK_DS_PG_PROD_PASSWORD: s3cret
    ports:
      - "8082:8082"
    depends_on:
      - postgres
```

`docker/metadata.Dockerfile`：

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-metadata/target/mask-metadata-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: README 追加章节**

`README.md` 末尾追加（示例段落，按现有 README 语言风格并入）：

```markdown
## 元数据微服务（mask-metadata，8082）

引擎实例 + 表结构的唯一事实源：实例 CRUD、三引擎采集（复用 core introspect）、
YAML 导入、数据面 `GET /api/metadata/instances/{name}`（tables 段等价 JSON）。
策略服务按版本轮询拉取；拉不到用上个 version（stale-but-available）。

本地起套：`mvn -pl mask-metadata -am package && docker compose -f docker-compose.metadata.yml up`
（需先 `mvn -pl mask-metadata -am package` 生成 fat jar）。

- 鉴权：`X-Api-Key`（服务端 Key 来自 `METADATA_API_KEY`；未配置 = 全 401）
- 密码：实例只登记环境变量名（`passwordRef`，推荐 `SQLMASK_DS_<INSTANCE>_PASSWORD`），
  采集时服务端解析；密码不落库、不进日志、不进 URL
- 导入：`POST /api/instances/import` 只吃 `metadata.tables`；表声明含 `rowFilter`
  字段会被 400 拒绝——行过滤请在策略服务配置为 row_filter 策略
```

- [ ] **Step 4: 冒烟验证（本机有 docker 时）**

Run: `mvn -pl mask-metadata -am package -DskipTests && docker compose -f docker-compose.metadata.yml up -d && curl -s -H "X-Api-Key: local-dev-key" http://127.0.0.1:8082/api/metadata/instances && docker compose -f docker-compose.metadata.yml down`
Expected: `curl` 返回 `[]`。无 docker 环境时如实跳过并记录（不阻塞合并，mock 契约测试已覆盖）。

- [ ] **Step 5: Commit**

```bash
git add docker-compose.metadata.yml docker/metadata.Dockerfile README.md
git commit -m "docs(metadata): 本地 compose 一键起套与 README 使用说明"
```

---

## 范围边界（写给执行者）

本计划交付：可独立运行的 `mask-metadata` 服务 + mask-policy 的 `MetadataClient` 拉取原语。**不在本计划内**（依赖尚未建成的策略编译栈，属策略微服务实现计划，按 spec §9 修订执行）：

1. 策略服务策略 CRUD 挂 `instance_name`（替换 core `policyserver` 的实例存储——其依赖归位裁决一并处理）；
2. `MetadataClient` 的缓存/轮询/`configVersion` 传导与 `/api/effective` 编译；
3. 三件套端到端（compose 起 metadata + policy + rewrite）。

## Self-Review 记录

- Spec 覆盖：§2 模块形态（Task 1）、§3 数据模型/版本/密码引用（Task 3/7）、§4.1 管理面（Task 4/6/7）、§4.2 数据面（Task 8）、§4.3 错误码（Task 2）、§5 的可执行子集 `MetadataClient`（Task 9，缓存/传导显式划出）、§6 错误安全（Task 2/3/7）、§7 部署（Task 10）、§8.1–8.3 测试（各任务 TDD + Task 8 契约 + Task 10 回归）；§8.5 三件套端到端依赖策略编译栈，已显式移入范围边界；
- 占位符：无 TBD；Task 5 的 `not-a-type`、Task 8 的构造器约束两处标注了"以实际报错为准"的调整规则，断言语义固定；
- 类型一致性：`ConnectionInfo.ofNullable`（Task 4 定义、Task 6 controller 调用私有转发）、`StructureService.replace(String, List)`（Task 5 定义、Task 6/7 调用）、`CollectResponse`/`MetadataResponse` 等 DTO 字段名与 Task 9 客户端快照 JSON 逐字对齐；`InstanceRow.withVersion` 在 Task 8 引入（测试夹具用，非生产路径）。
