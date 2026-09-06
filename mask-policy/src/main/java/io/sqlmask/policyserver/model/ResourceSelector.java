package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * The resource a policy applies to: a fully qualified table plus, for
 * {@link PolicyType#DATAMASK}, the targeted columns; empty for
 * {@link PolicyType#ROW_FILTER}.
 */
public record ResourceSelector(String catalog, String schema, String table, List<String> columns) {

  public ResourceSelector {
    columns = List.copyOf(columns);
  }
}
