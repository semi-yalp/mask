package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.babel.SqlBabelCreateTable;
import org.apache.calcite.sql.babel.TableCollectionType;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.List;

/**
 * Trino: double-quote identifiers, unquoted fold to lower. Uses the babel
 * parser factory because Calcite's standard parser carries no DDL grammar
 * (plain CREATE TABLE AS SELECT could not parse); the babel-only CREATE
 * TABLE variants (REPLACE / VOLATILE / SET / MULTISET) are refused via
 * {@link #checkCreateTableVariant} so only plain CTAS reaches the composer.
 */
public final class TrinoDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "trino";

  public TrinoDialectAdapter() {
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
        org.apache.calcite.sql.fun.SqlStdOperatorTable.instance(),
        new TrinoTypeResolver(),
        new TrinoUnparseDialect(),
        new TrinoIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA,
        DialectCapabilities.STRICT));
  }

  /** Refuses babel-only CREATE TABLE variants whose syntax the composer cannot reproduce. */
  @Override
  protected void checkCreateTableVariant(SqlNode writeStatement) {
    if (!(writeStatement instanceof SqlBabelCreateTable babel)) {
      return;
    }
    List<SqlNode> operands = babel.getOperandList();
    boolean replace = ((SqlLiteral) operands.get(0)).booleanValue();
    // UNSPECIFIED/null means the plain default
    TableCollectionType collectionType =
        ((SqlLiteral) operands.get(1)).symbolValue(TableCollectionType.class);
    boolean volatileTable = ((SqlLiteral) operands.get(2)).booleanValue();
    if (replace || volatileTable
        || collectionType == TableCollectionType.MULTISET) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "unsupported CREATE TABLE variant (REPLACE / VOLATILE / SET / MULTISET); "
              + "only plain CREATE TABLE [IF NOT EXISTS] ... AS SELECT is supported");
    }
  }
}
