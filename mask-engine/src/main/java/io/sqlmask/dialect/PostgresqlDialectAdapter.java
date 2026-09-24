package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.parser.SqlMaskConformance;
import io.sqlmask.parser.SqlMaskParserImpl;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.babel.SqlBabelCreateTable;
import org.apache.calcite.sql.babel.TableCollectionType;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.List;

/**
 * PostgreSQL dialect: mask parser factory (PostgreSQL syntax extensions,
 * dialect extensions gated off), PostgreSQL identifier semantics (unquoted
 * identifiers fold to lower case, double-quoted identifiers keep their case)
 * and PostgreSQL SQL rendering.
 */
public final class PostgresqlDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "postgresql";

  public PostgresqlDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.DEFAULT, false, false)),
        SqlConformanceEnum.DEFAULT,
        true,
        PostgresqlFunctions.TABLE,
        new PostgresqlTypeResolver(),
        PostgresqlSqlDialect.DEFAULT,
        new PostgresqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA,
        DialectCapabilities.STRICT));
  }
}
