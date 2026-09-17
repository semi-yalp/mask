package io.sqlmask.parser;

import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

/**
 * Parser-side conformance：委托底层方言 conformance，外加两个方言扩展开关。
 * 仅用于 SqlParser.config；校验器一律使用未包装的原 conformance（spec §4.5）。
 */
public final class SqlMaskConformance extends SqlDelegatingConformance {

  private final boolean allowTopN;
  private final boolean allowInsertOverwrite;

  private SqlMaskConformance(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite) {
    super(delegate);
    this.allowTopN = allowTopN;
    this.allowInsertOverwrite = allowInsertOverwrite;
  }

  public static SqlMaskConformance of(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite) {
    return new SqlMaskConformance(delegate, allowTopN, allowInsertOverwrite);
  }

  /** SQL Server 风格 SELECT TOP (n) 是否放行。 */
  public boolean isTopNAllowed() {
    return allowTopN;
  }

  /** Hive/Spark/Doris 风格 INSERT OVERWRITE 是否放行。 */
  public boolean isInsertOverwriteAllowed() {
    return allowInsertOverwrite;
  }
}
