package io.masklite.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lexical edge cases beyond the happy paths: doubled quotes, unterminated
 * literals, non-quote dollar signs and comment-like operators. */
class SqlStatementSplitterEdgeCasesTest {

  private final SqlStatementSplitter splitter = new SqlStatementSplitter();

  @Test
  void stringOpeningTheInputIsStillSkipped() {
    assertEquals(List.of("'a;b'", "SELECT 1"), splitter.split("'a;b';SELECT 1"));
  }

  @Test
  void doubledQuoteInsidePlainStringStaysInside() {
    assertEquals(List.of("SELECT 'it''s;fine'"), splitter.split("SELECT 'it''s;fine';"));
  }

  @Test
  void escapedStringBackslashBeforeEndQuoteIsConsumed() {
    assertEquals(List.of("SELECT E'a\\';b'"), splitter.split("SELECT E'a\\';b';"));
  }

  @Test
  void unterminatedStringConsumesTheRest() {
    assertEquals(List.of("SELECT 'oops"), splitter.split("SELECT 'oops"));
  }

  @Test
  void doubledQuoteInsideQuotedIdentifierStaysInside() {
    assertEquals(List.of("SELECT 1 FROM \"a\"\"b;c\""),
        splitter.split("SELECT 1 FROM \"a\"\"b;c\";"));
  }

  @Test
  void unterminatedQuotedIdentifierConsumesTheRest() {
    assertEquals(List.of("SELECT 1 FROM \"nope"), splitter.split("SELECT 1 FROM \"nope"));
  }

  @Test
  void dollarSignInIdentifierIsNotADollarQuote() {
    assertEquals(List.of("SELECT a$b"), splitter.split("SELECT a$b;"));
  }

  @Test
  void loneDollarWithoutClosingDollarIsNotATag() {
    // "$1$...": the tag inner part must start with a letter, so this $ is plain
    assertEquals(List.of("SELECT $1$, x"), splitter.split("SELECT $1$, x;"));
  }

  @Test
  void dollarTagWithIllegalInnerCharacterIsNotATag() {
    assertEquals(List.of("SELECT $a-b$"), splitter.split("SELECT $a-b$;"));
  }

  @Test
  void unterminatedDollarQuoteConsumesTheRest() {
    assertEquals(List.of("SELECT $$oops"), splitter.split("SELECT $$oops"));
  }

  @Test
  void minusWithoutDashIsPlainText() {
    assertEquals(List.of("SELECT 1 - 2", "SELECT 3"), splitter.split("SELECT 1 - 2;SELECT 3"));
  }

  @Test
  void slashWithoutStarIsPlainDivision() {
    assertEquals(List.of("SELECT 8 / 2", "SELECT 1"), splitter.split("SELECT 8 / 2;SELECT 1"));
  }

  @Test
  void trailingCommentAfterFinalSemicolonIsDropped() {
    // 修复后的行为：末尾分号后的注释-only 片段与空白语句一样被丢弃
    assertEquals(List.of("SELECT 1"), splitter.split("SELECT 1;-- trailing comment"));
  }

  @Test
  void commentOnlyFragmentsBetweenStatementsAreDropped() {
    assertEquals(List.of("SELECT 1", "SELECT 2"), splitter.split(
        "SELECT 1; -- note\n; /* block */ ;;; SELECT 2"));
  }

  @Test
  void leadingCommentBeforeRealStatementIsKept() {
    assertEquals(List.of("-- preamble\nSELECT 1"),
        splitter.split("-- preamble\nSELECT 1;"));
  }

  @Test
  void nestedBlockCommentOnlyFragmentIsDropped() {
    assertEquals(List.of("SELECT 1"), splitter.split("SELECT 1; /* outer /* inner */ */"));
  }

  @Test
  void minusBesideCommentIsContent() {
    // "- -" 不是注释：它是（非法的）表达式片段，应保留交给解析器报错
    assertEquals(List.of("SELECT 1 - -1"), splitter.split("SELECT 1 - -1;"));
  }
}
