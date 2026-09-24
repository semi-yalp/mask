package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * The resource a policy applies to: a fully qualified table plus, for
 * {@link PolicyType#DATAMASK}, the targeted columns; empty for
 * {@link PolicyType#ROW_FILTER}. {@code inheritColumns} names the columns
 * whose masking must be inherited when the table is copied (CTAS) — always a
 * subset of {@code columns}, empty when unset.
 */
public record ResourceSelector(String catalog, String schema, String table,
    List<String> columns, List<String> inheritColumns) {

  public ResourceSelector {
    columns = List.copyOf(columns);
    inheritColumns = inheritColumns == null ? List.of() : List.copyOf(inheritColumns);
  }

  /** 兼容既有调用:无继承列。 */
  public ResourceSelector(String catalog, String schema, String table, List<String> columns) {
    this(catalog, schema, table, columns, List.of());
  }
}
