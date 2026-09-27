package io.sqlmask.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawns the real server as a subprocess (same classpath as the test JVM) and
 * drives it over stdio: initialize -> listTools -> callTool.
 */
class StdioEndToEndTest {

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void fullRoundTripOverStdio() throws Exception {
    String cp = System.getProperty("java.class.path");
    try (McpSyncClient client = McpClient.sync(
            new StdioClientTransport(
                ServerParameters.builder("java")
                    .args("-cp", cp, "io.sqlmask.mcp.Main")
                    .build(),
                /* 校准点 2 同款 mapper */ McpJsonDefaults.getMapper()))
        .requestTimeout(java.time.Duration.ofSeconds(30))
        .build()) {
      client.initialize();

      var tools = client.listTools();
      assertEquals(3, tools.tools().size());

      Map<String, Object> args = new HashMap<>();
      args.put("metadataYaml", Fixtures.METADATA);
      args.put("policyYaml", Fixtures.MASK_POLICIES);
      args.put("sql", Fixtures.SQL);
      args.put("dialect", "postgresql");
      args.put("user", "alice");
      McpSchema.CallToolResult r = client.callTool(
          McpSchema.CallToolRequest.builder("rewrite_sql")
              .arguments(args)
              .build());
      assertFalse(r.isError());
      String json = ((McpSchema.TextContent) r.content().get(0)).text();
      assertTrue(json.contains("mask_phone"), json);
    }
  }
}
