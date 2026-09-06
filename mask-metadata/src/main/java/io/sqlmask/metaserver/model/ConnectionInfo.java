package io.sqlmask.metaserver.model;

import io.sqlmask.error.SqlMaskException;

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

  /**
   * Builds the connection group from request fields: all-blank yields null
   * (no connection), any present field requires the complete group.
   */
  public static ConnectionInfo ofNullable(String host, Integer port, String database,
      String dbUser, String passwordRef, String sslmode, Integer connectTimeoutSeconds,
      List<String> schemas, boolean includeViews) {
    boolean any = (host != null && !host.isBlank()) || port != null
        || (database != null && !database.isBlank()) || (dbUser != null && !dbUser.isBlank())
        || (passwordRef != null && !passwordRef.isBlank())
        || (sslmode != null && !sslmode.isBlank()) || connectTimeoutSeconds != null
        || (schemas != null && !schemas.isEmpty());
    if (!any) {
      return null;
    }
    if (host == null || host.isBlank() || port == null || port <= 0
        || database == null || database.isBlank() || dbUser == null || dbUser.isBlank()
        || passwordRef == null || passwordRef.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "connection settings must be provided as a complete group "
              + "(host, port, database, user, passwordRef)");
    }
    return new ConnectionInfo(host, port, database, dbUser, passwordRef, sslmode,
        connectTimeoutSeconds == null ? 0 : connectTimeoutSeconds, schemas, includeViews);
  }
}
