package io.masklite.dialect;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/**
 * PostgreSQL dialect: babel parser factory (PostgreSQL syntax extensions),
 * PostgreSQL identifier semantics (unquoted identifiers fold to lower case,
 * double-quoted identifiers keep their case) and PostgreSQL SQL rendering.
 * Parsing, validation/conversion and rendering of SELECT queries only.
 */
public final class PostgresDialect extends AbstractCalciteDialect {

  public static final String NAME = "postgresql";

  public PostgresDialect() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlBabelParserImpl.FACTORY)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true)
            .withConformance(SqlConformanceEnum.DEFAULT),
        SqlConformanceEnum.DEFAULT,
        true,
        PostgresqlFunctions.TABLE,
        new PostgresqlTypeResolver(),
        PostgresqlSqlDialect.DEFAULT,
        new PostgresqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA));
  }
}
