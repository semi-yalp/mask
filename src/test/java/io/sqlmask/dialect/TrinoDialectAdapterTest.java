package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoDialectAdapterTest {

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
              - name: seen_at
                type: timestamp(3)
              - name: payload
                type: varbinary
      policies: {}
      """;

  private final TrinoDialectAdapter adapter = new TrinoDialectAdapter();
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "trino-test.yaml", "trino"));
  }

  @Test
  void parsesSelectAndCte() {
    assertEquals(SqlKind.SELECT, adapter.parse("SELECT phone FROM customer", 0).getKind());
    assertEquals(SqlKind.WITH, adapter.parse(
        "WITH a AS (SELECT phone FROM customer) SELECT phone FROM a", 0).getKind());
  }

  @Test
  void rejectsUpdateAndPlainCreateTable() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("UPDATE customer SET phone = 'x'", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("CREATE TABLE t (x int)", 0));
  }

  @Test
  void unquotedIdentifiersFoldToLower() {
    SqlNode parsed = adapter.parse("SELECT PHONE FROM CUSTOMER", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void quotedIdentifiersKeepCase() {
    // "Phone" 不匹配声明的小写列 → 校验失败（Trino 语义）
    SqlNode parsed = adapter.parse("SELECT \"Phone\" FROM customer", 0);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(parsed, schema));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void trinoTypeNamesParse() {
    TableMetadata.Column col = new TrinoTypeResolver().parseColumn("c", "timestamp(3)");
    assertEquals(SqlTypeName.TIMESTAMP, col.sqlTypeName());
    assertEquals(3, col.precision());
    assertEquals(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
        new TrinoTypeResolver().parseColumn("c", "timestamp with time zone").sqlTypeName());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new TrinoTypeResolver().parseColumn("c", "json"));
    assertTrue(e.getMessage().contains("trino"), () -> e.getMessage());
  }

  @Test
  void trinoOnlyFunctionsValidateViaCatchAll() {
    // Calcite 不知道的 Trino 函数走未知函数兜底
    SqlNode parsed = adapter.parse(
        "SELECT date_format(seen_at, '%Y-%m') AS m FROM customer", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("m"), validated.rowType().getFieldNames());
  }

  @Test
  void ctasWithoutTablePropertiesParses() {
    assertEquals(SqlKind.CREATE_TABLE,
        adapter.parse("CREATE TABLE t AS SELECT phone FROM customer", 1).getKind());
  }

  @Test
  void ctasWithTablePropertiesFailsAtParse() {
    // Trino 特有 WITH(...) 表属性超出 Calcite 语法 → 安全失败
    assertThrows(SqlMaskException.class, () -> adapter.parse(
        "CREATE TABLE t WITH (format = 'ORC') AS SELECT phone FROM customer", 1));
  }

  @Test
  void errorMessagesCarryDialectName() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELEC 1", 3));
    assertTrue(e.getMessage().contains("(trino)"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("statement 3"), () -> e.getMessage());
  }
}
