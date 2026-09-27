package io.masklite.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 顶层分号拆分的 PG 词法边界。break 对应：字符串/引用体/注释里的分号被
 * 误当拆分点（语句被拦腰截断），或注释/空白片段被当成语句（解析必炸）。
 */
class SqlStatementSplitterTest {

  private final SqlStatementSplitter splitter = new SqlStatementSplitter();

  @Test
  void splitsOnTopLevelSemicolons() {
    assertEquals(List.of("SELECT 1", "SELECT 2"),
        splitter.split("SELECT 1; SELECT 2;"));
  }

  @Test
  void keepsTrailingStatementWithoutSemicolon() {
    assertEquals(List.of("SELECT 1", "SELECT 2"),
        splitter.split("SELECT 1; SELECT 2"));
  }

  @Test
  void dollarQuotedBodyNeverSplits() {
    assertEquals(List.of("SELECT $$a;b$$", "SELECT 1"),
        splitter.split("SELECT $$a;b$$; SELECT 1"));
  }

  @Test
  void taggedDollarQuotesMatchOnlyTheirOwnTag() {
    assertEquals(List.of("SELECT $fn$ ; $fn$", "SELECT 2"),
        splitter.split("SELECT $fn$ ; $fn$; SELECT 2"));
  }

  @Test
  void dollarQuoteEndsAtItsOwnTagOnly() {
    // "$fn$...$$..." — the $$ inside is plain body text, not a terminator
    assertEquals(List.of("SELECT $fn$ a$$b $fn$", "SELECT 3"),
        splitter.split("SELECT $fn$ a$$b $fn$; SELECT 3"));
  }

  @Test
  void escapedQuoteInEStringNeverSplits() {
    assertEquals(List.of("SELECT E'a\\';b'", "SELECT 4"),
        splitter.split("SELECT E'a\\';b'; SELECT 4"));
  }

  @Test
  void doubledQuoteInPlainStringNeverSplits() {
    assertEquals(List.of("SELECT 'it''s;fine'", "SELECT 5"),
        splitter.split("SELECT 'it''s;fine'; SELECT 5"));
  }

  @Test
  void quotedIdentifierNeverSplits() {
    assertEquals(List.of("SELECT \"a;b\" FROM t", "SELECT 6"),
        splitter.split("SELECT \"a;b\" FROM t; SELECT 6"));
  }

  @Test
  void nestedBlockCommentsNeverSplit() {
    assertEquals(List.of("SELECT /* a ; /* b ; */ c ; */ 1", "SELECT 7"),
        splitter.split("SELECT /* a ; /* b ; */ c ; */ 1; SELECT 7"));
  }

  @Test
  void semicolonInLineCommentIsNotASplitPoint() {
    assertEquals(List.of("SELECT 1 -- ; not a split", "SELECT 8"),
        splitter.split("SELECT 1 -- ; not a split\n; SELECT 8"));
  }

  @Test
  void blankAndCommentOnlyFragmentsAreDropped() {
    assertEquals(List.of("SELECT 9"),
        splitter.split(";; SELECT 9 ; -- trailing note"));
  }

  @Test
  void emptyInputYieldsNoStatements() {
    assertEquals(List.of(), splitter.split(""));
    assertEquals(List.of(), splitter.split(null));
    assertEquals(List.of(), splitter.split("   -- nothing but a comment\n"));
  }
}
