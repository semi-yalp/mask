package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresqlDialectAdapterTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
              - name: email
                type: varchar
              - name: status
                type: varchar
      policies: {}
      """;

  private final PostgresqlDialectAdapter adapter = new PostgresqlDialectAdapter();
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    schema = YamlCalciteSchemaFactory.create(new YamlConfigLoader().loadContent(YAML, "dialect-test.yaml"));
  }

  @Test
  void parsesSelectAsSelectKind() {
    SqlNode node = adapter.parse("SELECT phone FROM customer", 0);
    assertEquals(SqlKind.SELECT, node.getKind());
  }

  @Test
  void parsesCteQueryAsWithKind() {
    SqlNode node = adapter.parse(
        "WITH active AS (SELECT phone FROM customer) SELECT phone FROM active", 0);
    assertEquals(SqlKind.WITH, node.getKind());
  }

  @Test
  void acceptsInsertStatement() {
    SqlNode node = adapter.parse("INSERT INTO crm.public.customer SELECT 1", 2);
    assertEquals(SqlKind.INSERT, node.getKind());
  }

  @Test
  void acceptsCreateTableAsSelect() {
    SqlNode node = adapter.parse("CREATE TABLE t AS SELECT phone FROM customer", 1);
    assertEquals(SqlKind.CREATE_TABLE, node.getKind());
  }

  @Test
  void rejectsPlainCreateTable() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("CREATE TABLE t (x int, y varchar)", 0));
  }

  @Test
  void rejectsUpdate() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.parse("UPDATE customer SET phone = 'x'", 0));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void rejectsDelete() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("DELETE FROM customer WHERE id = 1", 0));
  }

  @Test
  void validatesSelectAgainstYamlSchema() {
    SqlNode parsed = adapter.parse("SELECT c.id, c.phone FROM crm.public.customer AS c", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("id", "phone"), validated.rowType().getFieldNames());
  }

  @Test
  void resolvesUnqualifiedTableViaSchemaPath() {
    SqlNode parsed = adapter.parse("SELECT phone FROM customer", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals("phone", validated.rowType().getFieldNames().get(0));
  }

  @Test
  void rejectsUnknownColumn() {
    SqlNode parsed = adapter.parse("SELECT nope FROM customer", 0);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(parsed, schema));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("nope"), () -> e.getMessage());
  }

  @Test
  void rejectsUnknownTable() {
    SqlNode parsed = adapter.parse("SELECT phone FROM other", 0);
    assertThrows(SqlMaskException.class, () -> adapter.validate(parsed, schema));
  }

  @Test
  void validatesLimitAndOrder() {
    SqlNode parsed = adapter.parse(
        "SELECT phone FROM customer WHERE status = 'ACTIVE' ORDER BY phone LIMIT 10", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void validatesUnknownEngineFunctions() {
    // functions that only the target engine knows must not block validation
    SqlNode parsed = adapter.parse(
        "SELECT mask_idcard(c.phone, 'abc') AS v FROM crm.public.customer c", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("v"), validated.rowType().getFieldNames());
  }

  @Test
  void validatesUnknownZeroArgFunction() {
    SqlNode parsed = adapter.parse(
        "SELECT session_tag() AS t FROM crm.public.customer c", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("t"), validated.rowType().getFieldNames());
  }

  @Test
  void validatesUnknownFunctionInsideBuiltin() {
    // known aggregate over an unknown function argument
    SqlNode parsed = adapter.parse(
        "SELECT count(mask_idcard(c.phone, 'x')) AS t FROM crm.public.customer c", 0);
    ValidatedSql validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("t"), validated.rowType().getFieldNames());
  }

  @Test
  void unparsePreservesSqlText() {
    SqlNode parsed = adapter.parse(
        "WITH active AS (SELECT phone FROM customer WHERE status = 'ACTIVE') "
            + "SELECT phone FROM active", 0);
    String sql = adapter.unparse(parsed);
    assertTrue(sql.toUpperCase().contains("WITH ACTIVE AS"), () -> sql);
    assertTrue(sql.toUpperCase().contains("SELECT"), () -> sql);
  }
}
