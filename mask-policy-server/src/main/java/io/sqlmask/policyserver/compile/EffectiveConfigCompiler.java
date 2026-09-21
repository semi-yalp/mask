package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.match.GlobMatcher;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure function from stored state to the wire contract: expands enabled
 * datamask selectors (exact and glob) into concrete column bindings, backfills
 * row filters onto their tables and summarizes enablement. Glob table/column
 * patterns are expanded against the instance snapshot so the emitted
 * {@code ColumnBinding}/{@code TablePayload} are always <em>concrete</em>
 * identifiers — never a literal {@code *}/{@code ?}. Patterns that match no
 * snapshot table/column contribute nothing and are reported as warnings.
 * Raw declaration text is preserved verbatim; identifiers are only normalized
 * for matching. {@code configVersion} is left at 0 — the caller fills it.
 */
public final class EffectiveConfigCompiler {

  private EffectiveConfigCompiler() {
  }

  public static EffectiveConfigResponse compile(EngineInstance instance,
      List<PolicyEntity> policies, Subject subject, List<String> warnings) {
    List<PolicyEntity> enabled = policies.stream()
        .filter(PolicyEntity::enabled)
        .filter(p -> p.accessType() == AccessType.SELECT)
        .filter(p -> p.subjects().matchLevel(subject) > 0)
        .sorted(Comparator.comparingInt(PolicyEntity::priority).reversed()
            .thenComparing(PolicyEntity::name))
        .toList();
    int disabled = policies.size() - enabled.size();

    List<EffectiveConfigResponse.ColumnBinding> bindings = new ArrayList<>();
    Map<String, List<PolicyEntity>> policiesByTable = indexPoliciesByTable(instance, enabled,
        warnings);
    List<EffectiveConfigResponse.TablePayload> tables = new ArrayList<>();
    for (TableDef table : instance.tables()) {
      List<String> filterParts = new ArrayList<>();
      Set<String> emittedColumns = new HashSet<>();
      for (PolicyEntity policy : policiesByTable.getOrDefault(tableKey(table), List.of())) {
        if (policy.policyType() == PolicyType.ROW_FILTER) {
          filterParts.add(policy.filterExpr());
        } else if (policy.policyType() == PolicyType.DATAMASK) {
          for (String column : expandColumns(policy.resource().columns(), table, policy, warnings)) {
            if (emittedColumns.add(ColumnKey.normalize(column, "column"))) {
              bindings.add(new EffectiveConfigResponse.ColumnBinding(
                  table.catalog(), table.schema(), table.name(), column, policy.name()));
            }
          }
        }
      }
      String rowFilter = null;
      if (filterParts.size() == 1) {
        rowFilter = filterParts.get(0);
      } else if (filterParts.size() > 1) {
        rowFilter = filterParts.stream().map(f -> "(" + f + ")")
            .reduce((a, b) -> a + " AND " + b).orElseThrow();
      }
      List<EffectiveConfigResponse.ColumnPayload> columns = new ArrayList<>();
      for (ColumnDef column : table.columns()) {
        columns.add(new EffectiveConfigResponse.ColumnPayload(
            column.name(), column.typeDeclaration()));
      }
      tables.add(new EffectiveConfigResponse.TablePayload(
          table.catalog(), table.schema(), table.name(), rowFilter, columns));
    }

    Map<String, EffectiveConfigResponse.UdfDefinition> udfs = new LinkedHashMap<>();
    for (PolicyEntity policy : enabled) {
      if (policy.policyType() == PolicyType.DATAMASK) {
        udfs.put(policy.name(),
            new EffectiveConfigResponse.UdfDefinition(policy.udf(), policy.arguments()));
      }
    }

    EffectiveConfigResponse.ConfigPayload config = new EffectiveConfigResponse.ConfigPayload(
        new EffectiveConfigResponse.MetadataPayload(tables), bindings, udfs);
    EffectiveConfigResponse.PolicySummary summary =
        new EffectiveConfigResponse.PolicySummary(enabled.size(), disabled);
    return new EffectiveConfigResponse(instance.name(), instance.dialect(), 0L, summary, config);
  }

  /** Groups enabled policies by the concrete snapshot tables they target. */
  private static Map<String, List<PolicyEntity>> indexPoliciesByTable(EngineInstance instance,
      List<PolicyEntity> enabled, List<String> warnings) {
    Map<String, List<PolicyEntity>> byTable = new LinkedHashMap<>();
    for (PolicyEntity policy : enabled) {
      List<TableDef> targets = policyTableTargets(instance, policy);
      if (targets.isEmpty()) {
        warnings.add("policy '" + policy.name() + "': resource '"
            + tableKey(policy.resource()) + "' matches no snapshot table; nothing compiled");
        continue;
      }
      for (TableDef target : targets) {
        byTable.computeIfAbsent(tableKey(target), k -> new ArrayList<>()).add(policy);
      }
    }
    return byTable;
  }

  /** Exact catalog/schema/table or glob-expanded concrete tables. */
  private static List<TableDef> policyTableTargets(EngineInstance instance, PolicyEntity policy) {
    List<TableDef> targets = new ArrayList<>();
    for (TableDef table : instance.tables()) {
      if (tableMatches(policy.resource(), table)) {
        targets.add(table);
      }
    }
    return targets;
  }

  private static List<String> expandColumns(List<String> columns, TableDef table,
      PolicyEntity policy, List<String> warnings) {
    List<String> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String column : columns) {
      if (containsGlob(column)) {
        boolean any = false;
        for (ColumnDef candidate : table.columns()) {
          if (GlobMatcher.matches(normalize(column), normalize(candidate.name()))
              && seen.add(normalize(candidate.name()))) {
            result.add(candidate.name());
            any = true;
          }
        }
        if (!any) {
          warnings.add("policy '" + policy.name() + "': column pattern '" + column
              + "' matches no column of snapshot table '" + tableKey(table) + "'");
        }
      } else if (seen.add(normalize(column))) {
        result.add(column);
      }
    }
    return result;
  }

  private static boolean tableMatches(ResourceSelector selector, TableDef table) {
    return levelMatches(selector.catalog(), table.catalog())
        && levelMatches(selector.schema(), table.schema())
        && levelMatches(selector.table(), table.name());
  }

  private static boolean levelMatches(String pattern, String value) {
    return GlobMatcher.matches(normalize(pattern), normalize(value));
  }

  private static boolean containsGlob(String level) {
    return level != null && (level.indexOf('*') >= 0 || level.indexOf('?') >= 0);
  }

  private static String normalize(String identifier) {
    return identifier == null ? "" : identifier.toLowerCase(java.util.Locale.ROOT);
  }

  private static String tableKey(ResourceSelector r) {
    return normalize(r.catalog()) + "." + normalize(r.schema()) + "." + normalize(r.table());
  }

  private static String tableKey(TableDef t) {
    return normalize(t.catalog()) + "." + normalize(t.schema()) + "." + normalize(t.name());
  }
}