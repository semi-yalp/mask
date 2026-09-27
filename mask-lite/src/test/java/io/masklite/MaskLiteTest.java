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
