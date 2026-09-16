package io.sqlmask.sql;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lexical-scope and ownership guarantees of CTE expansion: inner WITH clauses
 * shadow outer same-name CTEs, redefining a name inside an outer CTE body is
 * shadowing rather than recursion, and no two reference sites share mutable
 * AST nodes.
 */
class CteExpanderTest {

  private static final Path SCOPE_YAML = Path.of("src/test/resources/metadata/cte-scope.yaml");

  private static final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private static SchemaPlus schema;

  @BeforeAll
  static void setUp() {
    LoadedConfig loaded = new YamlConfigLoader().load(SCOPE_YAML);
    schema = YamlCalciteSchemaFactory.create(loaded);
  }

  @Test
  void innerCteShadowsOuterSameName() {
    // the inner WITH x shadows the outer x for references inside it: the outer
    // body exposes only phone, the inner one provides tag, so the query must
    // validate instead of failing with "Column 'tag' not found"
    SqlNode parsed = adapter.parse(
        "WITH x AS (SELECT phone FROM crm.public.customer) "
            + "SELECT tag FROM (WITH x AS (SELECT 't' AS tag) SELECT tag FROM x) AS q", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void innerWithRedefinitionInsideOuterCteBodyIsNotRecursive() {
    // defining an inner CTE named like the outer one inside the outer body is
    // shadowing, not a cycle: the inner body references the inner x (base
    // table), the outer body references the inner x, the main query the outer
    // one — it must expand and validate instead of failing with a
    // recursive-CTE diagnostic
    SqlNode parsed = adapter.parse(
        "WITH x AS (WITH x AS (SELECT phone FROM crm.public.customer) SELECT phone FROM x) "
            + "SELECT phone FROM x", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void cteBodyNotSharedAcrossReferences() {
    // each reference site must own an independent copy of the expanded body:
    // the validator mutates the analysis tree in place, so a shared subtree
    // would leak changes between the self-join sides
    SqlNode parsed = adapter.parse(
        "WITH c AS (SELECT id, phone FROM crm.public.customer) "
            + "SELECT a.phone FROM c a JOIN c b ON a.id = b.id", 0);
    adapter.validate(parsed, schema);
    SqlNode expanded = new CteExpander().expand(parsed);
    SqlJoin join = (SqlJoin) ((SqlSelect) expanded).getFrom();
    assertNotSame(derivedBody(join.getLeft()), derivedBody(join.getRight()));
  }

  @Test
  void orderBySubqueryOverCteIsExpandedInsideTheWithScope() {
    // Calcite places ORDER BY above the WITH in the parse tree; the order
    // list must still expand inside the WITH scope (regression: it used to
    // expand after the scope was popped, leaving the bare `t` reference in
    // the analysis tree and failing lineage with an unknown table)
    SqlNode parsed = adapter.parse(
        "WITH t AS (SELECT id AS v FROM crm.public.customer) "
            + "SELECT phone FROM crm.public.customer ORDER BY (SELECT max(v) FROM t)", 0);
    SqlNode expanded = new CteExpander().expand(parsed);
    String flat = adapter.unparse(expanded).replaceAll("\\s+", " ");
    assertTrue(flat.contains("ORDER BY (SELECT MAX(v) FROM (SELECT id AS v"
            + " FROM crm.public.customer) AS t)"),
        () -> flat);
    adapter.validate(expanded, schema);
  }

  /** Unwraps the derived-table AS wrapper down to the expanded CTE body. */
  private static SqlNode derivedBody(SqlNode fromItem) {
    SqlNode derived = ((SqlCall) fromItem).getOperandList().get(0);
    return ((SqlCall) derived).getOperandList().get(0);
  }
}
