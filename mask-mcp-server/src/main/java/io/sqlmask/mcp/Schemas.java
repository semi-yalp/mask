package io.sqlmask.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;

/** JSON-schema helper: the only place that knows how the SDK ingests schemas. */
public final class Schemas {

  /** Parses a JSON Schema string into whatever Tool.builder accepts (SDK-calibrated). */
  public static Object parse(String json) {
    // 校准点 1：反序列化为 SDK 的 McpSchema.JsonSchema，经 builder.inputSchema 进入 Tool。
    try {
      return McpJsonDefaults.getMapper().readValue(json, McpSchema.JsonSchema.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid schema json", e);
    }
  }

  private Schemas() {
  }
}
