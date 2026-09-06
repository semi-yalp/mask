package io.sqlmask.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStatementSplitterTest {

  private final SqlStatementSplitter splitter = new SqlStatementSplitter();

  @Test
  void splitsTwoStatementsPreservingOrder() {
    String sql = """
        SELECT phone FROM customer;

        WITH active AS (SELECT phone FROM customer)
        SELECT phone FROM active;
        """;
    List<String> statements = splitter.split(sql);
    assertEquals(2, statements.size());
    assertEquals("SELECT phone FROM customer", statements.get(0));
    assertEquals("""
        WITH active AS (SELECT phone FROM customer)
        SELECT phone FROM active""", statements.get(1));
  }

  @Test
  void ignoresEmptyStatements() {
    List<String> statements = splitter.split(";;   \n\t ; SELECT 1;   ;");
    assertEquals(List.of("SELECT 1"), statements);
  }

  @Test
  void doesNotSplitOnSemicolonInsideString() {
    List<String> statements = splitter.split("SELECT ';' AS v FROM t;\nSELECT 1;");
    assertEquals(2, statements.size());
    assertEquals("SELECT ';' AS v FROM t", statements.get(0));
  }

  @Test
  void doesNotSplitOnSemicolonInsideEscapedString() {
    List<String> statements = splitter.split("SELECT E'a\\';b' AS v FROM t;\nSELECT 1;");
    assertEquals(2, statements.size());
    assertEquals("SELECT E'a\\';b' AS v FROM t", statements.get(0));
  }

  @Test
  void doesNotSplitOnSemicolonInsideDollarQuotedString() {
    List<String> statements = splitter.split("SELECT $$a;b$$ AS v FROM t;\nSELECT $tag$c;d$tag$ AS w FROM u;\nSELECT 1;");
    assertEquals(3, statements.size());
    assertEquals("SELECT $$a;b$$ AS v FROM t", statements.get(0));
    assertEquals("SELECT $tag$c;d$tag$ AS w FROM u", statements.get(1));
  }

  @Test
  void doesNotSplitInsideComments() {
    List<String> statements = splitter.split("""
        -- a comment; with a semicolon
        SELECT 1;
        /* block; comment /* nested; */ still comment; */
        SELECT 2;
        """);
    assertEquals(2, statements.size());
    // comments are part of the statement text; semicolons inside them never split
    assertTrue(statements.get(1).endsWith("SELECT 2"),
        () -> "unexpected statement: " + statements.get(1));
  }

  @Test
  void doesNotSplitOnSemicolonInsideQuotedIdentifier() {
    List<String> statements = splitter.split("SELECT \";\" AS v FROM t;\nSELECT 1;");
    assertEquals(2, statements.size());
    assertEquals("SELECT \";\" AS v FROM t", statements.get(0));
  }

  @Test
  void keepsTrailingStatementWithoutSemicolon() {
    List<String> statements = splitter.split("SELECT 1;\nSELECT 2");
    assertEquals(List.of("SELECT 1", "SELECT 2"), statements);
  }

  @Test
  void emptyInputYieldsNoStatements() {
    assertEquals(List.of(), splitter.split(""));
    assertEquals(List.of(), splitter.split("   \n ; ; \n"));
    assertEquals(List.of(), splitter.split(null));
  }
}
