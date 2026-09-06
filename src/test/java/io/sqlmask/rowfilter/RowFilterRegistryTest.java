package io.sqlmask.rowfilter;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-filter condition registry: parse/validation failures surface as
 * CONFIG_ERROR with the declaring table in the message, subqueries and
 * non-deterministic/session constructs are rejected outright (the validator
 * alone is too permissive), and the cached template is never the node the
 * validator mutated.
 */
class RowFilterRegistryTest {

  private static final String YAML_TEMPLATE = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: %s
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
              - {name: region, type: varchar}
              - {name: expires_at, type: timestamp}
          - catalog: crm
            schema: public
            name: orders
            rowFilter: %s
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
      policies: {}
      """;

  private final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();

  private RowFilterRegistry build(String customerFilter, String ordersFilter) {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(
        YAML_TEMPLATE.formatted(customerFilter, ordersFilter), "test.yaml");
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    return RowFilterRegistry.build(loaded, adapter, schema);
  }

  private static SqlMaskException assertConfigError(Runnable action) {
    return assertConfigError(action, () -> "action");
  }

  private static SqlMaskException assertConfigError(Runnable action,
      java.util.function.Supplier<String> what) {
    SqlMaskException e = assertThrows(SqlMaskException.class, action::run, what);
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode(), () -> e.getMessage());
    return e;
  }

  @Test
  void buildsFromValidCondition() {
    RowFilterRegistry registry = build("'status = ''active'''", "");
    assertFalse(registry.isEmpty());
    assertNotNull(registry.conditionTemplateOf("crm", "public", "customer"));
  }

  @Test
  void emptyWhenNoTableDeclaresFilter() {
    RowFilterRegistry registry = build("", "");
    assertTrue(registry.isEmpty());
  }

  @Test
  void rejectsParseFailure() {
    SqlMaskException e = assertConfigError(() -> build("'status ='", ""));
    assertTrue(e.getMessage().contains("table 'crm.public.customer': row filter"),
        () -> e.getMessage());
  }

  @Test
  void rejectsSubqueriesInAllShapes() {
    for (String condition : new String[] {
        "id IN (SELECT id FROM crm.public.orders)",
        "EXISTS (SELECT 1 FROM crm.public.orders)",
        "status = (SELECT 'x')"}) {
      SqlMaskException e = assertConfigError(() -> build("'" + condition.replace("'", "''") + "'", ""),
          () -> condition);
      assertTrue(e.getMessage().contains("table 'crm.public.customer': row filter"),
          () -> condition + ": " + e.getMessage());
    }
  }

  @Test
  void rejectsUnknownColumn() {
    SqlMaskException e = assertConfigError(() -> build("'nope = 1'", ""));
    assertTrue(e.getMessage().contains("table 'crm.public.customer': row filter"),
        () -> e.getMessage());
  }

  @Test
  void validationFailureNamesTheTableOnce() {
    // the message prefix already carries the declaring table, so the inner
    // diagnostic must not repeat it
    SqlMaskException e = assertConfigError(() -> build("'1 + 1'", ""));
    assertTrue(e.getMessage().contains("row filter is not a valid condition:"),
        () -> e.getMessage());
    assertFalse(e.getMessage().contains("for table"), () -> e.getMessage());
  }

  @Test
  void rejectsNonBooleanCondition() {
    assertConfigError(() -> build("'1 + 1'", ""));
  }

  @Test
  void acceptsCalciteImplicitCoercion() {
    // Calcite's validator coerces varchar = 123 — the same semantics user
    // queries get; strict PostgreSQL typing is not enforced here
    RowFilterRegistry registry = build("'status = 123'", "");
    assertNotNull(registry.conditionTemplateOf("crm", "public", "customer"));
  }

  @Test
  void acceptsIsDistinctFrom() {
    RowFilterRegistry registry = build("'status IS NOT DISTINCT FROM ''active'''", "");
    assertNotNull(registry.conditionTemplateOf("crm", "public", "customer"));
  }

  @Test
  void rejectsNonDeterministicAndSessionConstructs() {
    // validator would happily accept these (UnknownFunctionTable resolves
    // unknown names as opaque UDFs; CURRENT_USER/CURRENT_TIMESTAMP/RAND are
    // known operators) — the AST whitelist is the line of defense
    for (String condition : new String[] {
        "current_user = 'a'",
        "is_allowed(status)",
        "random() < 0.5",
        "current_timestamp < expires_at"}) {
      SqlMaskException e = assertConfigError(() -> build("'" + condition.replace("'", "''") + "'", ""),
          () -> condition);
      assertTrue(e.getMessage().contains("table 'crm.public.customer': row filter"),
          () -> condition + ": " + e.getMessage());
    }
  }

  @Test
  void rejectsDynamicParameterVariants() {
    // a filter carrying ? / $1 would only bind at execution time — it can
    // never be a static, per-table condition. `?` surfaces as a dynamic
    // parameter node and gets its dedicated rejection; `$1` lexes as a plain
    // identifier and is rejected as an undeclared column — both CONFIG_ERROR
    SqlMaskException questionMark = assertConfigError(() -> build("'status = ?'", ""));
    assertTrue(questionMark.getMessage().contains("must not contain dynamic parameters"),
        () -> questionMark.getMessage());
    SqlMaskException dollar = assertConfigError(() -> build("'status = $1'", ""));
    assertTrue(dollar.getMessage().contains("table 'crm.public.customer': row filter"),
        () -> dollar.getMessage());
  }

  @Test
  void reportsTheOffendingTable() {
    SqlMaskException e = assertConfigError(() -> build("'status ='", "'region ='"));
    assertTrue(e.getMessage().contains("crm.public.customer"), () -> e.getMessage());
    assertFalse(e.getMessage().contains("crm.public.orders"), () -> e.getMessage());
  }

  @Test
  void templateIsStableAcrossBuildsAndRepeatedlyValidatable() {
    RowFilterRegistry first = build("'status = ''active'''", "");
    RowFilterRegistry second = build("'status = ''active'''", "");
    String rendered = render(first.conditionTemplateOf("crm", "public", "customer").orElseThrow());
    assertEquals(rendered,
        render(second.conditionTemplateOf("crm", "public", "customer").orElseThrow()));
    // fresh copies of the template must validate repeatedly — a template that
    // was consumed (validator-mutated) during build would misbehave here
    for (int i = 0; i < 2; i++) {
      adapter.validate(
          adapter.parse("SELECT 1 FROM crm.public.customer WHERE " + rendered, 0),
          schemaOf("'status = ''active'''", ""));
    }
  }

  private String render(org.apache.calcite.sql.SqlNode node) {
    return node.toSqlString(c -> c
        .withDialect(org.apache.calcite.sql.dialect.PostgresqlSqlDialect.DEFAULT)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withIndentation(0)).getSql();
  }

  private SchemaPlus schemaOf(String customerFilter, String ordersFilter) {
    return YamlCalciteSchemaFactory.create(new YamlConfigLoader().loadContent(
        YAML_TEMPLATE.formatted(customerFilter, ordersFilter), "test.yaml"));
  }
}
