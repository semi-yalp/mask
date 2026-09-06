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

  public InstanceRow create(String name, String dialect, ConnectionInfo connection) {
    String trimmed = requireName(name);
    String normalizedDialect = normalizeDialect(dialect);
    if (store.findInstance(trimmed).isPresent()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_EXISTS,
          "instance '" + trimmed + "' already exists");
    }
    store.createInstance(new InstanceRow(trimmed, normalizedDialect, connection, 1));
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
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + dialect + "' (supported: postgresql, mysql, trino)");
    }
    return dialect.trim().toLowerCase(Locale.ROOT);
  }
}
