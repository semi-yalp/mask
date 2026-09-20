package io.sqlmask.parser;

import java.util.List;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the handwritten {@link SqlInsertOverwrite#unparse}
 * (gap §4.8-1) and the {@link SqlInsertOverwrite#isOverwrite()} accessor
 * (gap §4.8-4). The existing InsertOverwrite cases only assert
 * instanceof/kind, so the handwritten prefix / column-list / newline-indent
 * unparse branches had zero assertions; a broken unparse would emit illegal
 * SQL unseen by the suite.
 *
 * <p>Unparse is driven through Calcite's {@link SqlPrettyWriter} with the
 * same config as {@link SqlNode#toString()} except
 * {@code quoteAllIdentifiers(false)}: keywords stay upper case ("INSERT
 * OVERWRITE TABLE"), unquoted identifiers are normalized to upper case but
 * printed raw (the mask parser's tokenizer rejects the backtick quoting that
 * the default AnsiSqlDialect emits). Each case pins the exact normalized
 * golden, plus a re-parse fixed point proving the emitted SQL is valid and
 * still an INSERT OVERWRITE.
 */
class InsertOverwriteUnparseTest {

  private static SqlParser parser(String sql) {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, true)));
  }

  private static SqlNode parse(String sql) throws SqlParseException {
    return parser(sql).parseStmt();
  }

  /** Same writer config as SqlNode#toString(), minus identifier quoting. */
  private static String unparse(SqlNode node) {
    SqlPrettyWriter writer = new SqlPrettyWriter(
        SqlPrettyWriter.config()
            .withDialect(org.apache.calcite.sql.dialect.AnsiSqlDialect.DEFAULT)
            .withAlwaysUseParentheses(false)
            .withSelectListItemsOnSeparateLines(false)
            .withUpdateSetListNewline(false)
            .withIndentation(0)
            .withQuoteAllIdentifiers(false));
    node.unparse(writer, 0, 0);
    // SqlPrettyWriter uses System.lineSeparator(), so normalize CRLF on
    // Windows before comparing with the \n goldens (and before re-parsing).
    return writer.toString().replace("\r\n", "\n");
  }

  private record Case(String input, String expected) {
  }

  private static final List<Case> CASES = List.of(
      new Case("INSERT OVERWRITE TABLE t SELECT id FROM t",
          "INSERT OVERWRITE TABLE T\nSELECT ID\nFROM T"),
      // [TABLE] is optional in the grammar; unparse must restore the TABLE keyword.
      new Case("INSERT OVERWRITE t SELECT id FROM t",
          "INSERT OVERWRITE TABLE T\nSELECT ID\nFROM T"),
      // column-list branch: (a, b) must survive the round trip.
      new Case("INSERT OVERWRITE TABLE t (a, b) SELECT 1, 2",
          "INSERT OVERWRITE TABLE T (A, B)\nSELECT 1, 2"),
      new Case("INSERT OVERWRITE TABLE t (a) VALUES (1)",
          "INSERT OVERWRITE TABLE T (A)\nVALUES ROW(1)"),
      new Case("INSERT OVERWRITE TABLE t (a, b) VALUES (1, 2), (3, 4)",
          "INSERT OVERWRITE TABLE T (A, B)\nVALUES ROW(1, 2),\nROW(3, 4)"),
      new Case("INSERT OVERWRITE TABLE t SELECT a AS x, COUNT(*) FROM t GROUP BY a",
          "INSERT OVERWRITE TABLE T\nSELECT A AS X, COUNT(*)\nFROM T\nGROUP BY A"));

  @Test
  void insertOverwriteUnparsesBackToSameSyntax() throws SqlParseException {
    for (Case c : CASES) {
      SqlNode node = parse(c.input());
      assertTrue(node instanceof SqlInsertOverwrite, () -> c.input());
      assertEquals(c.expected(), unparse(node), () -> "unparse mismatch for: " + c.input());
      // Fixed point: unparse output must re-parse to the same semantics
      // (still INSERT OVERWRITE) and to identical text.
      SqlNode reparsed = parse(c.expected());
      assertTrue(reparsed instanceof SqlInsertOverwrite,
          () -> "overwrite semantics lost after round trip: " + c.expected());
      assertEquals(c.expected(), unparse(reparsed));
    }
  }

  @Test
  void isOverwriteAccessorContract() throws SqlParseException {
    SqlNode overwrite = parse("INSERT OVERWRITE TABLE t SELECT id FROM t");
    assertTrue(overwrite instanceof SqlInsertOverwrite);
    assertTrue(((SqlInsertOverwrite) overwrite).isOverwrite());
    // Plain INSERT INTO stays a plain SqlInsert: the constant accessor must
    // never leak onto it.
    SqlNode plain = parse("INSERT INTO t SELECT id FROM t");
    assertFalse(plain instanceof SqlInsertOverwrite);
  }
}