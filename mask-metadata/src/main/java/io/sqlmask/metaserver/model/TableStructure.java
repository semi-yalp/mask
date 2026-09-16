package io.sqlmask.metaserver.model;

import io.sqlmask.introspect.TableKind;

import java.util.List;

/**
 * One table of an instance: ordered columns whose types are the engine's own
 * type name text (the YAML type per the dialect), never a parsed form.
 * {@code kind} is the normalized {@link TableKind} value collected from the
 * source engine ("table" for plain tables).
 */
public record TableStructure(String catalog, String schema, String name, String kind,
    List<ColumnStructure> columns) {

  public TableStructure {
    columns = List.copyOf(columns);
    kind = kind == null || kind.isBlank() ? TableKind.TABLE : kind;
  }

  /** Convenience constructor for plain tables (kind defaults to "table"). */
  public TableStructure(String catalog, String schema, String name,
      List<ColumnStructure> columns) {
    this(catalog, schema, name, TableKind.TABLE, columns);
  }

  public String qualifiedName() {
    return catalog + "." + schema + "." + name;
  }

  public record ColumnStructure(String name, String type) {
  }
}
