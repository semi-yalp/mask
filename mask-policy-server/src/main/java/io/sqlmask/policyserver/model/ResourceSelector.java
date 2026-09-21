package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * The resource a policy applies to: a fully qualified table plus, for
 * {@link PolicyType#DATAMASK}, the targeted columns; empty for
 * {@link PolicyType#ROW_FILTER}. Any level may carry a glob pattern
 * ({@code *} any sequence, {@code ?} one character) — the data plane expands
 * these into concrete tables/columns when compiling the effective config.
 */
public record ResourceSelector(String catalog, String schema, String table, List<String> columns) {

  public ResourceSelector {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}