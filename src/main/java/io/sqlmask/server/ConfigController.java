package io.sqlmask.server;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Parses YAML metadata into the structured shape used by the web editor, so
 * the UI can round-trip configurations: YAML source → form, form → YAML
 * (generation happens client-side), YAML → rewrite.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

  private final YamlConfigLoader loader = new YamlConfigLoader();

  @PostMapping("/parse")
  public ConfigResponse parse(@RequestBody ConfigParseRequest request) {
    if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml is required");
    }
    LoadedConfig loaded = loader.loadContent(request.metadataYaml(), "metadata.yaml");
    return toResponse(loaded);
  }

  private ConfigResponse toResponse(LoadedConfig loaded) {
    List<TableDto> tables = loaded.config().tables().stream()
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(),
            t.rowFilter() == null ? "" : t.rowFilter(),
            t.columns().stream()
                .map(c -> new ColumnDto(c.name(), c.typeDeclaration()))
                .toList()))
        .toList();
    List<BindingDto> bindings = loaded.config().columnPolicies().stream()
        .map(b -> new BindingDto(b.key().catalog(), b.key().schema(), b.key().table(),
            b.key().column(), b.policyName()))
        .toList();
    List<PolicyDto> policies = loaded.config().policies().entrySet().stream()
        .map(e -> new PolicyDto(e.getKey(), e.getValue().udf(), e.getValue().arguments()))
        .toList();
    return new ConfigResponse(tables, bindings, policies);
  }

  public record ConfigParseRequest(String metadataYaml) {
  }

  public record ConfigResponse(List<TableDto> tables, List<BindingDto> columnPolicies,
      List<PolicyDto> policies) {
  }

  public record TableDto(String catalog, String schema, String name, String rowFilter,
      List<ColumnDto> columns) {
  }

  public record ColumnDto(String name, String type) {
  }

  public record BindingDto(String catalog, String schema, String table, String column,
      String policy) {
  }

  public record PolicyDto(String name, String udf, List<Object> arguments) {
  }
}
