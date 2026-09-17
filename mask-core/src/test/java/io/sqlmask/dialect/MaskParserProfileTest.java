package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.parser.SqlMaskConformance;
import io.sqlmask.parser.SqlInsertOverwrite;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskParserProfileTest {

  /** 开关全开的测试专用 profile（生产三方言开关全关，spec §5）。 */
  private static final DialectProfile OPEN_PROFILE = new DialectProfile(
      "mysql-open",
      org.apache.calcite.sql.parser.SqlParser.config()
          .withParserFactory(io.sqlmask.parser.SqlMaskParserImpl.FACTORY)
          .withQuoting(Quoting.BACK_TICK)
          .withUnquotedCasing(Casing.UNCHANGED)
          .withQuotedCasing(Casing.UNCHANGED)
          .withCaseSensitive(false)
          .withConformance(SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5, true, true)),
      SqlConformanceEnum.MYSQL_5,
      false,
      MysqlFunctions.TABLE,
      new MysqlTypeResolver(),
      new MysqlUnparseDialect(),
      new MysqlIdentifierPolicy(),
      DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
      new DialectCapabilities(false));

  private final AbstractCalciteDialectAdapter openAdapter = openAdapter();

  /** 开关全开的测试适配器（端到端管线测试复用；生产三方言开关全关，spec §5）。 */
  static AbstractCalciteDialectAdapter openAdapter() {
    return new AbstractCalciteDialectAdapter(OPEN_PROFILE) {};
  }

  @Test
  void insertOverwriteClassifiesAsInsertAndComposesWithOverwriteHeader()
      throws SqlParseException {
    SqlNode node = openAdapter.parse(
        "INSERT OVERWRITE TABLE `orders` SELECT `id`, `memo` FROM `orders`", 0);
    assertTrue(node instanceof SqlInsertOverwrite);
    assertEquals("INSERT OVERWRITE TABLE `orders` (SELECT `id`, `memo` FROM `orders`)",
        openAdapter.composeWriteStatement(node,
            "(SELECT `id`, `memo` FROM `orders`)"));
  }

  @Test
  void topParsesAndSetsFetch() throws SqlParseException {
    SqlNode node = openAdapter.parse("SELECT TOP (3) `id` FROM `orders`", 0);
    assertNotNull(((SqlSelect) node).getFetch());
  }

  @Test
  void productionMysqlProfileRejectsBothExtensions() {
    MysqlDialectAdapter adapter = new MysqlDialectAdapter();
    SqlMaskException e1 = assertThrows(SqlMaskException.class,
        () -> adapter.parse("INSERT OVERWRITE TABLE `orders` SELECT 1", 0));
    assertEquals(SqlMaskException.Code.PARSE_ERROR, e1.getCode());
    assertTrue(e1.getMessage().contains("not enabled"), () -> e1.getMessage());
    assertThrows(SqlMaskException.class, () -> adapter.parse("SELECT TOP 10 `id` FROM `orders`", 0));
  }
}
