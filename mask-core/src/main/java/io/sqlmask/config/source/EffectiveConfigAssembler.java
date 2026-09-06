package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.dialect.TypeResolver;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.MaskingPolicy;
import io.sqlmask.policy.PolicyRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the compiled effective-config payload into the validated in-memory
 * model, reusing the dialect type resolver and the same duplicate/unknown
 * checks as the YAML loader. Errors carry paths prefixed with the instance name.
 *
 * <p>Null tolerance mirrors the wire contract: a missing optional
 * {@code arguments} list is treated as empty (equivalent to YAML absence);
 * every missing required structure, blank identifier and zero-column table is
 * rejected with {@link SqlMaskException.Code#CONFIG_ERROR} at the offending
 * field path, exactly as the YAML loader rejects the equivalent YAML.
 */
public final class EffectiveConfigAssembler {

  public LoadedConfig assemble(EffectiveConfigResponse response) {
    String source = "policy-instance '" + response.instance() + "'";
    TypeResolver typeResolver = DialectProfiles.byName(response.dialect()).typeResolver();

    EffectiveConfigResponse.ConfigPayload payload = required(response.config(),
        source + ": config");
    EffectiveConfigResponse.MetadataPayload metadata = required(payload.metadata(),
        source + ": config.metadata");
    List<EffectiveConfigResponse.TablePayload> tablePayloads = required(metadata.tables(),
        source + ": config.metadata.tables");
    List<EffectiveConfigResponse.ColumnBinding> bindingPayloads = required(payload.columns(),
        source + ": config.columns");
    Map<String, EffectiveConfigResponse.UdfDefinition> policyPayloads = required(
        payload.policies(), source + ": config.policies");

    List<TableMetadata> tables = new ArrayList<>();
    Set<String> seenTables = new LinkedHashSet<>();
    for (int i = 0; i < tablePayloads.size(); i++) {
      EffectiveConfigResponse.TablePayload tp = tablePayloads.get(i);
      String tablePath = source + ": config.metadata.tables[" + i + "]";
      String catalog = requiredString(tp.catalog(), tablePath + ".catalog");
      String schema = requiredString(tp.schema(), tablePath + ".schema");
      String name = requiredString(tp.name(), tablePath + ".name");
      String tableKey = key(catalog, schema, name);
      if (!seenTables.add(tableKey)) {
        throw error(tablePath + ": duplicate table '" + tableKey + "'");
      }
      List<EffectiveConfigResponse.ColumnPayload> columnPayloads = required(tp.columns(),
          tablePath + ".columns");
      if (columnPayloads.isEmpty()) {
        throw error(tablePath + ".columns must declare at least one column");
      }
      List<TableMetadata.Column> columns = new ArrayList<>();
      Set<String> seenColumns = new LinkedHashSet<>();
      for (int j = 0; j < columnPayloads.size(); j++) {
        EffectiveConfigResponse.ColumnPayload cp = columnPayloads.get(j);
        String columnPath = tablePath + ".columns[" + j + "]";
        String columnName = requiredString(cp.name(), columnPath + ".name");
        String columnType = requiredString(cp.type(), columnPath + ".type");
        if (!seenColumns.add(ColumnKey.normalize(columnName, "column"))) {
          throw error(columnPath + ": duplicate column name '" + columnName + "'");
        }
        try {
          columns.add(typeResolver.parseColumn(columnName, columnType));
        } catch (SqlMaskException | IllegalArgumentException e) {
          throw error(columnPath + ": " + e.getMessage());
        }
      }
      tables.add(new TableMetadata(catalog, schema, name, columns, tp.rowFilter()));
    }

    Map<String, MaskingPolicy> policies = new LinkedHashMap<>();
    for (Map.Entry<String, EffectiveConfigResponse.UdfDefinition> e
        : policyPayloads.entrySet()) {
      String policyPath = source + ": config.policies." + e.getKey();
      EffectiveConfigResponse.UdfDefinition definition = required(e.getValue(), policyPath);
      String udf = requiredString(definition.udf(), policyPath + ".udf");
      List<Object> arguments =
          definition.arguments() == null ? List.of() : definition.arguments();
      MaskingPolicy.validateArguments(arguments, e.getKey(), policyPath);
      policies.put(e.getKey(), new MaskingPolicy(e.getKey(), udf, arguments));
    }

    List<MaskingConfig.ColumnPolicyBinding> bindings = new ArrayList<>();
    Set<String> seenBindings = new LinkedHashSet<>();
    for (int i = 0; i < bindingPayloads.size(); i++) {
      EffectiveConfigResponse.ColumnBinding b = bindingPayloads.get(i);
      String bindingPath = source + ": config.columns[" + i + "]";
      String catalog = requiredString(b.catalog(), bindingPath + ".catalog");
      String schema = requiredString(b.schema(), bindingPath + ".schema");
      String table = requiredString(b.table(), bindingPath + ".table");
      String column = requiredString(b.column(), bindingPath + ".column");
      String policyName = requiredString(b.policy(), bindingPath + ".policy");
      if (!policies.containsKey(policyName)) {
        throw error(bindingPath + ".policy: unknown policy '" + policyName + "' "
            + "(declared policies: " + policies.keySet() + ")");
      }
      ColumnKey key = ColumnKey.of(catalog, schema, table, column);
      if (!seenBindings.add(key.toString())) {
        throw error(bindingPath + ": duplicate policy binding for column '" + key + "'");
      }
      bindings.add(new MaskingConfig.ColumnPolicyBinding(key, policyName));
    }

    MaskingConfig config = new MaskingConfig(tables, bindings, policies);
    return new LoadedConfig(config, PolicyRegistry.of(config));
  }

  private static String key(String catalog, String schema, String table) {
    return ColumnKey.normalize(catalog, "catalog") + "."
        + ColumnKey.normalize(schema, "schema") + "."
        + ColumnKey.normalize(table, "table");
  }

  /** Rejects a missing required structure at {@code path}. */
  private static <T> T required(T value, String path) {
    if (value == null) {
      throw error(path + ": required structure is missing");
    }
    return value;
  }

  /** Mirrors the YAML loader's requiredString check for identifiers and scalars. */
  private static String requiredString(String value, String path) {
    if (value == null || value.isBlank()) {
      throw error(path + ": required non-blank string is missing");
    }
    return value;
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
