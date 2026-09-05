package io.sqlmask.dialect;

import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;

/**
 * Dialect boundary: parsing, validation/conversion and SQL rendering.
 * Implementations must keep PostgreSQL-only details out of this interface.
 */
public interface DialectAdapter {

  /** Dialect name, as accepted by the {@code --dialect} CLI option. */
  String name();

  /**
   * Parses a single statement and classifies it. Only {@code SELECT} and
   * {@code WITH ... SELECT} are accepted; DML/DDL fail with
   * {@link io.sqlmask.error.SqlMaskException.Code#UNSUPPORTED_STATEMENT} and
   * a diagnostic containing {@code statementOrdinal}.
   */
  SqlNode parse(String sql, int statementOrdinal);

  /** Validates and converts a parsed statement against {@code rootSchema}. */
  ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema);

  /**
   * For a write statement ({@code INSERT ... SELECT}, {@code CREATE TABLE AS
   * SELECT}), returns the query that feeds the rows to be written, or null
   * when the statement carries no traceable query (e.g. {@code INSERT ...
   * VALUES} with plain literals).
   */
  SqlNode querySourceOf(SqlNode writeStatement);

  /**
   * True when a write statement can pass through unchanged because it has no
   * query source whose columns could need masking (and no query hidden
   * inside, e.g. a scalar subquery inside {@code VALUES}).
   */
  boolean isPassThroughWrite(SqlNode writeStatement);

  /**
   * Renders a write statement around the (already rewritten) source query
   * text, preserving the original target, column list and keywords.
   */
  String composeWriteStatement(SqlNode writeStatement, String wrappedQuery);

  /** Renders a statement as SQL text in this dialect (no trailing ';'). */
  String unparse(SqlNode node);

  /** What this dialect can safely express during rewriting. */
  DialectCapabilities capabilities();
}
