package io.sqlmask.metaserver.model;

/** Stored instance: identity + dialect + optional connection group + version anchor. */
public record InstanceRow(String name, String dialect, ConnectionInfo connection,
    long metadataVersion) {

  public InstanceRow withVersion(long newVersion) {
    return new InstanceRow(name, dialect, connection, newVersion);
  }
}
