package io.sqlmask.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import java.util.List;

/** SDK assembly: the only place that touches SDK builders (calibration surface). */
public final class McpServerFactory {

  /** Builds a SyncToolSpecification wrapping the pure McpTool handler. */
  public static SyncToolSpecification specFor(McpTool tool) {
    return SyncToolSpecification.builder()
        .tool(McpSchema.Tool.builder(tool.name())
            .inputSchema((McpSchema.JsonSchema) Schemas.parse(tool.schemaJson()))
            .description(tool.description())
            .build())
        .callHandler((exchange, request) -> tool.call(request.arguments()))
        .build();
  }

  /** stdio server with the given tools (used by Task 6 end-to-end). */
  public static McpSyncServer stdio(List<McpTool> tools) {
    return register(McpServer.sync(
            new StdioServerTransportProvider(McpJsonDefaults.getMapper())),
        tools)
        .build();
  }

  /** Streamable-HTTP server wired to a servlet transport provider (Task 7). */
  public static McpSyncServer build(McpStreamableServerTransportProvider provider,
      List<McpTool> tools) {
    return register(McpServer.sync(provider), tools).build();
  }

  private static McpServer.SyncSpecification<?> register(McpServer.SyncSpecification<?> builder,
      List<McpTool> tools) {
    builder.serverInfo("sql-mask", "0.1.0")
        .capabilities(McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build());
    for (McpTool tool : tools) {
      builder.tools(specFor(tool));
    }
    return builder;
  }

  private McpServerFactory() {
  }
}
