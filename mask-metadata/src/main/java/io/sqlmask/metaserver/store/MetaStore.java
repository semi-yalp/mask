package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.List;
import java.util.Optional;

/** Persistence port for instances and their table structures. */
public interface MetaStore {

  void createInstance(InstanceRow row);

  Optional<InstanceRow> findInstance(String name);

  List<InstanceRow> listInstances();

  /** Updates the mutable connection group; bumps metadata_version in the same transaction. */
  void updateInstance(String name, ConnectionInfo connection);

  void deleteInstance(String name);

  /** Replaces the whole table structure; bumps metadata_version in the same transaction. */
  void replaceStructure(String name, List<TableStructure> tables);

  List<TableStructure> loadStructure(String name);
}
