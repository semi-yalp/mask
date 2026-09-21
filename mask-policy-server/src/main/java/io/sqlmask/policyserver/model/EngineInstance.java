package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * One managed query engine: registered name, the SQL dialect it speaks, the
 * optional live connection used for connectivity tests and metadata listing,
 * its connection status, and the tables (with typed columns) it exposes.
 * {@code connection} is null for metadata-only instances; their status is
 * {@link ConnectionStatus#UNCONNECTED}.
 */
public record EngineInstance(String name, String dialect, ConnectionConfig connection,
    ConnectionStatus status, List<TableDef> tables) {

  public EngineInstance {
    tables = tables == null ? List.of() : List.copyOf(tables);
    status = status == null ? ConnectionStatus.UNCONNECTED : status;
  }
}