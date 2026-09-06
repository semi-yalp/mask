package io.sqlmask.metaserver.model;

import java.util.List;

/**
 * One table of an instance: ordered columns whose types are the engine's own
 * type name text (the YAML type per the dialect), never a parsed form.
 */
public record TableStructure(String catalog, String schema, String name,
    List<ColumnStructure> columns) {

  public TableStructure {
    columns = List.copyOf(columns);
  }

  public String qualifiedName() {
    return catalog + "." + schema + "." + name;
  }

  public record ColumnStructure(String name, String type) {
  }
}
