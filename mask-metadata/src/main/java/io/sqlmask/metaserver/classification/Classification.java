package io.sqlmask.metaserver.classification;

import java.time.Instant;

/** One classified column: {@code columnKey} is the fully lowered
 * {@code catalog.schema.table.column} path; {@code source} is MANUAL (admin
 * assertion, wins on upsert) or AUTO (name heuristic). */
public record Classification(String instance, String columnKey, String category, String level,
    String source, String note, Instant updatedAt) {

  public static final String SOURCE_MANUAL = "MANUAL";
  public static final String SOURCE_AUTO = "AUTO";
}
