package io.masklite.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Statement splitting on top-level semicolons only: semicolons inside
 * string literals, escaped strings, quoted identifiers, dollar-quoted
 * strings and comments never split.
 */
class SqlStatementSplitterTest {

  private final SqlStatementSplitter splitter = new SqlStatementSplitter();

  private List<String> split(String sql) {
    return splitter.split(sql);
  }

  @Test
  void splitsOnTopLevelSemicolons() {
    assertEquals(List.of("SELECT 1", "SELECT 2"), split("SELECT 1; SELECT 2"));
  }

  @Test
  void dropsBlankStatements() {
    assertEquals(List.of("SELECT 1", "SELECT 2"), split("; SELECT 1 ; ; SELECT 2 ;"));
  }

  @Test
  void semicolonInsideSingleQuotedStringDoesNotSplit() {
    assertEquals(
        List.of("SELECT note FROM t WHERE note = 'it''s;a;b'"),
        split("SELECT note FROM t WHERE note = 'it''s;a;b'"));
  }

  @Test
  void semicolonInsideEscapedEStringDoesNotSplit() {
    assertEquals(
        List.of("SELECT note FROM t WHERE note = E'a\\';b'"),
        split("SELECT note FROM t WHERE note = E'a\\';b'"));
  }

  @Test
  void semicolonInsideDollarQuotedStringDoesNotSplit() {
    assertEquals(
        List.of("SELECT $$a;b$$", "SELECT 2"),
        split("SELECT $$a;b$$; SELECT 2"));
  }

  @Test
  void semicolonInsideTaggedDollarQuoteDoesNotSplit() {
    assertEquals(
        List.of("SELECT $tag$x;y$tag$"),
        split("SELECT $tag$x;y$tag$"));
  }

  @Test
  void semicolonInsideQuotedIdentifierDoesNotSplit() {
    assertEquals(
        List.of("SELECT \"a;b\" FROM t"),
        split("SELECT \"a;b\" FROM t"));
  }

  @Test
  void semicolonInsideLineCommentDoesNotSplit() {
    assertEquals(
        List.of("SELECT 1 -- ; not a split\nFROM t", "SELECT 2"),
        split("SELECT 1 -- ; not a split\nFROM t; SELECT 2"));
  }

  @Test
  void semicolonInsideBlockCommentDoesNotSplit() {
    assertEquals(
        List.of("SELECT /* a ; b */ 1", "SELECT 2"),
        split("SELECT /* a ; b */ 1; SELECT 2"));
  }

  @Test
  void emptyAndNullInputProduceNoStatements() {
    assertEquals(List.of(), split(null));
    assertEquals(List.of(), split("  \n "));
  }
}
