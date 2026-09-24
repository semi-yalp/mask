package io.sqlmask.common.metadata;

import java.util.List;
import java.util.OptionalLong;

/**
 * Read primitive over a metadata source (data-plane semantics). The HTTP
 * transport ({@link HttpMetadataClient}) talks to a standalone metadata
 * service; the monolith's rewrite path supplies an in-process implementation
 * over the local metadata domain services. Snapshots are value records so
 * both transports share one assembly path.
 */
public interface MetadataClient {

  /** 200 → version; 404 → empty; 401/other/unreachable → fail-closed exceptions. */
  OptionalLong versionOf(String instance);

  /** One consistent instance snapshot: version and tables always pair. */
  MetadataSnapshot fetch(String instance);

  record MetadataSnapshot(String instance, String dialect, long metadataVersion,
                          List<TableSnapshot> tables) {
  }

  record TableSnapshot(String catalog, String schema, String name,
                       List<ColumnSnapshot> columns) {
  }

  record ColumnSnapshot(String name, String type) {
  }
}
