package io.sqlmask.metaserver.model;

import java.util.List;

/**
 * Connection and collection-scope settings of an instance. Absent as a whole
 * (null) for YAML-imported instances without connection settings.
 */
public record ConnectionInfo(String host, int port, String database, String dbUser,
    String passwordRef, String sslmode, int connectTimeoutSeconds, List<String> schemas,
    boolean includeViews) {

  public ConnectionInfo {
    schemas = List.copyOf(schemas == null ? List.of() : schemas);
    sslmode = sslmode == null || sslmode.isBlank() ? "disable" : sslmode;
    if (connectTimeoutSeconds <= 0) {
      connectTimeoutSeconds = 10;
    }
  }
}
