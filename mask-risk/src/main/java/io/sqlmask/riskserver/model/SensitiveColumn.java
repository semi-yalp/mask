package io.sqlmask.riskserver.model;

/**
 * One entry of the sensitive-asset registry: a fully qualified column with its
 * sensitivity classification. Drives the behavior rules (burst, SELECT *,
 * mask-bypass, first-access baseline) instead of raw SQL text matching alone.
 */
public record SensitiveColumn(
    String columnKey,
    RiskSeverity sensitivity,
    String category,
    boolean enabled,
    String source) {

  public SensitiveColumn {
    if (columnKey == null || columnKey.isBlank()) {
      throw new IllegalArgumentException("columnKey is required (catalog.schema.table.column)");
    }
    if (sensitivity == null) {
      sensitivity = RiskSeverity.MEDIUM;
    }
    category = category == null || category.isBlank() ? "OTHER" : category;
    source = source == null || source.isBlank() ? "MANUAL" : source;
  }

  /** Last dot-separated token, e.g. {@code phone} for crm.public.customer.phone. */
  public String columnName() {
    String[] parts = columnKey.split("\\.");
    return parts[parts.length - 1];
  }

  /** Everything before the column, e.g. {@code crm.public.customer}. */
  public String tableKey() {
    int idx = columnKey.lastIndexOf('.');
    return idx > 0 ? columnKey.substring(0, idx) : columnKey;
  }

  /** Bare table name (no catalog/schema) for tolerant SQL matching. */
  public String tableName() {
    String[] parts = columnKey.split("\\.");
    return parts.length >= 2 ? parts[parts.length - 2] : columnKey;
  }
}
