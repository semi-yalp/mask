package io.masklite.sql;

import io.masklite.dialect.PostgresDialect;
import io.masklite.error.SqlMaskException;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expansion scenarios beyond the two facade-level CTE tests: column alias
 * lists, references from every SELECT clause position, joins, aliased and
 * nested-WITH FROM items, and the recursive-reference refusal.
 */
class CteExpanderTest {

  private final PostgresDialect dialect = new PostgresDialect();
  private final CteExpander expander = new CteExpander();

  private String expand(String sql) {
    SqlNode parsed = dialect.parse(sql, 1);
    return dialect.unparse(expander.expand(parsed));
  }

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void columnAliasListRidesOnTheDerivedTable() {
    String result = expand("""
        WITH t(a, b) AS (SELECT id, phone FROM crm.public.customer)
        SELECT a FROM t""");
    assertTrue(flat(result).contains("FROM (SELECT id, phone FROM crm.public.customer) AS t (a, b)"),
        () -> result);
  }

  @Test
  void referenceFromWhereInSubqueryIsExpanded() {
    String result = expand("""
        WITH t AS (SELECT id AS v FROM crm.public.customer)
        SELECT id FROM crm.public.customer WHERE id IN (SELECT v FROM t)""");
    assertTrue(flat(result).contains("WHERE id IN (SELECT v FROM"
            + " (SELECT id AS v FROM crm.public.customer) AS t)"),
        () -> result);
  }

  @Test
  void referenceFromHavingAndOrderBySubqueriesIsExpanded() {
    String result = expand("""
        WITH t AS (SELECT id AS v FROM crm.public.customer)
        SELECT status FROM crm.public.customer GROUP BY status
        HAVING count(*) > (SELECT count(*) FROM t)
        ORDER BY (SELECT max(v) FROM t)""");
    String f = flat(result);
    assertTrue(f.contains("HAVING COUNT(*) > (SELECT COUNT(*) FROM"
            + " (SELECT id AS v FROM crm.public.customer) AS t)"),
        () -> result);
    assertTrue(f.contains("ORDER BY (SELECT MAX(v) FROM"
            + " (SELECT id AS v FROM crm.public.customer) AS t)"),
        () -> result);
  }

  @Test
  void joinsBetweenTwoCtesExpandBothSides() {
    String result = expand("""
        WITH a AS (SELECT id FROM crm.public.customer),
             b AS (SELECT id FROM crm.public.customer)
        SELECT a.id FROM a JOIN b ON a.id = b.id""");
    String f = flat(result);
    assertEquals(2, f.split("\\) AS [ab]", -1).length - 1, () -> result);
    assertTrue(f.contains(") AS b ON a.id = b.id"), () -> result);
  }

  @Test
  void aliasedCteReferenceKeepsTheAlias() {
    String result = expand("""
        WITH t AS (SELECT id FROM crm.public.customer)
        SELECT x.id FROM t AS x""");
    // 展开结果 AS(body, t) 再包一层用户别名 AS(..., x)，分析器逐层剥 AS
    assertTrue(flat(result).contains("FROM (SELECT id FROM crm.public.customer) AS t AS x"),
        () -> result);
  }

  @Test
  void withAsFromItemIsInlinedIntoTheDerivedTable() {
    String result = expand(
        "SELECT q.one FROM (WITH iw AS (SELECT 1 AS one) SELECT one FROM iw) AS q");
    // 展开器把 WITH 节点整体替换为展开后的 body，CTE 引用变普通派生表
    assertTrue(flat(result).contains("FROM (SELECT one FROM (SELECT 1 AS one) AS iw) AS q"),
        () -> result);
  }

  @Test
  void unnestFromItemPassesThrough() {
    String result = expand("SELECT t.col FROM UNNEST(ARRAY[1, 2]) AS t(col)");
    assertTrue(flat(result).toUpperCase().contains("UNNEST"), () -> result);
  }

  @Test
  void selfReferenceFailsAsRecursiveCte() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> expand("WITH r AS (SELECT id FROM r) SELECT id FROM r"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
    assertTrue(e.getMessage().contains("recursive CTE 'r'"), () -> e.getMessage());
  }
}
