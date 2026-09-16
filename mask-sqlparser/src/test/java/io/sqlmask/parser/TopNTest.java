package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNTest {

  private static SqlNode parse(String sql) throws SqlParseException {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, true, false)))
        .parseStmt();
  }

  @Test
  void parsesTopWithoutParenthesesIntoFetch() throws SqlParseException {
    SqlNode node = parse("SELECT TOP 10 id FROM t");
    assertEquals(SqlKind.SELECT, node.getKind());
    assertNotNull(((SqlSelect) node).getFetch());
  }

  @Test
  void parsesParenthesizedTopIntoFetch() throws SqlParseException {
    assertNotNull(((SqlSelect) parse("SELECT TOP (10) id FROM t")).getFetch());
  }

  @Test
  void topCoexistsWithOrderBy() throws SqlParseException {
    assertEquals(SqlKind.ORDER_BY, parse("SELECT TOP 3 id FROM t ORDER BY id").getKind());
  }

  @Test
  void rejectsPercent() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP 10 PERCENT id FROM t"));
    assertTrue(e.getMessage().contains("PERCENT"), () -> e.getMessage());
  }

  @Test
  void rejectsWithTies() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP (10) WITH TIES id FROM t ORDER BY id"));
    assertTrue(e.getMessage().contains("WITH TIES"), () -> e.getMessage());
  }

  @Test
  void rejectsTopCombinedWithLimit() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP 5 id FROM t LIMIT 2"));
    assertTrue(e.getMessage().contains("TOP"), () -> e.getMessage());
  }

  @Test
  void failsWithClearMessageWhenDialectDisallows() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> SqlParser.create("SELECT TOP 10 id FROM t",
            SqlParser.config()
                .withParserFactory(SqlMaskParserImpl.FACTORY)
                .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false)))
            .parseStmt());
    assertTrue(e.getMessage().contains("not enabled"), () -> e.getMessage());
  }
}
