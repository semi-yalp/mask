package io.sqlmask.mcp;

/** Entry point: --transport stdio|http (default stdio), --port (default 8084). */
public final class Main {

  record Config(String transport, int port) {
  }

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);
    // P1: stdio; Task 7 adds the http branch delegating to JettyHttpServer.run(cfg.port(), tools)
    throw new UnsupportedOperationException("wired in Task 6");
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
