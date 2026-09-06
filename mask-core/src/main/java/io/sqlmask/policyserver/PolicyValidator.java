package io.sqlmask.policyserver;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.dialect.DialectRegistry;
import io.sqlmask.dialect.TypeResolver;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.config.MaskingPolicy;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.rowfilter.RowFilterRegistry;
import org.apache.calcite.schema.SchemaPlus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Config-time gatekeeper: every write path validates here so the compiled
 * effective config is always self-consistent (dangling references and
 * selector overlaps are impossible by construction).
 */
public final class PolicyValidator {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

  public void validateInstance(EngineInstance instance) {
    requireName(instance.name(), "instance name");
    if (!EngineDefs.names().contains(instance.dialect())) {
      throw error("instance '" + instance.name() + "': unknown dialect '" + instance.dialect()
          + "' (supported: " + EngineDefs.names() + ")");
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    Set<String> seenTables = new LinkedHashSet<>();
    for (TableDef table : instance.tables()) {
      String tableKey = tableKey(table);
      if (!seenTables.add(tableKey)) {
        throw error("instance '" + instance.name() + "': duplicate table '" + tableKey + "'");
      }
      if (table.columns().isEmpty()) {
        throw error("instance '" + instance.name() + "': table '" + tableKey
            + "' columns must declare at least one column");
      }
      Set<String> seenColumns = new LinkedHashSet<>();
      for (ColumnDef column : table.columns()) {
        String normalized = ColumnKey.normalize(column.name(), "column");
        if (!seenColumns.add(normalized)) {
          throw error("instance '" + instance.name() + "': table '" + tableKey
              + "': duplicate column '" + column.name() + "'");
        }
        try {
          typeResolver.parseColumn(column.name(), column.typeDeclaration());
        } catch (SqlMaskException | IllegalArgumentException e) {
          throw error("instance '" + instance.name() + "': table '" + tableKey + "' column '"
              + column.name() + "': " + e.getMessage()
              + " (dialect " + instance.dialect() + ")");
        }
      }
    }
  }

  public void validatePolicy(EngineInstance instance, PolicyEntity policy,
      List<PolicyEntity> otherEnabledPolicies) {
    requireName(instance.name(), "instance name");
    requireName(policy.name(), "policy name");
    TableMetadata target = findTable(instance, policy);
    switch (policy.policyType()) {
      case DATAMASK -> {
        if (policy.udf() == null || policy.udf().isBlank()) {
          throw error("policy '" + policy.name() + "': datamask requires a udf");
        }
        MaskingPolicy.validateArguments(policy.arguments(), policy.name(),
            "instance '" + instance.name() + "'");
        if (policy.resource().columns().isEmpty()) {
          throw error("policy '" + policy.name() + "': datamask requires at least one column");
        }
        Set<String> tableColumns = new LinkedHashSet<>();
        target.columns().forEach(c -> tableColumns.add(ColumnKey.normalize(c.name(), "column")));
        for (String column : policy.resource().columns()) {
          if (!tableColumns.contains(ColumnKey.normalize(column, "column"))) {
            throw error("policy '" + policy.name() + "': unknown column '" + column
                + "' in table '" + tableKey(policy.resource()) + "'");
          }
        }
      }
      case ROW_FILTER -> {
        if (policy.filterExpr() == null || policy.filterExpr().isBlank()) {
          throw error("policy '" + policy.name() + "': row_filter requires filterExpr");
        }
        validateFilterExpression(instance, policy);
      }
    }
    for (PolicyEntity other : otherEnabledPolicies) {
      if (!other.enabled() || other.name().equals(policy.name())
          || other.policyType() != policy.policyType()) {
        continue;
      }
      if (!ColumnKey.normalize(other.resource().table(), "table")
          .equals(ColumnKey.normalize(policy.resource().table(), "table"))
          || !ColumnKey.normalize(other.resource().schema(), "schema")
          .equals(ColumnKey.normalize(policy.resource().schema(), "schema"))
          || !ColumnKey.normalize(other.resource().catalog(), "catalog")
          .equals(ColumnKey.normalize(policy.resource().catalog(), "catalog"))) {
        continue;
      }
      boolean overlap = policy.policyType() == PolicyType.ROW_FILTER
          || intersects(other.resource().columns(), policy.resource().columns());
      if (overlap) {
        throw error("policy '" + policy.name() + "' overlaps enabled policy '" + other.name()
            + "' on table '" + tableKey(policy.resource()) + "'; disable one of them first");
      }
    }
  }

  /**
   * Reuses the rewrite engine's row-filter whitelist as the config-time gate:
   * the policy's condition is attached to its declared table so
   * {@link RowFilterRegistry#build} parses it, enforces the construct
   * whitelist and validates it against the instance's schema.
   */
  private void validateFilterExpression(EngineInstance instance, PolicyEntity policy) {
    List<TableMetadata> tables = new ArrayList<>();
    String targetKey = tableKey(policy.resource());
    instance.tables().forEach(t -> {
      List<TableMetadata.Column> columns = new ArrayList<>();
      t.columns().forEach(c -> columns.add(
          DialectProfiles.byName(instance.dialect()).typeResolver()
              .parseColumn(c.name(), c.typeDeclaration())));
      String rowFilter = tableKey(t).equals(targetKey) ? policy.filterExpr() : null;
      tables.add(new TableMetadata(t.catalog(), t.schema(), t.name(), columns, rowFilter));
    });
    MaskingConfig config = new MaskingConfig(tables, List.of(), Map.of());
    LoadedConfig loaded = new LoadedConfig(config);
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    try {
      RowFilterRegistry.build(loaded, DialectRegistry.create(instance.dialect()), schema);
    } catch (SqlMaskException e) {
      throw error("policy '" + policy.name() + "': invalid filterExpr: " + e.getMessage());
    }
  }

  private TableMetadata findTable(EngineInstance instance, PolicyEntity policy) {
    String wanted = tableKey(policy.resource());
    TableDef def = instance.tables().stream()
        .filter(t -> tableKey(t).equals(wanted))
        .findFirst()
        .orElseThrow(() -> error("policy '" + policy.name()
            + "': unknown table '" + wanted + "'"));
    List<TableMetadata.Column> columns = new ArrayList<>();
    def.columns().forEach(c -> columns.add(
        DialectProfiles.byName(instance.dialect()).typeResolver()
            .parseColumn(c.name(), c.typeDeclaration())));
    return new TableMetadata(def.catalog(), def.schema(), def.name(), columns, null);
  }

  private static boolean intersects(List<String> a, List<String> b) {
    Set<String> left = new LinkedHashSet<>();
    a.forEach(c -> left.add(ColumnKey.normalize(c, "column")));
    return b.stream().anyMatch(c -> left.contains(ColumnKey.normalize(c, "column")));
  }

  private static String tableKey(ResourceSelector r) {
    return ColumnKey.normalize(r.catalog(), "catalog") + "."
        + ColumnKey.normalize(r.schema(), "schema") + "."
        + ColumnKey.normalize(r.table(), "table");
  }

  private static String tableKey(TableDef t) {
    return ColumnKey.normalize(t.catalog(), "catalog") + "."
        + ColumnKey.normalize(t.schema(), "schema") + "."
        + ColumnKey.normalize(t.name(), "table");
  }

  private static void requireName(String name, String what) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw error(what + " '" + name + "' must match " + NAME.pattern());
    }
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
