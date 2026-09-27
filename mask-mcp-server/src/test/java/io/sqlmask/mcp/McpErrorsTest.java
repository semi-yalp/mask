package io.sqlmask.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpErrorsTest {

  @Test
  void sqlMaskExceptionKeepsKernelCode() {
    McpErrors.ApiError e = McpErrors.of(
        new SqlMaskException(SqlMaskException.Code.REWRITE_ERROR, "boom"));
    assertEquals("REWRITE_ERROR", e.code());
    assertEquals("boom", e.message());
  }

  @Test
  void illegalArgumentBecomesConfigError() {
    McpErrors.ApiError e = McpErrors.of(new IllegalArgumentException("bad dialect"));
    assertEquals("CONFIG_ERROR", e.code());
    assertEquals("bad dialect", e.message());
  }

  @Test
  void anythingElseIsInternal() {
    assertEquals("INTERNAL", McpErrors.of(new RuntimeException("x")).code());
  }

  @Test
  void errorResultIsFlaggedAndCarriesJson() {
    McpSchema.CallToolResult r =
        McpErrors.errorResult(new McpErrors.ApiError("CONFIG_ERROR", "bad", null));
    assertTrue(r.isError());
    McpSchema.TextContent text = (McpSchema.TextContent) r.content().get(0);
    assertTrue(text.text().contains("\"code\":\"CONFIG_ERROR\""));
    assertTrue(text.text().contains("\"details\":[]"));
  }

  @Test
  void okResultIsNotError() {
    McpSchema.CallToolResult r = McpErrors.ok("{\"a\":1}");
    assertFalse(r.isError());
    McpSchema.TextContent text = (McpSchema.TextContent) r.content().get(0);
    assertEquals("{\"a\":1}", text.text());
  }
}
