package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
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
    return create(name, dialect, engine, connection, null, null, null, null);
  }

  /** Full create with the query-gateway options (submitter, rewrite-failure
   * posture, syntax-extension overrides). */
  public InstanceRow create(String name, String dialect, String engine, ConnectionInfo connection,
      String submitter, String onRewriteFailure, Boolean topN, Boolean insertOverwrite) {
    String trimmed = requireName(name);
    String normalizedDialect = normalizeDialect(dialect);
    String normalizedEngine = normalizeEngine(engine, normalizedDialect);
    InstanceRow row = new InstanceRow(trimmed, normalizedDialect, normalizedEngine, connection, 1,
        submitter, onRewriteFailure, topN, insertOverwrite);
    row.validateGatewayOptions();
    if (store.findInstance(trimmed).isPresent()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_EXISTS,
          "instance '" + trimmed + "' already exists");
    }
    store.createInstance(row);
    return store.findInstance(trimmed).orElseThrow();
  }

  /** Updates the query-gateway options of an existing instance. */
  public InstanceRow updateGatewayOptions(String name, String submitter, String onRewriteFailure,
      Boolean topN, Boolean insertOverwrite) {
    InstanceRow current = get(name);
    InstanceRow updated = new InstanceRow(current.name(), current.dialect(), current.engine(),
        current.connection(), current.metadataVersion(),
        submitter, onRewriteFailure, topN, insertOverwrite);
    updated.validateGatewayOptions();
    store.updateGatewayOptions(name, updated);
    return get(name);
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

  /** Registers a target table structure (e.g. CTAS copy targets) over the whole
   * existing structure; bumps metadata_version via {@code replaceStructure}. An
   * unknown instance is a 404, matching every other mutation path (M10). */
  public InstanceRow registerStructure(String name, List<TableStructure> tables) {
    String trimmed = requireName(name);
    get(trimmed);
    store.replaceStructure(trimmed, tables == null ? List.of() : tables);
    return get(trimmed);
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
