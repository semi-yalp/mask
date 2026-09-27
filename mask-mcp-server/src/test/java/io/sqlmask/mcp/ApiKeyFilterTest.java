package io.sqlmask.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auth three-state over real HTTP: no key configured / wrong key / right key. */
class ApiKeyFilterTest {

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

  /**
   * 校准（javap 实证，mcp-core 2.0.0）：transport builder 无 addHeaderInterceptor，
   * 请求头注入经 httpRequestCustomizer——SAM 接口 customize(builder, method, uri, body, ctx)。
   */
  private static HttpClientStreamableHttpTransport.Builder transport(int port, String apiKey) {
    HttpClientStreamableHttpTransport.Builder builder =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp");
    if (apiKey != null) {
      builder.httpRequestCustomizer(
          (HttpRequest.Builder b, String method, URI uri, String body, McpTransportContext ctx) ->
              b.header("X-Api-Key", apiKey));
    }
    return builder;
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void missingConfiguredKeyRejectsEverything() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS, null); // 未配置 → 默认拒绝
    assertThrows(Exception.class, () -> connectExpectingFailure(port, null));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void wrongKeyIsRejectedAndRightKeyPasses() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS, "secret-1");
    assertThrows(Exception.class, () -> connectExpectingFailure(port, "secret-2"));
    try (McpSyncClient ok = McpClient.sync(transport(port, "secret-1").build())
        .requestTimeout(java.time.Duration.ofSeconds(20))
        .build()) {
      ok.initialize();
      assertTrue(ok.listTools().tools().size() == 3);
    }
  }

  /** No/incorrect key must fail at HTTP level (401), before MCP initialize. */
  private static void connectExpectingFailure(int port, String key) throws Exception {
    try (McpSyncClient c = McpClient.sync(transport(port, key).build())
        .requestTimeout(java.time.Duration.ofSeconds(10))
        .build()) {
      c.initialize(); // must throw: 401 on the MCP endpoint
    }
  }
}
