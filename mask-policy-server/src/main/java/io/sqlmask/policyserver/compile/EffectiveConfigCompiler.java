package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.model.Subject;
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
 * datamask selectors into column bindings, backfills row filters onto their
 * tables and summarizes enablement. Raw declaration text is preserved
 * verbatim; identifiers are only normalized for matching and deduplication.
 * {@code configVersion} is deliberately left at 0 — the caller fills it from
 * the store.
 */
public final class EffectiveConfigCompiler {

  private EffectiveConfigCompiler() {
  }

  public static EffectiveConfigResponse compile(EngineInstance instance,
      List<PolicyEntity> policies, Subject subject) {
    List<PolicyEntity> enabled = policies.stream()
        .filter(PolicyEntity::enabled)
        .filter(p -> p.subjects().matchLevel(subject) > 0)
        .sorted(Comparator.comparingInt(PolicyEntity::priority).reversed()
            .thenComparing(PolicyEntity::name))
        .toList();
    int disabled = policies.size() - enabled.size();

    List<EffectiveConfigResponse.ColumnBinding> bindings = new ArrayList<>();
    List<EffectiveConfigResponse.TablePayload> tables = new ArrayList<>();
    for (TableDef table : instance.tables()) {
      List<String> filterParts = new ArrayList<>();
      Set<String> emittedColumns = new HashSet<>();
      for (PolicyEntity policy : enabled) {
        if (!targets(policy.resource(), table)) {
          continue;
        }
        if (policy.policyType() == PolicyType.ROW_FILTER) {
          filterParts.add(policy.filterExpr());
        } else {
          for (String column : policy.resource().columns()) {
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

  private static boolean targets(ResourceSelector selector, TableDef table) {
    return ColumnKey.normalize(selector.catalog(), "catalog")
        .equals(ColumnKey.normalize(table.catalog(), "catalog"))
        && ColumnKey.normalize(selector.schema(), "schema")
            .equals(ColumnKey.normalize(table.schema(), "schema"))
        && ColumnKey.normalize(selector.table(), "table")
            .equals(ColumnKey.normalize(table.name(), "table"));
  }
}
