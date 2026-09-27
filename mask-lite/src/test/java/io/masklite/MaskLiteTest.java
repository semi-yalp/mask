package io.masklite;

import io.masklite.config.YamlConfigLoader;
import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Facade behavior: masking wrapper, row-filter injection, fail-closed rejections. */
class MaskLiteTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - { name: id, type: bigint }
              - { name: phone, type: varchar(20) }
              - { name: status, type: varchar(10) }
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      columns:
        - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_phone }
      """;

  @Test
  void rewritesWithMaskWrapperAndRowFilterInjection() {
    MaskLite mask = MaskLite.fromYaml(YAML);
    var statements = mask.rewriteStatements("SELECT id, phone FROM customer WHERE id > 10");
    assertEquals(1, statements.size());
    var s = statements.get(0);
    assertEquals(1, s.ordinal());
    assertTrue(s.masked(), "masking wrapper expected");
    assertTrue(s.rowFiltered(), "row filter injection expected");
    assertTrue(s.rewrittenSql().contains("mask_phone(r.phone, 3, 4) AS phone"));
    assertTrue(s.rewrittenSql().contains("WHERE status = 'active'"));
    assertFalse(s.rewrittenSql().equals(s.originalSql()));
  }

  @Test
  void rewriteJoinsIntoScript() {
    MaskLite mask = MaskLite.fromYaml(YAML);
    String script = mask.rewrite("SELECT id FROM customer; SELECT phone FROM customer");
    assertEquals(2, script.chars().filter(c -> c == ';').count());
  }

  @Test
  void rejectsWriteStatementsFailClosed() {
    MaskLite mask = MaskLite.fromYaml(YAML);
    var e = assertThrows(SqlMaskException.class,
        () -> mask.rewriteStatements("INSERT INTO customer SELECT 1, 'x', 'y'"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
    assertTrue(e.getMessage().contains("INSERT"));
  }

  /**
   * schema 限定的 UDF 名（{@code public.mask_phone}）是合法 PG：必须逐段渲染，
   * 不能当单个标识符整体加引号（PG 会把它当字面名找不到函数）。
   */
  @Test
  void rendersSchemaQualifiedUdfNameSegmentWise() {
    MaskLite mask = MaskLite.fromYaml(YAML.replace("udf: mask_phone", "udf: public.mask_phone"));
    String rewritten = mask.rewrite("SELECT phone FROM customer");
    assertTrue(rewritten.contains("public.mask_phone(r.phone, 3, 4)"),
        "segment-wise rendering expected, got: " + rewritten);
  }

  /** 空段/畸形点分名 fail-closed：CONFIG_ERROR，而不是产出坏 SQL。 */
  @Test
  void rejectsMalformedDottedUdfName() {
    for (String bad : new String[] {"mask_phone.", ".mask_phone", "mask..phone"}) {
      var e = assertThrows(SqlMaskException.class,
          () -> MaskLite.fromYaml(YAML.replace("udf: mask_phone", "udf: " + bad))
              .rewrite("SELECT phone FROM customer"),
          "udf name '" + bad + "' must be rejected");
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    }
  }

  /**
   * 用户列恰名 {@code mask_col_1} 且存在待重命名的 EXPR$1 时，生成名必须跳过
   * 撞名位——否则派生表列别名表里出现重复名，PG 直接报 duplicate column。
   */
  @Test
  void generatedColumnNamesSkipUserCollisions() {
    String tricky = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: id, type: bigint }
                - { name: mask_col_1, type: varchar(20) }
                - { name: phone, type: varchar(20) }
        policies:
          mask_all:
            udf: mask_phone
            arguments: [3, 4]
        columns:
          - { catalog: crm, schema: public, table: customer, column: mask_col_1, policy: mask_all }
          - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_all }
        """;
    String rewritten = MaskLite.fromYaml(tricky)
        .rewrite("SELECT mask_col_1, phone || 'x' FROM customer");
    assertTrue(rewritten.contains(") AS r (mask_col_1, mask_col_2)"),
        "the EXPR$1 rename must skip the taken 'mask_col_1', got: " + rewritten);
  }

  @Test
  void rejectsUnsupportedDialect() {
    var e = assertThrows(SqlMaskException.class,
        () -> new YamlConfigLoader().loadContent(YAML, "metadata.yaml", "trino"));
    assertTrue(e.getMessage().contains("mask-lite only supports: postgresql"));
  }

  @Test
  void rejectsRowFilterReferencingUndeclaredColumn() {
    String badYaml = YAML.replace("status = 'active'", "length(id) > 3");
    MaskLite mask = MaskLite.fromYaml(badYaml);
    var e = assertThrows(SqlMaskException.class,
        () -> mask.rewriteStatements("SELECT id FROM customer"));
    assertTrue(e.getMessage().contains("row filter"));
  }
}
