package io.sqlmask.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.util.EnumSet;
import java.util.List;

/** Streamable HTTP facade on embedded Jetty (zero Spring, jakarta.servlet). */
public final class JettyHttpServer {

  /**
   * Non-blocking core for tests: returns a handle that stops server + transport.
   * apiKey == null/blank means "not configured" -- the filter rejects everything (fail-closed).
   */
  public static AutoCloseable start(int port, List<McpTool> tools, String apiKey) throws Exception {
    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(McpJsonDefaults.getMapper())
            .mcpEndpoint("/mcp")
            .build();
    var mcpServer = McpServerFactory.build(transport, tools);

    ServletContextHandler ctx = new ServletContextHandler();
    ctx.setContextPath("/");
    ctx.addServlet(new ServletHolder(transport), "/mcp");
    // 无条件挂载：未配置 key（null）时拒绝一切的逻辑在 ApiKeyFilter 内部。
    ctx.addFilter(
        new FilterHolder(new ApiKeyFilter(apiKey)),
        "/mcp", EnumSet.of(DispatcherType.REQUEST));

    Server jetty = new Server(port);
    jetty.setHandler(ctx);
    try {
      jetty.start();
    } catch (Throwable t) {
      try {
        mcpServer.close();
      } catch (Exception suppressed) {
        t.addSuppressed(suppressed);
      }
      throw t;
    }
    return () -> {
      try {
        jetty.stop();
      } finally {
        mcpServer.close();
      }
    };
  }

  /** Blocking entry used by Main --transport http (key from MASK_MCP_API_KEY). */
  public static void run(int port, List<McpTool> tools, String apiKey) throws Exception {
    start(port, tools, apiKey);
    Thread.currentThread().join();
  }

  private JettyHttpServer() {
  }
}
