package io.sqlmask.query.web;

import io.sqlmask.query.service.QueryModels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// base-url 启动期校验（非空白）要求哑值，否则默认上下文因空配置拒绝启动
@SpringBootTest(properties = {
    "upstream.metadata-base-url=http://localhost:1",
    "upstream.rewrite-base-url=http://localhost:1"})
// addFilters=false：common ApiKeyFilter 在未配置 SQLMASK_QUERY_API_KEY 时 fail-closed，
// 全上下文测试统一绕过（过滤器自身语义由 common ApiKeyFilterTest 覆盖）。
@AutoConfigureMockMvc(addFilters = false)
@Import(QueryControllerTest.Stubs.class)
class QueryControllerTest {

  @Autowired MockMvc mockMvc;

  @TestConfiguration
  static class Stubs {
    static final java.sql.Statement STATEMENT =
        org.mockito.Mockito.mock(java.sql.Statement.class);

    @Bean
    io.sqlmask.query.service.QueryService queryService() throws Exception {
      var directory = org.mockito.Mockito.mock(io.sqlmask.query.service.QueryService.InstanceDirectory.class);
      var rewrites = org.mockito.Mockito.mock(io.sqlmask.query.rewrite.RewriteServiceClient.class);
      var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
      var statement = STATEMENT;
      var resultSet = org.mockito.Mockito.mock(java.sql.ResultSet.class);
      var meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
      org.mockito.Mockito.when(directory.fetch("pg")).thenReturn(
          new io.sqlmask.query.metadata.MetadataServiceClient.InstanceView("pg", "postgresql",
              "postgresql", 1, new io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView(
                  "h", 5432, "db", "u", "REF", "disable", 10)));
      org.mockito.Mockito.when(rewrites.rewrite(org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()))
          .thenReturn(new io.sqlmask.query.rewrite.RewrittenQuery(
              java.util.List.of(new io.sqlmask.query.rewrite.StatementView(
                  1, "SELECT phone FROM customer", "SELECT mask_phone(r.phone,3,4) ...",
                  true, false, "SELECT"))));
      org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
      org.mockito.Mockito.when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(resultSet);
      org.mockito.Mockito.when(resultSet.getMetaData()).thenReturn(meta);
      org.mockito.Mockito.when(meta.getColumnCount()).thenReturn(1);
      org.mockito.Mockito.when(meta.getColumnLabel(1)).thenReturn("phone");
      org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("varchar");
      org.mockito.Mockito.when(resultSet.next()).thenReturn(true, false);
      org.mockito.Mockito.when(resultSet.getObject(1)).thenReturn("138****0001");
      return new io.sqlmask.query.service.QueryService(directory, rewrites,
          new io.sqlmask.query.config.QueryProperties(null, null, null, null, null),
          (engine, c, password) -> connection, ref -> "pw");
    }
  }

  @Test
  void happyPathReturnsMaskedRows() throws Exception {
    // WebAsyncTask 在 MockMvc 下先启动异步，再用 asyncDispatch 取回真实响应
    var mvcResult = mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg\",\"sql\":\"SELECT phone FROM customer\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg"))
        .andExpect(jsonPath("$.engine").value("postgresql"))
        .andExpect(jsonPath("$.rowCount").value(1))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.rewrittenSql").doesNotExist());
  }

  @Test
  void missingInstanceFailsWithConfigError() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void includeRewrittenSqlEchoesRewriteAndClampsMaxRows() throws Exception {
    var mvcResult = mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg\",\"sql\":\"SELECT phone FROM customer\","
                + "\"maxRows\":999999,\"includeRewrittenSql\":true}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rewrittenSql").value("SELECT mask_phone(r.phone,3,4) ..."));
    // maxRows 999999 超过硬上限 10000 → 生效 10000 → setMaxRows(10001)
    org.mockito.Mockito.verify(Stubs.STATEMENT).setMaxRows(10001);
  }

  @Test
  void nonPositiveMaxRowsFailsWithConfigError() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg\",\"sql\":\"SELECT 1\",\"maxRows\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value("maxRows must be positive"));
  }
}
