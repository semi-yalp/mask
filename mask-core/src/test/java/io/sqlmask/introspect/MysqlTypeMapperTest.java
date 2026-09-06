package io.sqlmask.introspect;

import io.sqlmask.dialect.DialectProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlTypeMapperTest {

  private final MysqlTypeMapper mapper = new MysqlTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "'tinyint(1)', 'tinyint(1)'",
      "'smallint(5)', 'smallint(5)'",
      "'mediumint', mediumint",
      "'int(11)', 'int(11)'",
      "'int', int",
      "'bigint', bigint",
      "'decimal(10,2)', 'decimal(10,2)'",
      "'decimal(8)', 'decimal(8)'",
      "'float', float",
      "'double', double",
      "'char(20)', 'char(20)'",
      "'char', 'char(1)'",
      "'varchar(50)', 'varchar(50)'",
      "'varchar', varchar",
      "'tinytext', tinytext",
      "'text', text",
      "'mediumtext', mediumtext",
      "'longtext', longtext",
      "'binary(16)', 'binary(16)'",
      "'varbinary(255)', 'varbinary(255)'",
      "'date', date",
      "'datetime(3)', 'datetime(3)'",
      "'datetime', datetime",
      "'time(3)', 'time(3)'",
      "'timestamp(6)', 'timestamp(6)'",
      "'timestamp', timestamp"
  })
  void mapsExactly(String columnType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(columnType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), columnType);
  }

  @ParameterizedTest(name = "{0} -> {1} degraded")
  @CsvSource({
      "'int unsigned', int",
      "'bigint unsigned', bigint",
      "'tinyint(1) unsigned', 'tinyint(1)'"
  })
  void stripsUnsignedWithDegradedFlag(String columnType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(columnType);
    assertEquals(expected, m.yamlType());
    assertTrue(m.degraded(), columnType);
  }

  @Test
  void unsupportedTypesDegrade() {
    for (String t : new String[]{"json", "enum('a','b')", "set('x','y')", "bit(8)", "bit",
        "year(4)", "year", "geometry", "blob", "longblob", "point", "int signed"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @Test
  void neverThrows() {
    for (String t : new String[]{null, "", "  ", "numeric(a,b)", "varchar(99999999999)",
        "enum('a','b')", "wèírd"}) {
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> mapper.map(t));
    }
    assertEquals("varchar", mapper.map(null).yamlType());
  }

  /** 硬防线：每个精确映射结果必须通过 MysqlTypeResolver。 */
  @Test
  void everyMappedTypePassesMysqlResolver() {
    String[] samples = {"tinyint(1)", "smallint", "mediumint", "int", "bigint",
        "decimal(10,2)", "float", "double", "char(20)", "char", "varchar(50)", "varchar",
        "text", "longtext", "binary(16)", "varbinary(255)", "date", "datetime(3)",
        "time(3)", "timestamp"};
    for (String yamlType : samples) {
      assertDoesNotThrow(() -> DialectProfiles.byName("mysql").typeResolver()
          .parseColumn("t", yamlType), yamlType);
    }
  }
}
