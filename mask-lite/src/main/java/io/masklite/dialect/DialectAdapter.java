package io.masklite.dialect;

import io.masklite.sql.ValidatedSql;
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
   * {@link io.masklite.error.SqlMaskException.Code#UNSUPPORTED_STATEMENT} and
   * a diagnostic containing {@code statementOrdinal}.
   */
  SqlNode parse(String sql, int statementOrdinal);

  /** Validates and converts a parsed statement against {@code rootSchema}. */
  ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema);

  /** Renders a statement as SQL text in this dialect (no trailing ';'). */
  String unparse(SqlNode node);

  /** The declarative profile backing this adapter. */
  DialectProfile profile();

  /** What this dialect can safely express during rewriting. */
  default DialectCapabilities capabilities() {
    return profile().capabilities();
  }
}
