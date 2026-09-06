package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * One managed query engine: its registered name, the SQL dialect it speaks
 * and the tables (with typed columns) the engine is allowed to read.
 */
public record EngineInstance(String name, String dialect, List<TableDef> tables) {

  public EngineInstance {
    tables = List.copyOf(tables);
  }
}
