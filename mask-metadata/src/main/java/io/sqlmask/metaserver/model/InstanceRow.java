package io.sqlmask.metaserver.model;

/** Stored instance: identity + dialect + optional engine override (currently
 * only 'starrocks'; otherwise derived from the dialect) + optional connection
 * group + version anchor. */
public record InstanceRow(String name, String dialect, String engine, ConnectionInfo connection,
    long metadataVersion) {

  public InstanceRow withVersion(long newVersion) {
    return new InstanceRow(name, dialect, engine, connection, newVersion);
  }

  /** engine 非空取 engine，否则由 dialect 推导（与 QueryEngine 目录一致）。 */
  public String effectiveEngine() {
    if (engine != null && !engine.isBlank()) {
      return engine;
    }
    return switch (dialect) {
      case "postgresql" -> "postgresql";
      case "trino" -> "trino";
      default -> "mysql";
    };
  }
}
