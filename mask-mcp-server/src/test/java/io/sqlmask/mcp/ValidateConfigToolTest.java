package io.sqlmask.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateConfigToolTest {

  private final ValidateConfigTool tool = new ValidateConfigTool();

  private static String text(McpSchema.CallToolResult r) {
    return ((McpSchema.TextContent) r.content().get(0)).text();
  }

  @Test
  void validMetadataAndPolicyPass() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "metadataYaml", Fixtures.METADATA,
        "policyYaml", Fixtures.MASK_POLICIES,
        "dialect", "postgresql"));
    assertFalse(r.isError());
    assertTrue(text(r).contains("\"valid\":true"), text(r));
    assertTrue(text(r).contains("\"errors\":[]"), text(r));
  }

  @Test
  void brokenMetadataReportsSource() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "metadataYaml", "metadata:\n  tables: [",
        "dialect", "trino"));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"valid\":false"), json);
    assertTrue(json.contains("\"source\":\"metadata\""), json);
  }

  @Test
  void brokenPolicyReportsSource() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "policyYaml", "policies:\n  - name: [",
        "dialect", "postgresql"));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"valid\":false"), json);
    assertTrue(json.contains("\"source\":\"policy\""), json);
  }

  @Test
  void neitherInputIsConfigError() {
    McpSchema.CallToolResult r = tool.call(Map.of("dialect", "postgresql"));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\""), text(r));
  }
}
