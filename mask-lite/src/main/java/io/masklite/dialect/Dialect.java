package io.masklite.dialect;

import io.masklite.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;

/**
 * Dialect boundary of the SELECT-masking pipeline: parsing,
 * validation/conversion and SQL rendering. Implementations must keep
 * engine-specific details out of the pipeline.
 */
public interface Dialect {

  /** Dialect name ({@code "postgresql"}), as used by {@link DialectRegistry}. */
  String name();

  /** Parses a single statement; only {@code SELECT} and {@code WITH ... SELECT} are accepted. */
  SqlNode parse(String sql, int statementOrdinal);

  /** Validates and converts a parsed statement against {@code rootSchema}. */
  ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema);

  /** Renders a statement as SQL text in this dialect (no trailing ';'). */
  String unparse(SqlNode node);

  /** Identifier rendering rules of the generated outer projection. */
  IdentifierPolicy identifiers();

  /** The declarative profile backing this dialect. */
  DialectProfile profile();
}
