package io.sqlmask.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * One MCP tool: name/description/schema plus a pure call function. Handlers
 * are plain classes so unit tests call them directly without any transport.
 */
public interface McpTool {

  String name();

  String description();

  /** JSON Schema (object) describing the call arguments. */
  String schemaJson();

  /** Never throws: failures come back as an isError CallToolResult via McpErrors. */
  McpSchema.CallToolResult call(Map<String, Object> arguments);
}
