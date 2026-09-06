package io.sqlmask.lineage;

/**
 * Analysis state of one output column's lineage.
 *
 * <ul>
 * <li>{@link #RESOLVED}: traced to one or more base columns; policies can match.</li>
 * <li>{@link #NO_ORIGIN}: the expression does not depend on base columns
 * (constants, parameters); it passes through untouched.</li>
 * <li>{@link #UNKNOWN}: the origin cannot be safely determined; the statement fails.</li>
 * </ul>
 */
public enum LineageStatus {
  RESOLVED,
  NO_ORIGIN,
  UNKNOWN
}
