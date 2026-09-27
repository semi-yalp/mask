package io.sqlmask.mcp;

import io.modelcontextprotocol.server.McpSyncServer;

import java.util.List;

/** Entry point: --transport stdio|http (default stdio), --port (default 8084). */
public final class Main {

  static final List<McpTool> TOOLS = List.of(
      new RewriteSqlTool(), new ValidateConfigTool(), new ListDialectsTool());

  record Config(String transport, int port) {
  }

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);
    if ("http".equals(cfg.transport())) {
      JettyHttpServer.run(cfg.port(), TOOLS);
      return;
    }
    if (!"stdio".equals(cfg.transport())) {
      throw new IllegalArgumentException("unknown transport: " + cfg.transport());
    }
    McpSyncServer server = McpServerFactory.stdio(TOOLS);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    // 校准（jstack 实证，mcp-core 2.0.0）：StdioServerTransportProvider 的 stdin
    // 读线程（"pool-N-thread-1"）是非 daemon 线程，且 build() 返回前已在运行——
    // 传输存活期间它自然撑住 JVM；stdin EOF 时该线程终止，JVM 随之退出并触发
    // shutdown hook 关闭 server。此前按任务简报写的 Thread.currentThread().join()
    // 是对自身 join（永不返回），EOF 后进程泄漏，已移除：main 直接返回即可。
  }

  static Config parseArgs(String[] args) {
    String transport = "stdio";
    int port = 8084;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--transport" -> transport = args[++i];
        case "--port" -> port = Integer.parseInt(args[++i]);
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    return new Config(transport, port);
  }

  private Main() {
  }
}
