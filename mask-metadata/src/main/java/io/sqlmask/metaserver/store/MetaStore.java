package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.List;
import java.util.Optional;

/** Persistence port for instances and their table structures. */
public interface MetaStore {

  /** One consistent read: the instance row plus its structure, taken together
   * so the data plane never pairs an old version with new tables (M7). */
  record InstanceSnapshot(InstanceRow instance, List<TableStructure> tables) {
  }

  void createInstance(InstanceRow row);

  Optional<InstanceRow> findInstance(String name);

  List<InstanceRow> listInstances();

  /** Updates the mutable connection group; bumps metadata_version in the same transaction. */
  void updateInstance(String name, ConnectionInfo connection);

  /** Updates the query-gateway options (submitter, rewrite posture, syntax
   * overrides) without touching the structure or the connection. */
  default void updateGatewayOptions(String name, InstanceRow updated) {
    throw new UnsupportedOperationException("gateway options not supported by this store");
  }

  void deleteInstance(String name);

  /** Replaces the whole table structure; bumps metadata_version in the same transaction. */
  void replaceStructure(String name, List<TableStructure> tables);

  List<TableStructure> loadStructure(String name);

  /** Row + structure in one consistent read; unknown name → METADATA_INSTANCE_NOT_FOUND. */
  InstanceSnapshot loadSnapshot(String name);
}
