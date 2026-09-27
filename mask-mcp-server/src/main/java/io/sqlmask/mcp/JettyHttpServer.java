package io.sqlmask.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.util.List;

/** Streamable HTTP facade on embedded Jetty (zero Spring, jakarta.servlet). */
public final class JettyHttpServer {

  /** Non-blocking core for tests: returns a handle that stops server + transport. */
  public static AutoCloseable start(int port, List<McpTool> tools) throws Exception {
    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(McpJsonDefaults.getMapper())
            .mcpEndpoint("/mcp")
            .build();
    var mcpServer = McpServerFactory.build(transport, tools);

    ServletContextHandler ctx = new ServletContextHandler();
    ctx.setContextPath("/");
    ctx.addServlet(new ServletHolder(transport), "/mcp");

    Server jetty = new Server(port);
    jetty.setHandler(ctx);
    jetty.start();
    return () -> {
      try {
        jetty.stop();
      } finally {
        mcpServer.close();
      }
    };
  }

  /** Blocking entry used by Main --transport http. */
  public static void run(int port, List<McpTool> tools) throws Exception {
    start(port, tools);
    Thread.currentThread().join();
  }

  private JettyHttpServer() {
  }
}
