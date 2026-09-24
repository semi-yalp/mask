package io.sqlmask.server;

import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.PgMetadataIntrospector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetadataControllerTest {

  private MockMvc mvc;
  private PgMetadataIntrospector introspector;

  @BeforeEach
  void setUp() {
    introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("not expected in web test");
      }
    };
    // 用可编程 stub 替换：见 introspector() 工厂
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static IntrospectionResult sample() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("extra", "varchar", "jsonb", true)))),
        List.of("column crm.public.customer.extra: PG type jsonb is not representable, degraded to varchar"));
  }

  @Test
  void returnsYamlCountsAndWarnings() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"host":"127.0.0.1","port":5432,"database":"crm","user":"postgres","password":"pw"}
        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalog").value("crm"))
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.columnCount").value(2))
        .andExpect(jsonPath("$.warnings.length()").value(1))
        .andExpect(jsonPath("$.yaml").value(org.hamcrest.Matchers.containsString("policies: {}")));
  }

  @Test
  void blankDatabaseIsConfigError() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":" ","user":"postgres","password":"pw"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void unknownEngineIsConfigError() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"engine":"oracle","database":"d","user":"u","password":"p"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message")
            .value("unsupported engine 'oracle' (supported: postgresql, mysql, trino)"));
  }

  @Test
  void connectionFailureIsIntrospectError400() throws Exception {
    introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) throws java.sql.SQLException {
        throw new java.sql.SQLException("FATAL: password authentication failed");
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":"crm","user":"postgres","password":"bad"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
  }

  @Test
  void mixedCaseEnginePassesRegistryAndReachesConnection() throws Exception {
    // 引擎名经注册表 trim+小写归一化（与 CLI 一致）后分发到 mysql 采集器：
    // 本机不可达端口上连接被拒表现为 INTROSPECT_ERROR；若归一化回归，
    // 则会在注册表处提前报 CONFIG_ERROR
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"engine":" MySQL ","host":"127.0.0.1","port":1,"database":"d","user":"u","password":"p"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
  }

  @Test
  void linkLocalHostIsDeniedByDefault() throws Exception {
    // 云元数据端点（169.254.169.254）默认拒绝：pull 不得充当内网/云凭证探针
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"host":"169.254.169.254","database":"d","user":"u","password":"p"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void linkLocalGuardCanBeSwitchedOff() throws Exception {
    // off 策略下守卫放行（stub introspector 不触网，连接行为另行验收）
    PgMetadataIntrospector offStub = new PgMetadataIntrospector() {
      @Override public IntrospectionResult introspect(io.sqlmask.introspect.ConnectionSpec spec) {
        return sample();
      }
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("open() must not be called; introspect() is stubbed");
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(offStub, "off"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"host":"169.254.169.254","database":"d","user":"u","password":"p"}
        """))
        .andExpect(status().isOk());
  }

  @Test
  void sslmodeIsPassedThroughToTheSpec() throws Exception {
    // 请求体 sslmode 流入 ConnectionSpec（此前控制器硬编码 "disable"）
    java.util.concurrent.atomic.AtomicReference<io.sqlmask.introspect.ConnectionSpec> captured =
        new java.util.concurrent.atomic.AtomicReference<>();
    PgMetadataIntrospector capturing = new PgMetadataIntrospector() {
      @Override public IntrospectionResult introspect(io.sqlmask.introspect.ConnectionSpec spec) {
        captured.set(spec);
        return sample();
      }
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("open() must not be called; introspect() is stubbed");
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(capturing, "link-local"))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"host":"127.0.0.1","database":"d","user":"u","password":"p","sslmode":"require"}
        """))
        .andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertEquals("require", captured.get().sslmode());
  }

  private PgMetadataIntrospector stubReturning(IntrospectionResult result) {
    return new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("open() must not be called; introspect() is stubbed");
      }
      @Override public IntrospectionResult introspect(io.sqlmask.introspect.ConnectionSpec spec) {
        return result;
      }
    };
  }
}
