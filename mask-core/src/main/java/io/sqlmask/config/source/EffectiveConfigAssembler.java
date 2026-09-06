package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.config.MaskingPolicy;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.dialect.TypeResolver;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;

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
 */
public final class EffectiveConfigAssembler {

  public LoadedConfig assemble(EffectiveConfigResponse response) {
    String source = "policy-instance '" + response.instance() + "'";
    TypeResolver typeResolver = DialectProfiles.byName(response.dialect()).typeResolver();

    List<TableMetadata> tables = new ArrayList<>();
    Set<String> seenTables = new LinkedHashSet<>();
    for (EffectiveConfigResponse.TablePayload tp : response.config().metadata().tables()) {
      String tableKey = key(tp.catalog(), tp.schema(), tp.name());
      if (!seenTables.add(tableKey)) {
        throw error(source + ": duplicate table '" + tableKey + "'");
      }
      List<TableMetadata.Column> columns = new ArrayList<>();
      Set<String> seenColumns = new LinkedHashSet<>();
      for (EffectiveConfigResponse.ColumnPayload cp : tp.columns()) {
        String normalizedName = ColumnKey.normalize(cp.name(), "column");
        if (!seenColumns.add(normalizedName)) {
          throw error(source + ": table '" + tableKey + "': duplicate column '" + cp.name() + "'");
        }
        try {
          columns.add(typeResolver.parseColumn(cp.name(), cp.type()));
        } catch (SqlMaskException | IllegalArgumentException e) {
          throw error(source + ": table '" + tableKey + "' column '" + cp.name()
              + "': " + e.getMessage());
        }
      }
      tables.add(new TableMetadata(tp.catalog(), tp.schema(), tp.name(), columns, tp.rowFilter()));
    }

    Map<String, MaskingPolicy> policies = new LinkedHashMap<>();
    for (Map.Entry<String, EffectiveConfigResponse.UdfDefinition> e
        : response.config().policies().entrySet()) {
      MaskingPolicy.validateArguments(e.getValue().arguments(), e.getKey(), source);
      policies.put(e.getKey(),
          new MaskingPolicy(e.getKey(), e.getValue().udf(), e.getValue().arguments()));
    }

    List<MaskingConfig.ColumnPolicyBinding> bindings = new ArrayList<>();
    Set<String> seenBindings = new LinkedHashSet<>();
    for (EffectiveConfigResponse.ColumnBinding b : response.config().columns()) {
      String policyName = b.policy();
      if (!policies.containsKey(policyName)) {
        throw error(source + ": column binding for '" + b.column() + "' references unknown policy '"
            + policyName + "' (declared policies: " + policies.keySet() + ")");
      }
      ColumnKey key = ColumnKey.of(b.catalog(), b.schema(), b.table(), b.column());
      if (!seenBindings.add(key.toString())) {
        throw error(source + ": duplicate policy binding for column '" + key + "'");
      }
      bindings.add(new MaskingConfig.ColumnPolicyBinding(key, policyName));
    }

    MaskingConfig config = new MaskingConfig(tables, bindings, policies);
    return new LoadedConfig(config);
  }

  private static String key(String catalog, String schema, String table) {
    return ColumnKey.normalize(catalog, "catalog") + "."
        + ColumnKey.normalize(schema, "schema") + "."
        + ColumnKey.normalize(table, "table");
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
