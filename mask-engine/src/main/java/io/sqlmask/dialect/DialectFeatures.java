package io.sqlmask.dialect;

/**
 * Per-instance overrides of the dialect's syntax-extension switches. A
 * {@code null} element keeps the dialect's default (e.g. INSERT OVERWRITE
 * is a Hive/SparkSQL default; TOP is off everywhere by default). The
 * rewrite path passes these through so a StarRocks-via-mysql or a
 * SQL-Server-style TOP deployment can opt in without a new dialect.
 */
public record DialectFeatures(Boolean topN, Boolean insertOverwrite) {

  /** The dialect-default features: no override at all. */
  public static final DialectFeatures DEFAULTS = new DialectFeatures(null, null);

  public boolean topNOrDefault(boolean dialectDefault) {
    return topN == null ? dialectDefault : topN;
  }

  public boolean insertOverwriteOrDefault(boolean dialectDefault) {
    return insertOverwrite == null ? dialectDefault : insertOverwrite;
  }
}
