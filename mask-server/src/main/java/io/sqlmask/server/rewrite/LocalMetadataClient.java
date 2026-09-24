package io.sqlmask.server.rewrite;

import io.sqlmask.common.metadata.MetadataClient;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.store.MetaStore;

import java.util.List;
import java.util.OptionalLong;

/**
 * In-process {@link MetadataClient} over the metadata domain services: no
 * HTTP, no serialization. Snapshot semantics match the data-plane payload
 * (version and tables always pair, no rowFilter field — that is policy
 * domain data).
 */
public final class LocalMetadataClient implements MetadataClient {

  private final MetadataService instances;

  public LocalMetadataClient(MetadataService instances) {
    this.instances = instances;
  }

  @Override
  public OptionalLong versionOf(String instance) {
    return OptionalLong.of(instances.get(instance).metadataVersion());
  }

  @Override
  public MetadataSnapshot fetch(String instance) {
    MetaStore.InstanceSnapshot snapshot = instances.snapshot(instance);
    InstanceRow row = snapshot.instance();
    return new MetadataSnapshot(row.name(), row.dialect(), row.metadataVersion(),
        snapshot.tables().stream().map(LocalMetadataClient::toSnapshot).toList());
  }

  private static TableSnapshot toSnapshot(TableStructure table) {
    return new TableSnapshot(table.catalog(), table.schema(), table.name(),
        table.columns().stream()
            .map(c -> new ColumnSnapshot(c.name(), c.type()))
            .toList());
  }
}
