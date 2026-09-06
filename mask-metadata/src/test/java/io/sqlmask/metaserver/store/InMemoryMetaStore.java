package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fixture: in-memory {@link MetaStore} with version bumps like the JDBC store. */
public class InMemoryMetaStore implements MetaStore {

  public final Map<String, InstanceRow> instances = new LinkedHashMap<>();
  public final Map<String, List<TableStructure>> structures = new LinkedHashMap<>();
  public final Map<String, Integer> versionBumps = new LinkedHashMap<>();

  @Override
  public void createInstance(InstanceRow row) {
    instances.put(row.name(), row);
    structures.put(row.name(), new ArrayList<>());
  }

  @Override
  public Optional<InstanceRow> findInstance(String name) {
    return Optional.ofNullable(instances.get(name));
  }

  @Override
  public List<InstanceRow> listInstances() {
    return new ArrayList<>(instances.values());
  }

  @Override
  public void updateInstance(String name, ConnectionInfo connection) {
    InstanceRow row = instances.get(name);
    instances.put(name, new InstanceRow(row.name(), row.dialect(), connection,
        row.metadataVersion() + 1));
    versionBumps.merge(name, 1, Integer::sum);
  }

  @Override
  public void deleteInstance(String name) {
    instances.remove(name);
    structures.remove(name);
  }

  @Override
  public void replaceStructure(String name, List<TableStructure> tables) {
    structures.put(name, new ArrayList<>(tables));
    InstanceRow row = instances.get(name);
    instances.put(name, new InstanceRow(row.name(), row.dialect(), row.connection(),
        row.metadataVersion() + 1));
    versionBumps.merge(name, 1, Integer::sum);
  }

  @Override
  public List<TableStructure> loadStructure(String name) {
    return new ArrayList<>(structures.getOrDefault(name, List.of()));
  }
}
