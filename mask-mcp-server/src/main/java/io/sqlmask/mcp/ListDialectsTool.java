package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.sqlmask.dialect.DialectProfiles;

import java.util.Comparator;
import java.util.Map;

/** list_dialects: registry-driven enumeration of supported dialects. */
public final class ListDialectsTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Map<String, String> DESCRIPTIONS = Map.of(
      "postgresql", "PostgreSQL（pg_catalog 采集，行过滤与脱敏 UDF 包装）",
      "trino", "Trino（catalog/schema 三段名，information_schema 采集）",
      "mysql", "MySQL（反引号标识符，unsigned 剥离告警）",
      "hive", "Hive（大小写不敏感标识符折叠）",
      "sparksql", "Spark SQL（Hive 兼容语法面）");

  @Override
  public String name() {
    return "list_dialects";
  }

  @Override
  public String description() {
    return "List the SQL dialects accepted by rewrite_sql/validate_config "
        + "(input and output are always the same dialect).";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      var dialects = DialectProfiles.names().stream()
          .sorted(Comparator.naturalOrder())
          .map(n -> Map.of("name", n, "description", DESCRIPTIONS.getOrDefault(n, "")))
          .toList();
      return McpErrors.ok(MAPPER.writeValueAsString(Map.of("dialects", dialects)));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }
}
