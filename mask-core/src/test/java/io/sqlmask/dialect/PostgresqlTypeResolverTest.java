package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full PostgreSQL type vocabulary: aliases, precision/scale and rejections. */
class PostgresqlTypeResolverTest {

  private final PostgresqlTypeResolver resolver = new PostgresqlTypeResolver();

  private static void assertParsed(PostgresqlTypeResolver resolver, String declaration,
      SqlTypeName expected, Integer precision, Integer scale) {
    TableMetadata.Column column = resolver.parseColumn("c", declaration);
    assertEquals(expected, column.sqlTypeName(), declaration);
    assertEquals(precision, column.precision(), declaration);
    assertEquals(scale, column.scale(), declaration);
    assertEquals(declaration, column.typeDeclaration(), "declaration echoes verbatim");
  }

  @Test
  void numericFamilyAliasesParse() {
    assertParsed(resolver, "boolean", SqlTypeName.BOOLEAN, null, null);
    assertParsed(resolver, "bool", SqlTypeName.BOOLEAN, null, null);
    assertParsed(resolver, "smallint", SqlTypeName.SMALLINT, null, null);
    assertParsed(resolver, "int2", SqlTypeName.SMALLINT, null, null);
    assertParsed(resolver, "integer", SqlTypeName.INTEGER, null, null);
    assertParsed(resolver, "int", SqlTypeName.INTEGER, null, null);
    assertParsed(resolver, "int4", SqlTypeName.INTEGER, null, null);
    assertParsed(resolver, "bigint", SqlTypeName.BIGINT, null, null);
    assertParsed(resolver, "int8", SqlTypeName.BIGINT, null, null);
    assertParsed(resolver, "real", SqlTypeName.REAL, null, null);
    assertParsed(resolver, "float4", SqlTypeName.REAL, null, null);
    assertParsed(resolver, "double precision", SqlTypeName.DOUBLE, null, null);
    assertParsed(resolver, "float8", SqlTypeName.DOUBLE, null, null);
    assertParsed(resolver, "float", SqlTypeName.DOUBLE, null, null);
  }

  @Test
  void decimalCarriesPrecisionAndScale() {
    assertParsed(resolver, "decimal(10,2)", SqlTypeName.DECIMAL, 10, 2);
    assertParsed(resolver, "numeric(8)", SqlTypeName.DECIMAL, 8, null);
    assertParsed(resolver, "decimal", SqlTypeName.DECIMAL, null, null);
  }

  @Test
  void characterFamilyDefaultsAndPrecision() {
    assertParsed(resolver, "char", SqlTypeName.CHAR, 1, null);
    assertParsed(resolver, "character", SqlTypeName.CHAR, 1, null);
    assertParsed(resolver, "char(3)", SqlTypeName.CHAR, 3, null);
    assertParsed(resolver, "varchar(20)", SqlTypeName.VARCHAR, 20, null);
    assertParsed(resolver, "character varying(30)", SqlTypeName.VARCHAR, 30, null);
    assertParsed(resolver, "text", SqlTypeName.VARCHAR, null, null);
  }

  @Test
  void temporalTypesParseWithOptionalPrecision() {
    assertParsed(resolver, "date", SqlTypeName.DATE, null, null);
    assertParsed(resolver, "timestamp", SqlTypeName.TIMESTAMP, null, null);
    assertParsed(resolver, "timestamp(6)", SqlTypeName.TIMESTAMP, 6, null);
    assertParsed(resolver, "timestamptz", SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, null, null);
    assertParsed(resolver, "timestamp with time zone",
        SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, null, null);
    assertParsed(resolver, "time", SqlTypeName.TIME, null, null);
    assertParsed(resolver, "time(3)", SqlTypeName.TIME, 3, null);
    assertParsed(resolver, "timetz", SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, null, null);
    assertParsed(resolver, "time with time zone",
        SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, null, null);
  }

  @Test
  void whitespaceAndCaseAreNormalized() {
    TableMetadata.Column column = resolver.parseColumn("c", "  DECIMAL(10,2) ");
    assertEquals(SqlTypeName.DECIMAL, column.sqlTypeName());
    assertEquals(10, column.precision());
    assertEquals(2, column.scale());
  }

  @Test
  void unknownTypesAreRejected() {
    for (String bad : new String[] {"struct<a int>", "int[]", "jsonb", "uuid", "money"}) {
      SqlMaskException e = assertThrows(SqlMaskException.class,
          () -> resolver.parseColumn("c", bad), bad);
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
      assertTrue(e.getMessage().contains("unsupported or malformed type declaration"),
          () -> bad + ": " + e.getMessage());
    }
  }

  @Test
  void parametersOnParameterlessTypesAreRejected() {
    for (String bad : new String[] {"boolean(1)", "integer(3)", "bigint(9)", "date(6)"}) {
      SqlMaskException e = assertThrows(SqlMaskException.class,
          () -> resolver.parseColumn("c", bad), bad);
      assertTrue(e.getMessage().contains("unsupported or malformed type declaration"),
          () -> bad + ": " + e.getMessage());
    }
  }

  @Test
  void decimalScaleWithoutPrecisionIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> resolver.parseColumn("c", "decimal(,2)"));
    assertTrue(e.getMessage().contains("unsupported or malformed type declaration"),
        () -> e.getMessage());
  }
}
