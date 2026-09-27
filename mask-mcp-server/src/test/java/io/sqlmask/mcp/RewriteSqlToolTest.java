package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteSqlToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RewriteSqlTool tool = new RewriteSqlTool();

  private static Map<String, Object> args(String metadata, String policy, String sql,
      String dialect, String user) {
    Map<String, Object> m = new HashMap<>();
    m.put("metadataYaml", metadata);
    if (policy != null) {
      m.put("policyYaml", policy);
    }
    m.put("sql", sql);
    m.put("dialect", dialect);
    if (user != null) {
      m.put("user", user);
    }
    return m;
  }

  private static String text(McpSchema.CallToolResult r) {
    return ((McpSchema.TextContent) r.content().get(0)).text();
  }

  @Test
  void trinoRendersExpectedWrapper() throws Exception {
    // 确切输出对齐 MultiDialectRewriteTest.trinoWrapperRendersPlainLowercaseIdentifiers
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", "trino", null));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"masked\":true"), json);
    assertTrue(json.contains("mask_phone(r.phone, 3, 4)"), json);
    // 校准：内核渲染带真实换行（JSON 中转义为 \n / \r\n，且 \r\n 随平台行分隔符），
    // 与内核 MultiDialectRewriteTest 同法——取出值压平空白后再比对确切输出
    String rewritten = JSON.readTree(json)
        .get("statements").get(0).get("rewrittenSql").asText().replaceAll("\\s+", " ").trim();
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM ( "
        + "SELECT id, phone FROM customer ) AS r", rewritten);
    assertTrue(json.contains("\"unchanged\":false"), json);
  }

  @Test
  void mysqlBacktickQuotingSurvivesMapping() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", "mysql", null));
    assertFalse(r.isError());
    assertTrue(text(r).contains("`mask_phone`"), text(r));
  }

  @Test
  void postgresqlAndSparkSqlAcceptLegacyYaml() {
    for (String dialect : new String[] {"postgresql", "sparksql"}) {
      McpSchema.CallToolResult r = tool.call(
          args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", dialect, null));
      assertFalse(r.isError(), dialect);
      assertTrue(text(r).contains("mask_phone"), dialect);
    }
  }

  @Test
  void noPolicyStatementPassesThroughUnchanged() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id FROM customer", "trino", null));
    assertFalse(r.isError());
    assertTrue(text(r).contains("\"masked\":false"), text(r));
    assertTrue(text(r).contains("\"unchanged\":true"), text(r));
  }

  @Test
  void policyYamlWithSubjectMasks() {
    // 对齐 RewriteEnginePolicyTest.namedUserGetsMaskAndRowFilter 的输入形状（仅 mask 策略）
    Map<String, Object> a = new HashMap<>();
    a.put("metadataYaml", Fixtures.METADATA);
    a.put("policyYaml", Fixtures.MASK_POLICIES);
    a.put("sql", "SELECT phone FROM customer");
    a.put("dialect", "postgresql");
    a.put("user", "alice");
    a.put("groups", java.util.List.of("analyst"));
    McpSchema.CallToolResult r = tool.call(a);
    assertFalse(r.isError());
    assertTrue(text(r).contains("mask_phone"), text(r));
  }

  @Test
  void multipleStatementsReturnOneEntryEach() {
    McpSchema.CallToolResult r = tool.call(args(Fixtures.TRINO_YAML, null,
        "SELECT id FROM customer; SELECT phone FROM customer", "trino", null));
    assertFalse(r.isError());
    // 校准：内核 ordinal 为 1 基（错误信息同样按 statement 1/2 编号），工具透传不改写
    assertTrue(text(r).contains("\"ordinal\":1"), text(r));
    assertTrue(text(r).contains("\"ordinal\":2"), text(r));
  }

  @Test
  void invalidDialectIsConfigError() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT 1", "oracle", null));
    assertTrue(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"code\":"), json);
    assertFalse(json.contains("INTERNAL"), json);
  }

  @Test
  void brokenYamlKeepsKernelErrorCode() {
    McpSchema.CallToolResult r = tool.call(
        args("metadata:\n  tables: [", null, "SELECT 1", "trino", null));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\"")
        || text(r).contains("\"code\":\"PARSE_ERROR\""), text(r));
  }

  @Test
  void missingRequiredArgIsConfigError() {
    McpSchema.CallToolResult r = tool.call(Map.of("sql", "SELECT 1"));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\""), text(r));
  }
}
