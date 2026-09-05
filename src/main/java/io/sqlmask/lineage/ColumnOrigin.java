package io.sqlmask.lineage;

import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.error.SqlMaskException;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.metadata.RelColumnOrigin;

import java.util.List;
import java.util.Objects;

/**
 * One base-column origin of an output expression: the normalized
 * {@link ColumnKey} of the underlying {@code catalog.schema.table.column},
 * the qualified table name as registered, and whether the column is only a
 * derived (transformed) input of the expression.
 *
 * <p>Equality is based on the normalized {@link ColumnKey} alone so origin
 * sets deduplicate base columns regardless of derivation path.
 */
public final class ColumnOrigin {

  private final ColumnKey key;
  private final String tableName;
  private final boolean derived;

  private ColumnOrigin(ColumnKey key, String tableName, boolean derived) {
    this.key = key;
    this.tableName = tableName;
    this.derived = derived;
  }

  public static ColumnOrigin of(ColumnKey key, String tableName, boolean derived) {
    return new ColumnOrigin(key, tableName, derived);
  }

  /**
   * Converts a Calcite column origin into the normalized form. Requires the
   * origin table to resolve to a full {@code catalog.schema.table} path.
   */
  public static ColumnOrigin from(RelColumnOrigin origin) {
    RelOptTable table = origin.getOriginTable();
    List<String> qualifiedName = table.getQualifiedName();
    if (qualifiedName.size() != 3) {
      throw new SqlMaskException(SqlMaskException.Code.LINEAGE_UNKNOWN,
          "cannot determine the full catalog.schema.table path of origin table "
              + qualifiedName);
    }
    String column = table.getRowType()
        .getFieldList()
        .get(origin.getOriginColumnOrdinal())
        .getName();
    return new ColumnOrigin(
        ColumnKey.of(qualifiedName.get(0), qualifiedName.get(1), qualifiedName.get(2), column),
        String.join(".", qualifiedName),
        origin.isDerived());
  }

  public ColumnKey key() {
    return key;
  }

  /** Qualified table name as registered in the schema, for diagnostics. */
  public String tableName() {
    return tableName;
  }

  /** True when the column only feeds a derived expression. */
  public boolean isDerived() {
    return derived;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ColumnOrigin other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key);
  }

  @Override
  public String toString() {
    return (derived ? key + " (derived)" : key.toString());
  }
}
