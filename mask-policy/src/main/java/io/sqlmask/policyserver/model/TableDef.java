package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * A table exposed by an engine: fully qualified identity plus its ordered,
 * declared columns. Row filtering is deliberately absent here — it is a
 * {@link PolicyType#ROW_FILTER} policy, not table metadata.
 */
public record TableDef(String catalog, String schema, String name, List<ColumnDef> columns) {

  public TableDef {
    columns = List.copyOf(columns);
  }
}
