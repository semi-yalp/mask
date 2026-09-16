package io.sqlmask.rowfilter;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Query-position shapes the base test leaves uncovered: top-level set
 * operations, ORDER BY wrappers over unfiltered queries, unchanged joins and
 * fail-closed walks over uncontrolled tables.
 */
class RowFilterRewriterQueryPositionTest {

  private static final String MIXED_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
      policies: {}
      """;

  private final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private final RowFilterRewriter rewriter = new RowFilterRewriter(adapter);

  private record Fixture(LoadedConfig loaded, SchemaPlus schema, RowFilterRegistry registry) {
  }

  private Fixture fixture() {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(MIXED_YAML, "test.yaml");
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    return new Fixture(loaded, schema, RowFilterRegistry.build(loaded, adapter, schema));
  }

  private RowFilterRewriter.Result apply(Fixture f, String sql) {
    return rewriter.apply(adapter.parse(sql, 1), f.loaded(), f.registry());
  }

  @Test
  void queryPositionSetOperationInjectsIntoEveryBranch() throws Exception {
    Fixture f = fixture();
    // The dialect adapter's own parse rejects top-level set operations, but
    // the rewriter is also fed source queries of write statements, where a
    // UNION reaches query position — exercise it with the dialect's parser
    // config but without the adapter's statement classifier.
    String sql = "SELECT id FROM crm.public.customer UNION SELECT id FROM crm.public.customer";
    SqlNode parsed = org.apache.calcite.sql.parser.SqlParser.create(sql,
        io.sqlmask.dialect.DialectProfiles.byName("postgresql").parserConfig()).parseQuery();
    RowFilterRewriter.Result result = rewriter.apply(parsed, f.loaded(), f.registry());
    assertEquals(2, result.injections());
    String unparsed = adapter.unparse(result.node());
    assertEquals(3, unparsed.replaceAll("\\s+", " ").split("status = 'active'", -1).length,
        () -> unparsed);
  }

  @Test
  void orderByOverUnfilteredQueryKeepsNodeIdentity() {
    Fixture f = fixture();
    SqlNode parsed = adapter.parse("SELECT id FROM crm.public.orders ORDER BY id", 1);
    RowFilterRewriter.Result result = rewriter.apply(parsed, f.loaded(), f.registry());
    assertEquals(0, result.injections());
    assertSame(parsed, result.node(), "nothing to inject: the ORDER BY wrapper is returned as-is");
  }

  @Test
  void joinBetweenUnfilteredTablesKeepsNodeIdentity() {
    Fixture f = fixture();
    SqlNode parsed = adapter.parse(
        "SELECT a.id FROM crm.public.orders a JOIN crm.public.orders b ON a.id = b.id", 1);
    RowFilterRewriter.Result result = rewriter.apply(parsed, f.loaded(), f.registry());
    assertEquals(0, result.injections());
    assertSame(parsed, result.node(), "both join sides unfiltered: the join is returned as-is");
  }

  @Test
  void unknownFromShapeOverUncontrolledTablePassesThrough() {
    Fixture f = fixture();
    // UNNEST is outside the FROM whitelist; it mentions no controlled table,
    // so the fail-closed walk must let it through untouched
    RowFilterRewriter.Result result = apply(f,
        "SELECT t.col FROM UNNEST(ARRAY[1, 2]) AS t(col)");
    assertEquals(0, result.injections());
  }
}
