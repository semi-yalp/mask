package io.sqlmask.introspect;

/**
 * The normalized table kinds carried through the metadata chain. Source
 * values differ per engine (PG relkind, MySQL/Trino TABLE_TYPE); each
 * introspector maps its own values onto these constants.
 */
public final class TableKind {

  public static final String TABLE = "table";
  public static final String VIEW = "view";
  public static final String MATERIALIZED_VIEW = "materialized_view";

  private TableKind() {
  }

  /** True when {@code kind} is one of the three legal values. */
  public static boolean isKnown(String kind) {
    return TABLE.equals(kind) || VIEW.equals(kind) || MATERIALIZED_VIEW.equals(kind);
  }
}
