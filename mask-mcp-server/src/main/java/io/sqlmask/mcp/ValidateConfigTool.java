package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.policy.store.PolicyYamlLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** validate_config: dry-parse metadata/policy YAML, report errors per source. */
public final class ValidateConfigTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "validate_config";
  }

  @Override
  public String description() {
    return "Validate metadata YAML and/or Ranger-style policy YAML without touching SQL. "
        + "Returns per-source errors; a false-valid result is a normal response, not a failure.";
  }

  @Override
  public String schemaJson() {
    return """
        {"type":"object","properties":{
          "metadataYaml":{"type":"string","description":"metadata YAML to validate (needs dialect)"},
          "policyYaml":{"type":"string","description":"Ranger-style policy YAML to validate"},
          "dialect":{"type":"string","enum":["postgresql","trino","mysql","hive","sparksql"],"description":"required when metadataYaml is present"}
        },"required":["dialect"]}""";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      String dialect = requireString(arguments, "dialect");
      String metadataYaml = optionalString(arguments, "metadataYaml");
      String policyYaml = optionalString(arguments, "policyYaml");
      if (metadataYaml == null && policyYaml == null) {
        throw new IllegalArgumentException("metadataYaml or policyYaml is required");
      }
      List<Map<String, Object>> errors = new ArrayList<>();
      if (metadataYaml != null) {
        try {
          new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialect);
        } catch (Exception e) {
          errors.add(entry("metadata", e));
        }
      }
      if (policyYaml != null) {
        try {
          new PolicyYamlLoader().parse(policyYaml, "policies.yaml");
        } catch (Exception e) {
          errors.add(entry("policy", e));
        }
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("valid", errors.isEmpty());
      out.put("errors", errors);
      return McpErrors.ok(MAPPER.writeValueAsString(out));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }

  private static Map<String, Object> entry(String source, Exception e) {
    String code = e instanceof io.sqlmask.error.SqlMaskException s
        ? s.getCode().name() : "CONFIG_ERROR";
    return Map.of("source", source, "code", code, "message", String.valueOf(e.getMessage()));
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
