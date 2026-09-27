package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;

import java.util.List;
import java.util.Map;

/** rewrite_sql: inline-YAML masking rewrite, same kernel call as /api/rewrite. */
public final class RewriteSqlTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "rewrite_sql";
  }

  @Override
  public String description() {
    return "Rewrite SQL statements so result columns are wrapped in masking UDFs "
        + "(and row filters injected), per the supplied metadata/policy YAML. "
        + "Input and output are the same dialect; nothing is executed. "
        + "Error codes come back verbatim from the kernel (e.g. CONFIG_ERROR, "
        + "PARSE_ERROR, REWRITE_ERROR, UNSUPPORTED_STATEMENT).";
  }

  @Override
  public String schemaJson() {
    return """
        {"type":"object","properties":{
          "metadataYaml":{"type":"string","description":"metadata YAML: tables, columns, legacy policies/rowFilter"},
          "policyYaml":{"type":"string","description":"Ranger-style policy YAML; when non-blank the metadata must not embed policies"},
          "sql":{"type":"string","description":"one or more semicolon-separated SQL statements"},
          "dialect":{"type":"string","enum":["postgresql","trino","mysql","hive","sparksql"],"description":"target dialect (see list_dialects)"},
          "user":{"type":"string","description":"query subject for policy matching; default anonymous"},
          "groups":{"type":"array","items":{"type":"string"},"description":"subject groups"}
        },"required":["metadataYaml","sql","dialect"]}""";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      String metadataYaml = requireString(arguments, "metadataYaml");
      String sql = requireString(arguments, "sql");
      String dialect = requireString(arguments, "dialect");
      String policyYaml = optionalString(arguments, "policyYaml");
      String user = optionalString(arguments, "user");
      @SuppressWarnings("unchecked")
      List<String> groups = (List<String>) arguments.get("groups");

      Subject subject = user == null && groups == null
          ? Subject.anonymous()
          : Subject.of(user == null ? "anonymous" : user, groups == null ? List.of() : groups);

      List<RewriteEngine.StatementRewrite> statements = new RewriteEngine()
          .rewrite(metadataYaml, policyYaml == null ? "" : policyYaml, sql, dialect, subject);
      return McpErrors.ok(MAPPER.writeValueAsString(Map.of(
          "statements", statements,
          "rewrittenSql", RewriteEngine.join(statements))));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }

  private static String requireString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    if (!(v instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return s;
  }

  private static String optionalString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    return v instanceof String s && !s.isBlank() ? s : null;
  }
}
