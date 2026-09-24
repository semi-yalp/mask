package io.sqlmask.metaserver.classification;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link Classification} rows (one per instance column). */
public interface ClassificationStore {

  /** Insert or update by (instance, columnKey); true when a new row was created. */
  boolean upsert(Classification classification);

  /** All classifications of one instance, in columnKey order. */
  List<Classification> find(String instance);

  /** One column's classification, if any. */
  Optional<Classification> find(String instance, String columnKey);

  /** Removes one column's classification; true when a row existed. */
  boolean delete(String instance, String columnKey);

  /** Every classification across instances (admin dump / overview). */
  List<Classification> listAll();
}
