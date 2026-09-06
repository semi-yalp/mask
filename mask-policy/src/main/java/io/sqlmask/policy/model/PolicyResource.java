package io.sqlmask.policy.model;

/**
 * One resource path entry. Every level is a concrete identifier or "*";
 * {@code column} is null for table-level (row filter) resources. Levels are
 * normalized on construction.
 */
public record PolicyResource(String catalog, String schema, String table, String column) {

  public PolicyResource {
    catalog = PolicyNames.normalize(catalog, "catalog");
    schema = PolicyNames.normalize(schema, "schema");
    table = PolicyNames.normalize(table, "table");
    column = column == null ? null : PolicyNames.normalize(column, "column");
  }

  public static PolicyResource table(String catalog, String schema, String table) {
    return new PolicyResource(catalog, schema, table, null);
  }

  public static PolicyResource column(String catalog, String schema, String table, String column) {
    return new PolicyResource(catalog, schema, table, column);
  }
}
