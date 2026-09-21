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
 * Spark SQL: backtick identifiers, unquoted names fold to lower case (Hive
 * storage semantics), case-insensitive matching, LENIENT conformance with the
 * Hive/Spark INSERT OVERWRITE extension enabled. Wrapper and row-filter
 * rendering reuse the MySQL-style projection aliases; babel-only CREATE
 * TABLE variants are refused so only plain CTAS reaches the composer.
 */
public final class SparkSqlDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "sparksql";

  public SparkSqlDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.LENIENT, false, true)),
        SqlConformanceEnum.LENIENT,
        false,
        MysqlFunctions.TABLE,
        new SparkSqlTypeResolver(),
        new SparkSqlUnparseDialect(),
        new SparkSqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
        new DialectCapabilities(false)));
  }

  /** Refuses babel-only CREATE TABLE variants whose syntax the composer cannot reproduce. */
  @Override
  protected void checkCreateTableVariant(SqlNode writeStatement) {
    if (!(writeStatement instanceof SqlBabelCreateTable babel)) {
      return;
    }
    List<SqlNode> operands = babel.getOperandList();
    boolean replace = ((SqlLiteral) operands.get(0)).booleanValue();
    TableCollectionType collectionType =
        ((SqlLiteral) operands.get(1)).symbolValue(TableCollectionType.class);
    boolean volatileTable = ((SqlLiteral) operands.get(2)).booleanValue();
    if (replace || volatileTable
        || collectionType == TableCollectionType.MULTISET) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "unsupported CREATE TABLE variant (REPLACE / VOLATILE / SET / MULTISET); "
              + "only plain CREATE TABLE [IF NOT EXISTS] ... AS SELECT is supported (sparksql)");
    }
  }
}
