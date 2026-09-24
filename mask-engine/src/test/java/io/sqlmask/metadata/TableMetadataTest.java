package io.sqlmask.metadata;

import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Column invariants and declaration reconstruction when no raw text is kept. */
class TableMetadataTest {

  @Test
  void negativePrecisionAndScaleAreRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new TableMetadata.Column("c", SqlTypeName.VARCHAR, -1, null));
    assertThrows(IllegalArgumentException.class,
        () -> new TableMetadata.Column("c", SqlTypeName.DECIMAL, 10, -2));
  }

  @Test
  void blankDeclarationIsNormalizedToNull() {
    TableMetadata.Column column = new TableMetadata.Column("c", SqlTypeName.INTEGER, null, null, " ");
    assertNull(column.declaration());
    assertEquals("integer", column.typeDeclaration());
  }

  @Test
  void declarationIsReconstructedWithoutRawText() {
    assertEquals("boolean",
        new TableMetadata.Column("c", SqlTypeName.BOOLEAN, null, null).typeDeclaration());
    assertEquals("smallint",
        new TableMetadata.Column("c", SqlTypeName.SMALLINT, null, null).typeDeclaration());
    assertEquals("integer",
        new TableMetadata.Column("c", SqlTypeName.INTEGER, null, null).typeDeclaration());
    assertEquals("bigint",
        new TableMetadata.Column("c", SqlTypeName.BIGINT, null, null).typeDeclaration());
    assertEquals("real",
        new TableMetadata.Column("c", SqlTypeName.REAL, null, null).typeDeclaration());
    assertEquals("double precision",
        new TableMetadata.Column("c", SqlTypeName.DOUBLE, null, null).typeDeclaration());
    assertEquals("decimal",
        new TableMetadata.Column("c", SqlTypeName.DECIMAL, null, null).typeDeclaration());
    assertEquals("char",
        new TableMetadata.Column("c", SqlTypeName.CHAR, null, null).typeDeclaration());
    assertEquals("varchar",
        new TableMetadata.Column("c", SqlTypeName.VARCHAR, null, null).typeDeclaration());
    assertEquals("date",
        new TableMetadata.Column("c", SqlTypeName.DATE, null, null).typeDeclaration());
    assertEquals("timestamp",
        new TableMetadata.Column("c", SqlTypeName.TIMESTAMP, null, null).typeDeclaration());
    assertEquals("timestamptz",
        new TableMetadata.Column("c", SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, null, null)
            .typeDeclaration());
    assertEquals("time",
        new TableMetadata.Column("c", SqlTypeName.TIME, null, null).typeDeclaration());
    assertEquals("timetz",
        new TableMetadata.Column("c", SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, null, null)
            .typeDeclaration());
    assertEquals("varchar(20)",
        new TableMetadata.Column("c", SqlTypeName.VARCHAR, 20, null).typeDeclaration());
    assertEquals("decimal(10,2)",
        new TableMetadata.Column("c", SqlTypeName.DECIMAL, 10, 2).typeDeclaration());
    assertEquals("any",
        new TableMetadata.Column("c", SqlTypeName.ANY, null, null).typeDeclaration());
  }

  @Test
  void rowFilterBlankNormalizesToNull() {
    TableMetadata table = new TableMetadata("crm", "public", "customer",
        List.of(new TableMetadata.Column("id", SqlTypeName.BIGINT, null, null)), "  ");
    assertNull(table.rowFilter());
    assertEquals("crm.public.customer", table.qualifiedName());
  }

  @Test
  void factoryParsesColumnDeclarations() {
    TableMetadata table = TableMetadata.of("crm", "public", "customer",
        new String[][] {{"id", "bigint"}, {"phone", "varchar(20)"}});
    assertEquals(2, table.columns().size());
    assertEquals(SqlTypeName.BIGINT, table.columns().get(0).sqlTypeName());
    assertEquals(SqlTypeName.VARCHAR, table.columns().get(1).sqlTypeName());
    assertEquals(20, table.columns().get(1).precision());
    assertNull(table.rowFilter());
  }
}
