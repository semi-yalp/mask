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
 * Hive: backtick identifiers, unquoted names fold to lower case (Hive storage
 * semantics), case-insensitive matching, LENIENT conformance with the
 * Hive/Spark INSERT OVERWRITE extension enabled. Wrapper and row-filter
 * rendering reuse the MySQL-style projection aliases; babel-only CREATE
 * TABLE variants are refused so only plain CTAS reaches the composer.
 */
public final class HiveDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "hive";

  public HiveDialectAdapter() {
    this(DialectFeatures.DEFAULTS);
  }

  /** Features override the dialect-default syntax extensions (topN, insertOverwrite). */
  public HiveDialectAdapter(DialectFeatures features) {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.LENIENT,
                features.topNOrDefault(false), features.insertOverwriteOrDefault(true))),
        SqlConformanceEnum.LENIENT,
        false,
        MysqlFunctions.TABLE,
        new HiveTypeResolver(),
        new HiveUnparseDialect(),
        new HiveIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
        DialectCapabilities.STRICT_NO_ALIAS_LIST));
  }

}
