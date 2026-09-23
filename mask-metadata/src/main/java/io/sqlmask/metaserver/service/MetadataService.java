package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.store.MetaStore;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Locale;

/** Instance CRUD with spec §4.1 rules: immutable name/dialect, versioned mutations. */
@Service
public class MetadataService {

  private final MetaStore store;

  public MetadataService(MetaStore store) {
    this.store = store;
  }

  public InstanceRow create(String name, String dialect, String engine, ConnectionInfo connection) {
    String trimmed = requireName(name);
    String normalizedDialect = normalizeDialect(dialect);
    String normalizedEngine = normalizeEngine(engine, normalizedDialect);
    if (store.findInstance(trimmed).isPresent()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_EXISTS,
          "instance '" + trimmed + "' already exists");
    }
    store.createInstance(
        new InstanceRow(trimmed, normalizedDialect, normalizedEngine, connection, 1));
    return store.findInstance(trimmed).orElseThrow();
  }

  public InstanceRow get(String name) {
    return store.findInstance(requireName(name))
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
            "instance '" + name + "' does not exist"));
  }

  public List<InstanceRow> list() {
    return store.listInstances();
  }

  /** One consistent read of the instance row plus its structure (M7). */
  public MetaStore.InstanceSnapshot snapshot(String name) {
    return store.loadSnapshot(requireName(name));
  }

  public InstanceRow updateConnection(String name, ConnectionInfo connection) {
    get(name);
    store.updateInstance(requireName(name), connection);
    return get(name);
  }

  public void delete(String name) {
    get(name);
    store.deleteInstance(requireName(name));
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance name is required");
    }
    return name.trim();
  }

  private static String normalizeDialect(String dialect) {
    try {
      DialectProfiles.byName(dialect);
    } catch (RuntimeException e) {
      // 支持清单与 DialectProfiles.byName 的消息同源，避免新增方言后此处陈旧
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + dialect + "' (supported: "
              + String.join(", ", DialectProfiles.names()) + ")");
    }
    return dialect.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeEngine(String engine, String normalizedDialect) {
    if (engine == null || engine.isBlank()) {
      return null;
    }
    String normalized = engine.trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.equals("starrocks")) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported engine '" + engine + "' (engines are dialect-derived; "
              + "the only explicit engine is starrocks)");
    }
    if (!normalizedDialect.equals("mysql")) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "engine 'starrocks' requires dialect 'mysql'");
    }
    return normalized;
  }
}
