package io.sqlmask.rowfilter;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-filter rewriter: table reference resolution (uniqueness, CTE-less name
 * matching, declared-table boundary) and derived-table injection shape.
 */
class RowFilterRewriterTest {

  private static final String CUSTOMER_FILTER = "status = 'active'";
  private static final String ORDERS_FILTER = "region = 'north'";

  private static final String TWO_TABLE_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
              - {name: region, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
      policies: {}
      """.formatted(CUSTOMER_FILTER, ORDERS_FILTER);

  private static final String MIXED_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
              - {name: region, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
      policies: {}
      """.formatted(CUSTOMER_FILTER);

  private static final String AMBIGUOUS_YAML_TEMPLATE = """
      metadata:
        tables:
          - catalog: %s
            schema: public
            name: customer
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: status, type: varchar}
          - catalog: %s
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
      policies: {}
      """;

  private static final String THREE_TABLE_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: status, type: varchar}
              - {name: region, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            rowFilter: "%s"
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
          - catalog: crm
            schema: public
            name: other
            columns:
              - {name: id, type: bigint}
              - {name: tag, type: varchar}
      policies: {}
      """.formatted(CUSTOMER_FILTER, ORDERS_FILTER);

  private final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private final RowFilterRewriter rewriter = new RowFilterRewriter(adapter);

  private record Fixture(LoadedConfig loaded, SchemaPlus schema, RowFilterRegistry registry) {
  }

  private Fixture fixture(String yaml) {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(yaml, "test.yaml");
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    return new Fixture(loaded, schema,
        RowFilterRegistry.build(loaded, adapter, schema));
  }

  private RowFilterRewriter.Result apply(Fixture fixture, String sql) {
    return apply(fixture, parse(sql));
  }

  private RowFilterRewriter.Result apply(Fixture fixture, SqlNode parsed) {
    return rewriter.apply(parsed, fixture.loaded(), fixture.registry());
  }

  private SqlNode parse(String sql) {
    return adapter.parse(sql, 1);
  }

  /** Calcite renders clause breaks as newlines; assert on normalized shape. */
  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  // --- name resolution and injection shape ---

  @Test
  void injectsIntoThreePartReference() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM crm.public.customer");
    assertEquals(1, result.injections());
    assertEquals(
        flat("SELECT id FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        flat(adapter.unparse(result.node())));
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void injectsIntoUniqueUnqualifiedReference() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM customer");
    assertEquals(1, result.injections());
    // the inner reference keeps the spelling it was written with; a
    // one-part name that got here cannot hit a CTE (scope shadowing is
    // checked first) and stays unique among declared tables
    assertEquals(
        flat("SELECT id FROM (SELECT * FROM customer WHERE status = 'active') AS customer"),
        flat(adapter.unparse(result.node())));
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void ambiguousUnqualifiedNameFailsExplicitly() {
    // first-match catalog resolution would silently bind one candidate — the
    // rewriter must refuse instead of leaving the decision to the validator
    Fixture f = fixture(AMBIGUOUS_YAML_TEMPLATE.formatted("a", CUSTOMER_FILTER, "b"));
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> apply(f, "SELECT id FROM customer"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("a.public.customer"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("b.public.customer"), () -> e.getMessage());
  }

  @Test
  void ambiguityDoesNotDependOnDeclarationOrder() {
    Fixture swapped = fixture(AMBIGUOUS_YAML_TEMPLATE.formatted("b", CUSTOMER_FILTER, "a"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> apply(swapped, "SELECT id FROM customer"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("a.public.customer"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("b.public.customer"), () -> e.getMessage());
  }

  @Test
  void twoPartNameIsLeftToTheValidator() {
    // 'schema.table' does not resolve against declared catalog.schema paths
    // today; the rewriter must not invent matching and must not inject
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT phone FROM public.customer");
    assertEquals(0, result.injections());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(result.node(), f.schema()));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void undeclaredTableIsUntouched() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM nope_table");
    assertEquals(0, result.injections());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(result.node(), f.schema()));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void declaredTableWithoutFilterReturnsSameNode() {
    Fixture f = fixture(MIXED_YAML);
    SqlNode parsed = parse("SELECT id FROM crm.public.orders");
    RowFilterRewriter.Result result = apply(f, parsed);
    assertEquals(0, result.injections());
    assertSame(parsed, result.node(),
        "no filter configured: the parsed tree must be returned as-is");
  }

  // --- alias handling ---

  @Test
  void keepsDeclaredAlias() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result =
        apply(f, "SELECT c.id FROM crm.public.customer AS c WHERE c.status = 'x'");
    assertEquals(1, result.injections());
    assertEquals(
        flat("SELECT c.id FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c"
            + " WHERE c.status = 'x'"),
        flat(adapter.unparse(result.node())));
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void derivesAliasFromTableNameWhenAbsent() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result =
        apply(f, "SELECT customer.id FROM crm.public.customer");
    assertEquals(
        flat("SELECT customer.id FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        flat(adapter.unparse(result.node())));
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void selfJoinInjectsIndependentlyAtBothSites() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, """
        SELECT a.id, customer.id
        FROM crm.public.customer a
        JOIN crm.public.customer ON a.id = customer.id""");
    assertEquals(2, result.injections());
    String rendered = flat(adapter.unparse(result.node()));
    assertTrue(rendered.contains(
            "FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS a"),
        () -> rendered);
    assertTrue(rendered.contains(
            "JOIN (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        () -> rendered);
    assertTrue(rendered.contains("ON a.id = customer.id"), () -> rendered);
    adapter.validate(result.node(), f.schema());
  }

  // --- AST ownership ---

  @Test
  void injectedPredicatesAreIndependentCopies() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, """
        SELECT a.id FROM crm.public.customer a JOIN crm.public.customer b ON a.id = b.id""");
    SqlJoin join = (SqlJoin) ((SqlSelect) result.node()).getFrom();
    assertNotSame(derivedWhere(join.getLeft()), derivedWhere(join.getRight()),
        "both injection sites must own their predicate copy");
    adapter.validate(result.node(), f.schema());
  }

  private SqlNode derivedWhere(SqlNode fromItem) {
    SqlNode derived = ((SqlCall) fromItem).getOperandList().get(0);
    return ((SqlSelect) derived).getWhere();
  }

  @Test
  void injectedDerivedTableIsNotReentered() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM crm.public.customer");
    SqlSelect derived = (SqlSelect) ((SqlCall) ((SqlSelect) result.node()).getFrom())
        .getOperandList().get(0);
    assertInstanceOf(SqlIdentifier.class, derived.getFrom(),
        "the injected derived table's own FROM stays a plain table reference");
  }

  @Test
  void emptyRegistryReturnsSameNode() {
    Fixture f = fixture("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - {name: id, type: bigint}
        policies: {}
        """);
    SqlNode parsed = parse("SELECT id FROM customer JOIN crm.public.orders ON true");
    RowFilterRewriter.Result result = apply(f, parsed);
    assertEquals(0, result.injections());
    assertSame(parsed, result.node());
  }

  // --- CTE scoping ---

  @Test
  void cteNameShadowsFilteredBaseTable() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "WITH customer AS (SELECT id FROM crm.public.other) SELECT id FROM customer");
    assertEquals(0, result.injections(),
        "the CTE binding wins; the base table filter must not be injected");
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void nestedWithShadowsOuterCteName() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "WITH customer AS (SELECT tag FROM crm.public.other) "
            + "SELECT tag FROM (WITH customer AS (SELECT tag FROM crm.public.other) "
            + "SELECT tag FROM customer) AS q");
    assertEquals(0, result.injections(),
        "inner and outer customer references both bind CTEs, never the base table");
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void cteBodyIsFilteredButItsReferencesAreNot() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "WITH c AS (SELECT * FROM crm.public.customer) SELECT id FROM c");
    assertEquals(1, result.injections());
    String rendered = flat(adapter.unparse(result.node()));
    assertTrue(rendered.contains(
            "WITH c AS (SELECT * FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer)"),
        () -> rendered);
    assertTrue(rendered.contains("SELECT id FROM c"), () -> rendered);
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void forwardCteReferenceIsNotReinterpretedAsBaseTable() {
    // a's body references a later CTE name that is also a declared filtered
    // table. PostgreSQL would reject the forward reference; Calcite's scope
    // (like the ambiguity deviation documented for E4) accepts it and binds
    // the CTE. The rewriter matches the validator: the name is never treated
    // as a base-table hit, and the CTE body itself gets the filter injected,
    // so no unfiltered rows can leak through the forward reference.
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "WITH a AS (SELECT id FROM customer), customer AS (SELECT id FROM crm.public.other) "
            + "SELECT * FROM a");
    assertEquals(0, result.injections(),
        "a forward CTE reference is never a base-table injection site");
    adapter.validate(result.node(), f.schema());

    RowFilterRewriter.Result filtered = apply(f,
        "WITH a AS (SELECT id FROM customer), customer AS (SELECT id FROM crm.public.customer) "
            + "SELECT * FROM a");
    assertEquals(1, filtered.injections(),
        "the forward-referenced CTE's own body is filtered");
    adapter.validate(filtered.node(), f.schema());
  }

  // --- identifier casing ---

  @Test
  void quotedCaseSensitiveNameDoesNotMatchLowercaseDeclaration() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM \"Customer\"");
    assertEquals(0, result.injections(),
        "\"Customer\" is a different table from customer; it stays unfiltered "
            + "and validation rejects it as undeclared");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(result.node(), f.schema()));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void quotedLowercaseNameMatchesDeclaration() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM \"customer\"");
    assertEquals(1, result.injections());
  }

  @Test
  void unquotedFoldsToLowercaseBeforeMatching() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM Customer");
    assertEquals(1, result.injections(),
        "unquoted Customer folds to customer and hits the filtered table");
  }

  // --- alias column lists and join shapes ---

  @Test
  void keepsColumnAliasList() {
    // Calcite requires the alias list to cover every column (unlike
    // PostgreSQL, which allows a prefix); four columns, four aliases
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result =
        apply(f, "SELECT a FROM crm.public.customer AS c(a, b, c2, d)");
    assertEquals(1, result.injections());
    String rendered = flat(adapter.unparse(result.node()));
    assertTrue(rendered.contains("AS c (a, b, c2, d)"), () -> rendered);
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void injectsOnBothSidesOfCommaJoin() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT c.id FROM crm.public.customer c, crm.public.orders o WHERE c.id = o.id");
    assertEquals(2, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void injectsIntoNestedSetOperationBranches() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT id FROM (SELECT id FROM crm.public.customer UNION ALL "
            + "SELECT id FROM crm.public.customer) t");
    assertEquals(2, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  // --- expression subqueries ---

  @Test
  void injectsIntoWhereExistsSubquery() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT o.id FROM crm.public.other o "
            + "WHERE EXISTS (SELECT 1 FROM crm.public.customer c WHERE c.id = o.id)");
    assertEquals(1, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void injectsIntoScalarSubqueryInSelectList() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT (SELECT max(id) FROM crm.public.customer) AS m FROM crm.public.other");
    assertEquals(1, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void injectsIntoJoinConditionSubquery() {
    Fixture f = fixture(THREE_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT a.id FROM crm.public.other a JOIN crm.public.other b "
            + "ON EXISTS (SELECT 1 FROM crm.public.customer c WHERE c.id = a.id)");
    assertEquals(1, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  // --- top-level ORDER BY / LIMIT wrapper ---

  @Test
  void orderAndLimitOnFilteredTableRendersOnce() {
    // a top-level ORDER BY/LIMIT wraps the query in a SqlOrderBy node whose
    // operator cannot rebuild it through the generic createCall path — the
    // rewritten wrapper must stay a real SqlOrderBy so unparse keeps working
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result =
        apply(f, "SELECT id FROM crm.public.customer ORDER BY id LIMIT 5");
    assertEquals(1, result.injections());
    String rendered = flat(adapter.unparse(result.node()));
    assertTrue(rendered.contains(
            "(SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        () -> rendered);
    assertEquals(1, countOccurrences(rendered, "ORDER BY id"), () -> rendered);
    assertEquals(1, countOccurrences(rendered, "FETCH NEXT 5 ROWS ONLY"), () -> rendered);
    adapter.validate(result.node(), f.schema());
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  // --- fail-closed boundaries ---

  @Test
  void rejectsFullyQualifiedColumnReferenceOfFilteredTable() {
    // after injection the derived table only exposes the alias, so a
    // three-part column reference would break binding; refuse instead
    Fixture f = fixture(TWO_TABLE_YAML);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> apply(f,
        "SELECT crm.public.customer.id FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode(), () -> e.getMessage());
  }

  @Test
  void rejectsTablesampleOnFilteredTable() {
    Fixture f = fixture(TWO_TABLE_YAML);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> apply(f,
        "SELECT id FROM crm.public.customer TABLESAMPLE SYSTEM (10)"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode(), () -> e.getMessage());
  }

  @Test
  void allowsUnnestWithoutFilteredTable() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f,
        "SELECT u FROM UNNEST(ARRAY['a', 'b']) AS u");
    assertEquals(0, result.injections());
  }

  @Test
  void dmlAndDdlNodesAreNeverTouched() {
    // the engine only feeds source queries to the rewriter; applying to a
    // full write statement must be a no-op even when the target table is
    // itself a declared filtered table
    Fixture f = fixture(THREE_TABLE_YAML);
    for (String sql : new String[] {
        "INSERT INTO customer SELECT id FROM crm.public.other",
        "CREATE TABLE customer AS SELECT id FROM crm.public.other"}) {
      SqlNode parsed = parse(sql);
      RowFilterRewriter.Result result = apply(f, parsed);
      assertEquals(0, result.injections(), () -> sql);
      assertSame(parsed, result.node(), () -> sql);
    }
  }
}
