package io.sqlmask.lineage;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lineage of one root output field: its ordinal, name and type, the set of
 * base-column origins and the analysis status.
 */
public record OutputLineage(
    int ordinal,
    String outputName,
    RelDataType outputType,
    Set<ColumnOrigin> origins,
    LineageStatus status) {

  public OutputLineage {
    origins = origins instanceof LinkedHashSet<ColumnOrigin> ? origins : new LinkedHashSet<>(origins);
  }

  public static OutputLineage of(RelDataTypeField field, Set<ColumnOrigin> origins,
      LineageStatus status) {
    return new OutputLineage(field.getIndex(), field.getName(), field.getType(), origins, status);
  }
}
