package io.sqlmask.metaserver.classification;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.StructureService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data-classification plane: manual per-column assertions (MANUAL source,
 * validated against the taxonomy) plus the name-heuristic auto pass (AUTO
 * source, insert-only — an existing classification is never overwritten) and
 * the cross-instance overview statistics.
 */
@Service
public class ClassificationService {

  private final ClassificationStore store;
  private final StructureService structures;
  private final MetadataService instances;

  public ClassificationService(ClassificationStore store, StructureService structures,
      MetadataService instances) {
    this.store = store;
    this.structures = structures;
    this.instances = instances;
  }

  public List<Classification> list(String instance) {
    requireInstance(instance);
    return store.find(instance);
  }

  public Optional<Classification> get(String instance, String columnKey) {
    requireInstance(instance);
    return store.find(instance, normalizeColumnKey(columnKey));
  }

  /** Manual upsert: category/level validated, source pinned to MANUAL. */
  public Classification upsert(String instance, String columnKey, String category, String level,
      String note) {
    requireInstance(instance);
    String key = normalizeColumnKey(columnKey);
    String safeCategory = ClassificationTaxonomy.requireCategory(category);
    String safeLevel = ClassificationTaxonomy.requireLevel(level);
    store.upsert(new Classification(instance, key, safeCategory, safeLevel,
        Classification.SOURCE_MANUAL, blankToNull(note), null));
    return store.find(instance, key).orElseThrow();
  }

  public boolean delete(String instance, String columnKey) {
    requireInstance(instance);
    return store.delete(instance, normalizeColumnKey(columnKey));
  }

  /**
   * Heuristic pass over every structure column: unclassified columns whose
   * name matches a taxonomy rule get an AUTO classification; existing rows
   * (MANUAL or earlier AUTO) are left untouched.
   */
  public AutoResult autoClassify(String instance) {
    requireInstance(instance);
    List<TableStructure> tables = structures.load(instance);
    Set<String> classified = store.find(instance).stream()
        .map(Classification::columnKey)
        .collect(Collectors.toSet());
    int considered = 0;
    int already = 0;
    int created = 0;
    for (TableStructure table : tables) {
      for (TableStructure.ColumnStructure column : table.columns()) {
        considered++;
        String columnKey = columnKey(table, column);
        if (classified.contains(columnKey)) {
          already++;
          continue;
        }
        Optional<Classification> guess = ClassificationTaxonomy.guess(instance, columnKey,
            column.name());
        if (guess.isPresent()) {
          store.upsert(guess.get());
          created++;
        }
      }
    }
    return new AutoResult(considered, created, already);
  }

  /** Global stats plus one row per registered instance, ordered by name. */
  public Overview overview() {
    Map<String, List<Classification>> byInstance = store.listAll().stream()
        .collect(Collectors.groupingBy(Classification::instance));
    List<InstanceStat> stats = instances.list().stream()
        .map(row -> instanceStat(row.name(), byInstance.getOrDefault(row.name(), List.of())))
        .sorted(java.util.Comparator.comparing(InstanceStat::instance))
        .toList();
    return new Overview(
        stats.stream().mapToLong(InstanceStat::classified).sum(),
        stats.stream().mapToLong(InstanceStat::high).sum(),
        stats.stream().mapToLong(InstanceStat::medium).sum(),
        stats.stream().mapToLong(InstanceStat::low).sum(),
        stats);
  }

  private InstanceStat instanceStat(String instance, List<Classification> rows) {
    int columns = structures.load(instance).stream()
        .mapToInt(table -> table.columns().size())
        .sum();
    return new InstanceStat(instance, columns, rows.size(),
        rows.stream().filter(row -> "HIGH".equals(row.level())).count(),
        rows.stream().filter(row -> "MEDIUM".equals(row.level())).count(),
        rows.stream().filter(row -> "LOW".equals(row.level())).count());
  }

  private void requireInstance(String instance) {
    instances.get(instance);
  }

  /** {@code catalog.schema.table.column}, fully lowered; must have 4 parts. */
  private static String normalizeColumnKey(String columnKey) {
    if (columnKey == null || columnKey.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "columnKey is required");
    }
    String lowered = columnKey.trim().toLowerCase(Locale.ROOT);
    String[] parts = lowered.split("\\.");
    if (parts.length != 4 || Arrays.stream(parts).anyMatch(String::isBlank)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "columnKey '" + columnKey + "' must be catalog.schema.table.column");
    }
    return lowered;
  }

  private static String columnKey(TableStructure table, TableStructure.ColumnStructure column) {
    return (table.catalog() + "." + table.schema() + "." + table.name() + "." + column.name())
        .toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String note) {
    return note == null || note.isBlank() ? null : note.trim();
  }

  /** Outcome of one heuristic pass. */
  public record AutoResult(int columnsConsidered, int created, int alreadyClassified) {
  }

  /** Global classification counters plus the per-instance breakdown. */
  public record Overview(long totalClassified, long high, long medium, long low,
      List<InstanceStat> instances) {
  }

  /** One instance's column coverage: total columns vs classified columns by level. */
  public record InstanceStat(String instance, long columns, long classified, long high,
      long medium, long low) {
  }
}
