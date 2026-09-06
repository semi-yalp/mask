package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.MetaStore;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a table structure against the instance's dialect type resolver
 * (write-time round-trip guarantee) and replaces it atomically in the store.
 * An empty list is legal: the collection scenario may legitimately capture
 * zero tables (warning surfaced by the caller).
 */
@Service
public class StructureService {

  private final MetaStore store;

  public StructureService(MetaStore store) {
    this.store = store;
  }

  public long replace(String instanceName, List<TableStructure> tables) {
    InstanceRow instance = store.findInstance(instanceName)
        .orElseThrow(() -> new SqlMaskException(
            SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
            "instance '" + instanceName + "' does not exist"));
    Set<String> seen = new HashSet<>();
    for (TableStructure table : tables) {
      if (table.columns().isEmpty()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            table.qualifiedName() + ": must declare at least one column");
      }
      if (!seen.add(normalizeQualifiedName(table))) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "duplicate table '" + table.qualifiedName() + "'");
      }
      for (TableStructure.ColumnStructure column : table.columns()) {
        validateColumn(instance.dialect(), table, column);
      }
    }
    store.replaceStructure(instanceName, tables);
    return store.findInstance(instanceName).orElseThrow().metadataVersion();
  }

  public List<TableStructure> load(String name) {
    return store.loadStructure(name);
  }

  private static void validateColumn(String dialect, TableStructure table,
      TableStructure.ColumnStructure column) {
    try {
      DialectProfiles.byName(dialect).typeResolver().parseColumn(column.name(), column.type());
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          table.qualifiedName() + "." + column.name() + ": " + e.getMessage());
    }
  }

  private static String normalizeQualifiedName(TableStructure table) {
    return table.catalog().toLowerCase(Locale.ROOT) + "."
        + table.schema().toLowerCase(Locale.ROOT) + "."
        + table.name().toLowerCase(Locale.ROOT);
  }
}
