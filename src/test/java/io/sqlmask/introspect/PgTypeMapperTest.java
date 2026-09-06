package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PgTypeMapperTest {

  private final PgTypeMapper mapper = new PgTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      // spec 映射表：精确行
      "'boolean', boolean",
      "'smallint', smallint",
      "'integer', integer",
      "'bigint', bigint",
      "'real', real",
      "'double precision', 'double precision'",
      "'numeric(10,2)', 'numeric(10,2)'",
      "'numeric', numeric",
      "'numeric(5)', 'numeric(5)'",
      "'character(20)', 'char(20)'",
      "'character', 'char(1)'",
      "'character varying(50)', 'varchar(50)'",
      "'character varying', varchar",
      "'date', date",
      "'timestamp(3) without time zone', 'timestamp(3)'",
      "'timestamp without time zone', timestamp",
      "'timestamp(3) with time zone', 'timestamptz(3)'",
      "'timestamp with time zone', timestamptz",
      "'time(6) without time zone', 'time(6)'",
      "'time without time zone', time",
      "'time(6) with time zone', 'timetz(6)'",
      "'time with time zone', timetz"
  })
  void mapsExactly(String pgType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(pgType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), pgType);
  }

  @Test
  void arrayTypesDegrade() {
    PgTypeMapper.Mapped m = mapper.map("integer[]");
    assertEquals("varchar", m.yamlType());
    assertTrue(m.degraded());
  }

  @Test
  void scalarUnsupportedTypesDegrade() {
    for (String t : new String[]{"jsonb", "json", "uuid", "bytea", "money", "inet",
        "xml", "tsvector", "interval", "hstore", "point"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @Test
  void nullAndBlankDegrade() {
    assertTrue(mapper.map(null).degraded());
    assertEquals("varchar", mapper.map("").yamlType());
  }
}
