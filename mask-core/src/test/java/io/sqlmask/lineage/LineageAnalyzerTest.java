package io.sqlmask.lineage;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageAnalyzerTest {

  private static final Path LINEAGE_YAML = Path.of("src/test/resources/metadata/lineage.yaml");

  private static final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private static final LineageAnalyzer analyzer = new LineageAnalyzer();
  private static SchemaPlus schema;

  @BeforeAll
  static void setUp() {
    LoadedConfig loaded = new YamlConfigLoader().load(LINEAGE_YAML);
    schema = YamlCalciteSchemaFactory.create(loaded);
  }

  private List<OutputLineage> analyze(String sql) {
    SqlNode parsed = adapter.parse(sql, 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    return analyzer.analyze(validated);
  }

  private static ColumnKey key(String column) {
    return ColumnKey.of("crm", "public", "customer", column);
  }

  private static void assertResolvedTo(List<OutputLineage> lineage, int ordinal, String column) {
    OutputLineage output = lineage.get(ordinal);
    assertEquals(LineageStatus.RESOLVED, output.status(), () -> lineage.toString());
    assertEquals(
        java.util.Set.of(ColumnOrigin.of(key(column), "crm.public.customer", false)),
        output.origins(),
        () -> lineage.toString());
  }

  @Test
  void resolvesDirectColumnsWithAlias() {
    List<OutputLineage> lineage = analyze(
        "SELECT c.id, c.phone FROM crm.public.customer c");
    assertEquals(2, lineage.size());
    assertEquals("id", lineage.get(0).outputName());
    assertEquals("phone", lineage.get(1).outputName());
    assertResolvedTo(lineage, 0, "id");
    assertResolvedTo(lineage, 1, "phone");
  }

  @Test
  void resolvesSingleOriginDerivedExpression() {
    List<OutputLineage> lineage = analyze(
        "SELECT lower(c.email) AS normalized_email FROM crm.public.customer c");
    assertEquals(1, lineage.size());
    assertEquals("normalized_email", lineage.get(0).outputName());
    OutputLineage output = lineage.get(0);
    assertEquals(LineageStatus.RESOLVED, output.status());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("email"), "crm.public.customer", true)),
        () -> lineage.toString());
  }

  @Test
  void resolvesMultiOriginDerivedExpression() {
    List<OutputLineage> lineage = analyze(
        "SELECT concat(c.email, '-', c.phone) AS contact FROM crm.public.customer c");
    OutputLineage output = lineage.get(0);
    assertEquals(LineageStatus.RESOLVED, output.status());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("email"), "crm.public.customer", true)),
        () -> lineage.toString());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("phone"), "crm.public.customer", true)),
        () -> lineage.toString());
    assertEquals(2, output.origins().size());
  }

  @Test
  void constantExpressionHasNoOrigin() {
    List<OutputLineage> lineage = analyze(
        "SELECT 1 AS constant_value FROM crm.public.customer c");
    assertEquals(LineageStatus.NO_ORIGIN, lineage.get(0).status());
  }

  @Test
  void resolvesThroughSimpleCte() {
    List<OutputLineage> lineage = analyze(
        "WITH active AS (SELECT phone FROM crm.public.customer) SELECT phone FROM active");
    assertResolvedTo(lineage, 0, "phone");
  }

  @Test
  void resolvesThroughNestedCtes() {
    List<OutputLineage> lineage = analyze(
        "WITH a AS (SELECT phone FROM crm.public.customer), "
            + "b AS (SELECT phone FROM a) SELECT phone FROM b");
    assertResolvedTo(lineage, 0, "phone");
  }

  @Test
  void resolvesThroughCteColumnAliasList() {
    List<OutputLineage> lineage = analyze(
        "WITH masked(phone_number) AS (SELECT phone FROM crm.public.customer) "
            + "SELECT phone_number FROM masked");
    assertEquals("phone_number", lineage.get(0).outputName());
    assertResolvedTo(lineage, 0, "phone");
  }

  @Test
  void resolvesThroughDerivedTableSubquery() {
    List<OutputLineage> lineage = analyze(
        "SELECT inner_q.phone FROM (SELECT phone FROM crm.public.customer) AS inner_q");
    assertResolvedTo(lineage, 0, "phone");
  }

  @Test
  void resolvesAggregateDerivedOrigin() {
    List<OutputLineage> lineage = analyze(
        "SELECT count(phone) AS total FROM crm.public.customer");
    OutputLineage output = lineage.get(0);
    assertEquals(LineageStatus.RESOLVED, output.status());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("phone"), "crm.public.customer", true)),
        () -> lineage.toString());
  }

  @Test
  void cteShadowsTableName() {
    // the CTE 'customer' only exposes email, so the outer phone reference
    // must fail validation instead of silently reading the base table
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> analyze(
        "WITH customer AS (SELECT email FROM crm.public.customer) SELECT phone FROM customer"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void recursiveCteFails() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> analyze(
        "WITH r AS (SELECT phone FROM r) SELECT phone FROM r"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
    assertTrue(e.getMessage().contains("recursive CTE 'r'"), () -> e.getMessage());
  }

  @Test
  void mutallyRecursiveCteFails() {
    // PostgreSQL itself rejects a forward reference to a later CTE; the
    // rewritten statement simply cannot validate
    assertThrows(SqlMaskException.class, () -> analyze(
        "WITH a AS (SELECT phone FROM b), b AS (SELECT phone FROM a) SELECT phone FROM a"));
  }

  @Test
  void unknownLineageFails() {
    // correlated scalar subquery in the output cannot be traced safely
    SqlNode parsed = adapter.parse(
        "SELECT (SELECT c2.phone FROM crm.public.customer c2 WHERE c2.id = c.id) AS p "
            + "FROM crm.public.customer c", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    List<OutputLineage> lineage = analyzer.analyze(validated);
    Optional<LineageStatus> unknown = lineage.stream().map(OutputLineage::status)
        .filter(s -> s == LineageStatus.UNKNOWN).findFirst();
    assertTrue(unknown.isPresent(),
        () -> "expected UNKNOWN lineage, got: " + lineage);
  }

  @Test
  void unknownEngineFunctionOriginTracedToArguments() {
    // engine-defined functions are opaque, but lineage flows through their args
    List<OutputLineage> lineage = analyze(
        "SELECT mask_idcard(c.phone, 'abc') AS v FROM crm.public.customer c");
    OutputLineage output = lineage.get(0);
    assertEquals(LineageStatus.RESOLVED, output.status());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("phone"), "crm.public.customer", true)),
        () -> lineage.toString());
  }

  @Test
  void concatWithLiteralTracesAllArguments() {
    List<OutputLineage> lineage = analyze(
        "SELECT concat(c.address, 'ff') AS x FROM crm.public.customer c");
    OutputLineage output = lineage.get(0);
    assertEquals(LineageStatus.RESOLVED, output.status());
    assertTrue(output.origins().contains(ColumnOrigin.of(key("address"), "crm.public.customer", true)),
        () -> lineage.toString());
    assertEquals(1, output.origins().size(), () -> lineage.toString());
  }

  @Test
  void whereConditionDoesNotAffectOutputLineage() {
    List<OutputLineage> lineage = analyze(
        "SELECT c.id FROM crm.public.customer c WHERE c.status = 'ACTIVE' AND c.phone = 'x'");
    assertResolvedTo(lineage, 0, "id");
  }
}
