package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.sqlmask.error.SqlMaskException;

import java.util.List;
import java.util.Map;

/** Kernel exceptions -> ApiError-shaped JSON -> MCP error results. */
public final class McpErrors {

  /** Same shape as mask-common ApiError (duplicated here: mask-common drags Spring in). */
  public record ApiError(String code, String message, List<String> details) {
    /** Contract: null details is serialized as []. */
    public ApiError {
      if (details == null) {
        details = List.of();
      }
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static ApiError of(Throwable t) {
    if (t instanceof SqlMaskException e) {
      return new ApiError(e.getCode().name(), e.getMessage(), List.of());
    }
    if (t instanceof IllegalArgumentException e) {
      return new ApiError("CONFIG_ERROR", e.getMessage(), List.of());
    }
    return new ApiError("INTERNAL", String.valueOf(t.getMessage()), List.of());
  }

  public static McpSchema.CallToolResult ok(String json) {
    // 校准：SDK 2.0.0 builder 不给 isError 缺省值（null Boolean 解箱即 NPE），显式置 false。
    return McpSchema.CallToolResult.builder()
        .isError(false)
        .content(List.of(McpSchema.TextContent.builder(json).build()))
        .build();
  }

  public static McpSchema.CallToolResult errorResult(ApiError e) {
    // 校准点 3：builder 有 structuredContent(Object)——结构化通道启用，text 保留（双通道）。
    return McpSchema.CallToolResult.builder()
        .isError(true)
        .structuredContent(MAPPER.convertValue(e, Map.class))
        .content(List.of(McpSchema.TextContent.builder(json(e)).build()))
        .build();
  }

  public static String json(ApiError e) {
    try {
      return MAPPER.writeValueAsString(e);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return "{\"code\":\"INTERNAL\",\"message\":\"unserializable error\",\"details\":[]}";
    }
  }

  private McpErrors() {
  }
}
