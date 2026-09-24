package io.sqlmask.metaserver.classification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-persistent {@link ClassificationStore}: the no-datasource fallback in
 * {@code MetadataServerConfig} and the fixture behind the classification
 * tests. Mirrors {@link JdbcClassificationStore} semantics including the
 * created-vs-updated answer of {@code upsert}.
 */
public class InMemoryClassificationStore implements ClassificationStore {

  private final Map<String, Classification> rows = new ConcurrentHashMap<>();

  private static String id(String instance, String columnKey) {
    return instance + "\u0000" + columnKey;
  }

  @Override
  public boolean upsert(Classification classification) {
    Instant now = classification.updatedAt() == null ? Instant.now()
        : classification.updatedAt();
    Classification stamped = new Classification(classification.instance(),
        classification.columnKey(), classification.category(), classification.level(),
        classification.source(), classification.note(), now);
    return rows.put(id(stamped.instance(), stamped.columnKey()), stamped) == null;
  }

  @Override
  public List<Classification> find(String instance) {
    List<Classification> found = new ArrayList<>();
    for (Classification row : rows.values()) {
      if (row.instance().equals(instance)) {
        found.add(row);
      }
    }
    found.sort(Comparator.comparing(Classification::columnKey));
    return found;
  }

  @Override
  public Optional<Classification> find(String instance, String columnKey) {
    return Optional.ofNullable(rows.get(id(instance, columnKey)));
  }

  @Override
  public boolean delete(String instance, String columnKey) {
    return rows.remove(id(instance, columnKey)) != null;
  }

  @Override
  public List<Classification> listAll() {
    List<Classification> all = new ArrayList<>(rows.values());
    all.sort(Comparator.comparing(Classification::instance)
        .thenComparing(Classification::columnKey));
    return all;
  }

  /** Test helper: drop every row (the production store relies on DELETE/TRUNCATE). */
  public void clear() {
    rows.clear();
  }
}
