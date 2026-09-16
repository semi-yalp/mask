package io.sqlmask.policyserver.model;

/** The two policy kinds the rewrite engine understands. */
public enum PolicyType {
  /** Column masking: apply a UDF with scalar arguments to selected columns. */
  DATAMASK,
  /** Row filtering: a static boolean condition applied to every read of a table. */
  ROW_FILTER
}
