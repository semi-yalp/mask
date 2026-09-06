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
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
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
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
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
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":" ","user":"postgres","password":"pw"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void connectionFailureIsIntrospectError400() throws Exception {
    introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) throws java.sql.SQLException {
        throw new java.sql.SQLException("FATAL: password authentication failed");
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":"crm","user":"postgres","password":"bad"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
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
