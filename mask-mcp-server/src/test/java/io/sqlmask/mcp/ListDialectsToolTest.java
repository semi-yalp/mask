package io.sqlmask.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListDialectsToolTest {

  @Test
  void listsAllFiveDialectsSorted() {
    McpSchema.CallToolResult r = new ListDialectsTool().call(Map.of());
    assertFalse(r.isError());
    String json = ((McpSchema.TextContent) r.content().get(0)).text();
    for (String d : new String[] {"hive", "mysql", "postgresql", "sparksql", "trino"}) {
      assertTrue(json.contains("\"name\":\"" + d + "\""), d + " missing in " + json);
    }
    assertTrue(json.indexOf("\"name\":\"hive\"") < json.indexOf("\"name\":\"trino\""), json);
    assertTrue(json.contains("\"description\":"), json);
  }
}
