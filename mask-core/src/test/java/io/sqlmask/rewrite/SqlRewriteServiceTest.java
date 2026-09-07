package io.sqlmask.rewrite;

import io.sqlmask.config.LegacyPolicyAdapter;
import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.LineageAnalyzer;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.match.PolicyIndex;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRewriteServiceTest {

  private static final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private static final LineageAnalyzer analyzer = new LineageAnalyzer();
  private static final SqlRewriteService service = new SqlRewriteService();
  private static SchemaPlus schema;
  private static MaskSelector selector;

  @BeforeAll
  static void setUp() {
    LoadedConfig loaded = new YamlConfigLoader().load(Path.of("src/test/resources/metadata/lineage.yaml"));
    schema = YamlCalciteSchemaFactory.create(loaded);
    selector = new PdpMaskSelector(
        new PolicyEngine(PolicyIndex.of(LegacyPolicyAdapter.convert(loaded.config()))),
        Subject.anonymous());
  }

  private ValidatedSql validate(String sql) {
    SqlNode parsed = adapter.parse(sql, 0);
    return adapter.validate(parsed, schema);
  }

  private RewritePlan planOf(ValidatedSql validated) {
    return RewritePlan.of(analyzer.analyze(validated), selector);
  }

  private String rewrite(String sql) {
    ValidatedSql validated = validate(sql);
    return service.rewrite(validated, planOf(validated), adapter);
  }

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void allUnmaskedQueryPassesThrough() {
    String sql = "SELECT c.id, c.name FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    String result = service.rewrite(validated, planOf(validated), adapter);
    assertEquals(flat(validated.originalSql()), flat(result));
    assertFalse(result.contains("mask_"), () -> result);
  }

  @Test
  void wrapsPartialMaskingWithOuterProjection() {
    String sql = "SELECT c.id, c.phone FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM ( "
        + flat(validated.originalSql()) + " ) AS r", out);
  }

  @Test
  void keepsWhereGroupOrderLimitDistinctInside() {
    String sql = """
        SELECT DISTINCT c.status, c.phone
        FROM crm.public.customer c
        WHERE c.status = 'ACTIVE'
        GROUP BY c.status, c.phone
        HAVING count(*) > 1
        ORDER BY c.phone
        LIMIT 10
        """;
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertTrue(out.startsWith("SELECT r.status, mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
    assertTrue(out.endsWith(") AS r"), out);
    // semantic clauses survive verbatim inside the inner query
    assertTrue(out.contains("SELECT DISTINCT c.status, c.phone"), out);
    assertTrue(out.contains("WHERE c.status = 'ACTIVE'"), out);
    assertTrue(out.contains("GROUP BY c.status, c.phone"), out);
    assertTrue(out.contains("HAVING COUNT(*) > 1"), out);
    assertTrue(out.contains("ORDER BY c.phone"), out);
    // Calcite renders LIMIT in the SQL-standard FETCH form, which PostgreSQL accepts
    assertTrue(out.contains("FETCH NEXT 10 ROWS ONLY") || out.contains("LIMIT 10"), out);
    assertEquals(1, out.split("FETCH NEXT 10 ROWS ONLY", -1).length
        + out.split("LIMIT 10", -1).length - 2, () -> out);
    assertEquals(1, out.split("ORDER BY c.phone", -1).length - 1, () -> out);
  }

  /**
   * ORDER BY referencing a column outside the SELECT projection: the converter
   * appends the sort key to the relational output, which used to trip the
   * converted-vs-validated shape guard and reject a legal query. The wrapper
   * must still be produced and the ORDER BY must survive inside it.
   */
  @Test
  void orderByNonProjectedColumnStillWraps() {
    String sql = """
        SELECT c.id, c.phone
        FROM crm.public.customer c
        ORDER BY c.address
        FETCH FIRST 3 ROWS ONLY
        """;
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertTrue(out.startsWith("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
    assertTrue(out.contains("ORDER BY c.address"), out);
    assertTrue(out.endsWith(") AS r"), out);
  }

  @Test
  void keepsJoinInsideWrapper() {
    String sql = """
        SELECT c.phone
        FROM crm.public.customer c
        JOIN crm.public.customer d ON d.id = c.id
        WHERE d.status = 'ACTIVE'
        """;
    String out = flat(rewrite(sql));
    assertTrue(out.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
    assertTrue(out.contains("INNER JOIN crm.public.customer AS d ON d.id = c.id"), out);
    assertTrue(out.contains("WHERE d.status = 'ACTIVE'"), out);
    assertTrue(out.endsWith(") AS r"), out);
  }

  @Test
  void wrapsCteAsOneInnerQuery() {
    String sql = """
        WITH active AS (SELECT phone FROM crm.public.customer WHERE status = 'ACTIVE')
        SELECT phone FROM active
        """;
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( "
        + flat(validated.originalSql()) + " ) AS r", out);
    assertTrue(out.toUpperCase().contains(" WITH ACTIVE AS "), () -> out);
  }

  @Test
  void masksDerivedExpressionWithSingleUdfCall() {
    String sql = "SELECT lower(c.email) AS normalized_email FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertEquals("SELECT mask_email(r.normalized_email) AS normalized_email FROM ( "
        + flat(validated.originalSql()) + " ) AS r", out);
  }

  @Test
  void rendersNumericArguments() {
    String out = flat(rewrite("SELECT phone FROM crm.public.customer"));
    assertTrue(out.contains("mask_phone(r.phone, 3, 4)"), out);
  }

  @Test
  void rewritesOutputOfUnknownEngineFunction() {
    // mask_idcard exists only in the target engine; the output column still
    // gets wrapped because its argument c.phone has a policy
    String sql = "SELECT mask_idcard(c.phone, 'abc') AS v FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    String out = flat(rewrite(sql));
    assertEquals("SELECT mask_phone(r.v, 3, 4) AS v FROM ( "
        + flat(validated.originalSql()) + " ) AS r", out);
  }

  @Test
  void duplicateOutputNamesFailWhenWrapping() {
    String sql = "SELECT c.phone AS value, c.email AS value FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    RewritePlan plan = planOf(validated);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.rewrite(validated, plan, adapter));
    assertEquals(SqlMaskException.Code.REWRITE_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("duplicate column name 'value'"), () -> e.getMessage());
  }

  @Test
  void duplicateOutputNamesPassThroughWithoutWrapper() {
    String sql = "SELECT c.id AS value, c.name AS value FROM crm.public.customer c";
    ValidatedSql validated = validate(sql);
    String out = rewrite(sql);
    assertEquals(flat(validated.originalSql()), flat(out));
  }

  @Test
  void masksOnlyPolicyColumnsInMultiColumnProjection() {
    String sql = "SELECT id, phone, email, status FROM crm.public.customer";
    String out = flat(rewrite(sql));
    assertTrue(out.contains("r.id"), out);
    assertTrue(out.contains("mask_phone(r.phone, 3, 4) AS phone"), out);
    assertTrue(out.contains("mask_email(r.email) AS email"), out);
    assertTrue(out.contains("r.status"), out);
    assertEquals(1, out.split("mask_phone\\(", -1).length - 1, () -> out);
    assertEquals(1, out.split("mask_email\\(", -1).length - 1, () -> out);
  }
}
