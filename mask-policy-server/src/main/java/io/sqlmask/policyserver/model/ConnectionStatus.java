package io.sqlmask.policyserver.model;

/**
 * Live status of an instance's engine connection, set by the last connection
 * test ({@code CONNECTED}/{@code FAILED}) or {@code UNCONNECTED} for a
 * metadata-only instance created without a connection.
 */
public enum ConnectionStatus {
  CONNECTED, FAILED, UNCONNECTED
}