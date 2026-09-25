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
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.rowfilter.RowFilterRegistry;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

  public void validatePolicy(EngineInstance instance, List<UdfDefinition> udfs,
      PolicyEntity policy, List<PolicyEntity> otherEnabledPolicies) {
    requireName(instance.name(), "instance name");
    requireName(policy.name(), "policy name");
    requireGlobFree(policy);
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
        // inheritColumns ⊆ columns:继承列必须同时是被脱敏的列(否则 CTAS 继承无从生效)
        for (String inheritColumn : policy.resource().inheritColumns()) {
          if (!policy.resource().columns().contains(inheritColumn)) {
            throw error("policy '" + policy.name() + "': inheritColumn '" + inheritColumn
                + "' is not among resource columns");
          }
        }
        String udfError = udfResolutionError(instance, udfs, policy);
        if (udfError != null) {
          throw error(udfError);
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
      // Overlap rejection is datamask-only now: row filters compose with AND at
      // compile time, and different-priority datamask overlap resolves by
      // priority (spec 2026-09-17-policy-priority-design §4).
      boolean overlap = policy.policyType() == PolicyType.DATAMASK
          && other.priority().intValue() == policy.priority().intValue()
          && intersects(other.resource().columns(), policy.resource().columns())
          && subjectsMayOverlap(other.subjects(), policy.subjects());
      if (overlap) {
        throw error("policy '" + policy.name() + "' (priority " + policy.priority()
            + ") overlaps enabled policy '" + other.name() + "' (priority "
            + other.priority() + ") on table '" + tableKey(policy.resource())
            + "'; use a different priority or disable one of them first");
      }
    }
  }

  /**
   * The management plane stores exact resources only: overlap detection relies
   * on name equality, so a glob pattern here would silently evade it. Glob
   * matching belongs to the policies.yaml policy subsystem.
   */
  private static void requireGlobFree(PolicyEntity policy) {
    ResourceSelector resource = policy.resource();
    List<String> levels = new ArrayList<>(
        List.of(resource.catalog(), resource.schema(), resource.table()));
    levels.addAll(resource.columns());
    for (String level : levels) {
      if (level.contains("*")) {
        throw error("policy '" + policy.name() + "': glob '*' in resource is not supported;"
            + " declare exact catalog/schema/table/column names");
      }
    }
  }

  /** Config-time gate for udf writes: names, non-empty signatures, dialect-valid
   * type declarations and signature deduplication. */
  public void validateUdf(EngineInstance instance, UdfDefinition udf) {
    requireName(udf.name(), "udf name");
    if (udf.signatures().isEmpty()) {
      throw error("udf '" + udf.name() + "' requires at least one signature");
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    Set<List<String>> seenParams = new LinkedHashSet<>();
    for (int i = 0; i < udf.signatures().size(); i++) {
      UdfDefinition.UdfSignature signature = udf.signatures().get(i);
      if (signature.params().isEmpty()) {
        throw error("udf '" + udf.name() + "' signature #" + i
            + " requires at least the column-value parameter");
      }
      for (String declaration : signature.params()) {
        requireParsableType(typeResolver, instance, udf.name(), declaration);
      }
      requireParsableType(typeResolver, instance, udf.name(), signature.returns());
      if (!seenParams.add(signature.params())) {
        throw error("udf '" + udf.name() + "': duplicate signature " + signature.params());
      }
    }
  }

  private static void requireParsableType(TypeResolver typeResolver, EngineInstance instance,
      String udfName, String declaration) {
    try {
      typeResolver.parseColumn(declaration, declaration);
    } catch (SqlMaskException | IllegalArgumentException e) {
      throw error("udf '" + udfName + "' in instance '" + instance.name()
          + "': invalid type declaration '" + declaration + "' (" + e.getMessage() + ")");
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

  /**
   * Udf-removal guard input: names of enabled DATAMASK policies whose udf
   * reference no longer resolves against the given registry.
   */
  public List<String> policiesFailingUdfResolution(EngineInstance instance,
      List<UdfDefinition> udfs, List<PolicyEntity> policies) {
    List<String> failing = new ArrayList<>();
    for (PolicyEntity policy : policies) {
      if (!policy.enabled() || policy.policyType() != PolicyType.DATAMASK) {
        continue;
      }
      if (udfResolutionError(instance, udfs, policy) != null) {
        failing.add(policy.name());
      }
    }
    return failing;
  }

  /** Null when the policy's udf reference resolves against the registry. */
  private String udfResolutionError(EngineInstance instance, List<UdfDefinition> udfs,
      PolicyEntity policy) {
    UdfDefinition udf = udfs.stream()
        .filter(u -> u.name().equals(policy.udf())).findFirst().orElse(null);
    if (udf == null) {
      return "policy '" + policy.name() + "': unknown udf '" + policy.udf()
          + "' in instance '" + instance.name() + "'";
    }
    int expectedParams = policy.arguments().size() + 1;
    List<UdfDefinition.UdfSignature> byArity = udf.signatures().stream()
        .filter(s -> s.params().size() == expectedParams).toList();
    if (byArity.isEmpty()) {
      return "policy '" + policy.name() + "': udf '" + udf.name() + "' declares signatures "
          + signatureShapes(udf) + " but the policy binds 1 column value + "
          + policy.arguments().size() + " argument(s)";
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    List<UdfDefinition.UdfSignature> byScalarTypes = byArity.stream()
        .filter(s -> argumentsMatch(typeResolver, s, policy.arguments())).toList();
    if (byScalarTypes.isEmpty()) {
      return "policy '" + policy.name() + "': argument types " + describeScalars(policy.arguments())
          + " match no arity-" + expectedParams + " " + udf.name() + " signature";
    }
    TableMetadata target = findTable(instance, policy);
    for (String columnName : policy.resource().columns()) {
      TableMetadata.Column column = target.columns().stream()
          .filter(c -> ColumnKey.normalize(c.name(), "column")
              .equals(ColumnKey.normalize(columnName, "column")))
          .findFirst().orElse(null);
      if (column == null) {
        return "policy '" + policy.name() + "': unknown column '" + columnName + "'";
      }
      boolean overloadExists = byScalarTypes.stream()
          .anyMatch(s -> sameType(typeResolver, s.params().get(0), column));
      if (!overloadExists) {
        return "policy '" + policy.name() + "': column '" + columnName + "' ("
            + column.typeDeclaration() + ") has no matching " + udf.name()
            + " overload; declare a signature whose first parameter is "
            + column.typeDeclaration();
      }
    }
    return null;
  }

  private static boolean argumentsMatch(TypeResolver typeResolver,
      UdfDefinition.UdfSignature signature, List<Object> arguments) {
    for (int i = 0; i < arguments.size(); i++) {
      TableMetadata.Column param = typeResolver.parseColumn("p", signature.params().get(i + 1));
      if (!scalarFits(arguments.get(i), param)) {
        return false;
      }
    }
    return true;
  }

  /** Strict scalar matrix — no cross-family coercion (spec §4.2). */
  private static boolean scalarFits(Object scalar, TableMetadata.Column param) {
    if (scalar instanceof Number) {
      return param.sqlTypeName() == SqlTypeName.SMALLINT
          || param.sqlTypeName() == SqlTypeName.INTEGER
          || param.sqlTypeName() == SqlTypeName.BIGINT
          || param.sqlTypeName() == SqlTypeName.REAL
          || param.sqlTypeName() == SqlTypeName.DOUBLE
          || param.sqlTypeName() == SqlTypeName.DECIMAL;
    }
    if (scalar instanceof Boolean) {
      return param.sqlTypeName() == SqlTypeName.BOOLEAN;
    }
    if (scalar instanceof String) {
      return param.sqlTypeName() == SqlTypeName.VARCHAR || param.sqlTypeName() == SqlTypeName.CHAR;
    }
    return false;
  }

  /** Exact match on the parsed triple — declarations only normalize spellings. */
  private static boolean sameType(TypeResolver typeResolver, String declaration,
      TableMetadata.Column column) {
    TableMetadata.Column parsed = typeResolver.parseColumn("x", declaration);
    return parsed.sqlTypeName() == column.sqlTypeName()
        && Objects.equals(parsed.precision(), column.precision())
        && Objects.equals(parsed.scale(), column.scale());
  }

  private static String signatureShapes(UdfDefinition udf) {
    return udf.signatures().stream()
        .map(s -> "(" + String.join(", ", s.params()) + ")")
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String describeScalars(List<Object> arguments) {
    return arguments.stream().map(scalar -> scalar instanceof Number ? "number"
        : scalar instanceof Boolean ? "boolean"
        : scalar instanceof String ? "string" : String.valueOf(scalar))
        .collect(Collectors.joining(", ", "[", "]"));
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

  /**
   * Two selectors may hit the same subject: any wildcard, a users or groups
   * intersection, or a cross-set pair — a composite subject (alice, [analysts])
   * matches users and groups independently via
   * {@link io.sqlmask.policy.model.SubjectSelector#matchLevel}, so A.users with
   * B.groups (or A.groups with B.users) both non-empty can select the same
   * subject through both policies.
   */
  private static boolean subjectsMayOverlap(
      io.sqlmask.policy.model.SubjectSelector a, io.sqlmask.policy.model.SubjectSelector b) {
    if (a.users().contains("*") || a.groups().contains("*")
        || b.users().contains("*") || b.groups().contains("*")) {
      return true;
    }
    return !a.users().isEmpty() && !b.groups().isEmpty()
        || !a.groups().isEmpty() && !b.users().isEmpty()
        || !java.util.Collections.disjoint(a.users(), b.users())
        || !java.util.Collections.disjoint(a.groups(), b.groups());
  }
}
