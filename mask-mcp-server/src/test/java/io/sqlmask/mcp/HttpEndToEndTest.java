package io.sqlmask.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpEndToEndTest {

  private AutoCloseable server;

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void fullRoundTripOverStreamableHttp() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS);

    try (McpSyncClient client = McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .build())
        .requestTimeout(java.time.Duration.ofSeconds(20))
        .build()) {
      client.initialize();
      assertEquals(3, client.listTools().tools().size());

      Map<String, Object> args = new HashMap<>();
      args.put("metadataYaml", Fixtures.METADATA);
      args.put("policyYaml", Fixtures.MASK_POLICIES);
      args.put("sql", Fixtures.SQL);
      args.put("dialect", "postgresql");
      args.put("user", "alice");
      McpSchema.CallToolResult r = client.callTool(
          McpSchema.CallToolRequest.builder("rewrite_sql").arguments(args).build());
      assertFalse(r.isError());
      assertTrue(((McpSchema.TextContent) r.content().get(0)).text().contains("mask_phone"));
    }
  }
}
