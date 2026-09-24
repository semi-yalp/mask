package io.sqlmask.introspect;

import io.sqlmask.dialect.DialectProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoTypeMapperTest {

  private final TrinoTypeMapper mapper = new TrinoTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "'boolean', boolean",
      "'tinyint', tinyint",
      "'smallint', smallint",
      "'integer', integer",
      "'int', int",
      "'bigint', bigint",
      "'real', real",
      "'double', double",
      "'decimal(10,2)', 'decimal(10,2)'",
      "'decimal', decimal",
      "'char(10)', 'char(10)'",
      "'character(10)', 'character(10)'",
      "'char', 'char(1)'",
      "'varchar(50)', 'varchar(50)'",
      "'varchar', varchar",
      "'character varying(50)', 'character varying(50)'",
      "'varbinary', varbinary",
      "'date', date",
      "'time(3)', 'time(3)'",
      "'time(3) with time zone', 'time(3) with time zone'",
      "'timestamp(3)', 'timestamp(3)'",
      "'timestamp(3) with time zone', 'timestamp(3) with time zone'"
  })
  void mapsExactly(String dataType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(dataType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), dataType);
  }

  @Test
  void complexAndUnknownTypesDegrade() {
    for (String t : new String[]{"json", "ipaddress", "hyperloglog", "P4HyperLogLog",
        "qdigest(x)", "array(integer)", "map(varchar,integer)", "row(a integer)",
        "array(x)", "weird"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @ParameterizedTest(name = "{0} -> varchar degraded")
  @CsvSource({
      "'decimal(99999999999)'",
      "'decimal(x)'",
      "'char ()'"
  })
  void malformedParamsDegrade(String dataType) {
    PgTypeMapper.Mapped m = mapper.map(dataType);
    assertEquals("varchar", m.yamlType(), dataType);
    assertTrue(m.degraded(), dataType);
  }

  @Test
  void neverThrows() {
    for (String t : new String[]{null, "", "decimal(99999999999)", "row(", "  "}) {
      assertDoesNotThrow(() -> mapper.map(t));
    }
    assertEquals("varchar", mapper.map(null).yamlType());
  }

  /** 硬防线：每个精确映射结果必须通过 TrinoTypeResolver。 */
  @Test
  void everyMappedTypePassesTrinoResolver() {
    String[] samples = {"boolean", "tinyint", "smallint", "integer", "int", "bigint", "real",
        "double", "decimal", "decimal(10,2)", "char(1)", "char(10)", "character(10)",
        "varchar(50)", "varchar", "character varying(50)", "varbinary",
        "date", "time(3)", "time(3) with time zone", "timestamp(3)",
        "timestamp(3) with time zone"};
    for (String yamlType : samples) {
      assertDoesNotThrow(() -> DialectProfiles.byName("trino").typeResolver()
          .parseColumn("t", yamlType), yamlType);
    }
  }
}
