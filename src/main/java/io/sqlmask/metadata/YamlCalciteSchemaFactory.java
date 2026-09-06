package io.sqlmask.metadata;

import io.sqlmask.config.LoadedConfig;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Builds a Calcite {@link SchemaPlus} tree ({@code catalog.schema.table})
 * from the validated YAML configuration. Tables expose the declared column
 * order and Calcite-compatible types; schema object names are registered
 * exactly as declared so quoted/case-sensitive references resolve against
 * the declared spelling.
 */
public final class YamlCalciteSchemaFactory {

  private YamlCalciteSchemaFactory() {
  }

  public static SchemaPlus create(LoadedConfig loaded) {
    SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
    for (TableMetadata table : loaded.tables()) {
      SchemaPlus catalog = subSchema(root, table.catalog());
      SchemaPlus schema = subSchema(catalog, table.schema());
      schema.add(table.name(), new YamlTable(table));
    }
    return root;
  }

  private static SchemaPlus subSchema(SchemaPlus parent, String name) {
    SchemaPlus existing = parent.getSubSchema(name);
    return existing != null ? existing : parent.add(name, new AbstractSchema() {
    });
  }

  /** Calcite table backed by YAML column declarations. */
  static final class YamlTable extends AbstractTable {

    private final TableMetadata metadata;

    YamlTable(TableMetadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      RelDataTypeFactory.Builder builder = typeFactory.builder();
      for (TableMetadata.Column column : metadata.columns()) {
        builder.add(column.name(), typeOf(typeFactory, column));
      }
      return builder.build();
    }
  }

  private static RelDataType typeOf(RelDataTypeFactory typeFactory, TableMetadata.Column column) {
    SqlTypeName typeName = column.sqlTypeName();
    RelDataType type = switch (typeName) {
      case DECIMAL -> column.precision() != null
          ? typeFactory.createSqlType(typeName, column.precision(), column.scale())
          : typeFactory.createSqlType(typeName);
      case CHAR, VARCHAR, TIME, TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE, TIME_WITH_LOCAL_TIME_ZONE ->
          column.precision() != null
              ? typeFactory.createSqlType(typeName, column.precision())
              : typeFactory.createSqlType(typeName);
      default -> typeFactory.createSqlType(typeName);
    };
    // YAML does not declare nullability; PostgreSQL defaults to nullable.
    // Keeping columns nullable also stops Calcite from rewriting e.g.
    // count(col) into count(*), which would erase column origins.
    return typeFactory.createTypeWithNullability(type, true);
  }
}
