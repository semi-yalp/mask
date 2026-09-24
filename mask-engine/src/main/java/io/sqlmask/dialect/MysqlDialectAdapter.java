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
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.List;

/**
 * MySQL: backtick identifiers, no case folding, case-insensitive matching
 * (column names are case-insensitive in MySQL), MYSQL_5 conformance. Uses
 * the mask parser factory (babel-grammar derived, dialect extensions gated
 * off) because Calcite's standard parser carries no DDL grammar (plain
 * CREATE TABLE AS SELECT could not parse); the babel-only
 * CREATE TABLE variants (REPLACE / VOLATILE / SET / MULTISET) are refused
 * via {@link #checkCreateTableVariant} so only plain CTAS reaches the
 * composer.
 */
public final class MysqlDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "mysql";

  public MysqlDialectAdapter() {
    this(DialectFeatures.DEFAULTS);
  }

  /** Features override the dialect-default syntax extensions (topN, insertOverwrite). */
  public MysqlDialectAdapter(DialectFeatures features) {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5,
                features.topNOrDefault(false), features.insertOverwriteOrDefault(false))),
        SqlConformanceEnum.MYSQL_5,
        false,
        MysqlFunctions.TABLE,
        new MysqlTypeResolver(),
        new MysqlUnparseDialect(),
        new MysqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
        DialectCapabilities.STRICT_NO_ALIAS_LIST));
  }

}
