package io.sqlmask.metaserver.model;

import io.sqlmask.error.SqlMaskException;

/** Stored instance: identity + dialect + optional engine override (currently
 * only 'starrocks'; otherwise derived from the dialect) + optional connection
 * group + query-gateway options (submitter type, rewrite-failure posture,
 * syntax-extension overrides) + version anchor. */
public record InstanceRow(String name, String dialect, String engine, ConnectionInfo connection,
    long metadataVersion, String submitter, String onRewriteFailure, Boolean topN,
    Boolean insertOverwrite) {

  public static final String DEFAULT_SUBMITTER = "jdbc";
  public static final String DEFAULT_ON_REWRITE_FAILURE = "REJECT";

  public InstanceRow {
    submitter = submitter == null || submitter.isBlank() ? DEFAULT_SUBMITTER : submitter;
    onRewriteFailure = onRewriteFailure == null || onRewriteFailure.isBlank()
        ? DEFAULT_ON_REWRITE_FAILURE
        : onRewriteFailure;
  }

  /** Legacy-arity constructor: gateway options take their defaults. */
  public InstanceRow(String name, String dialect, String engine, ConnectionInfo connection,
      long metadataVersion) {
    this(name, dialect, engine, connection, metadataVersion, null, null, null, null);
  }

  public InstanceRow withVersion(long newVersion) {
    return new InstanceRow(name, dialect, engine, connection, newVersion,
        submitter, onRewriteFailure, topN, insertOverwrite);
  }

  /** engine 非空取 engine，否则由 dialect 推导（与 QueryEngine 目录一致）。 */
  public String effectiveEngine() {
    if (engine != null && !engine.isBlank()) {
      return engine;
    }
    return switch (dialect) {
      case "postgresql" -> "postgresql";
      case "trino" -> "trino";
      case "hive" -> "hive";
      case "sparksql" -> "sparksql";
      default -> "mysql";
    };
  }

  /** Validates the gateway option fields; unknown values are CONFIG_ERROR. */
  public void validateGatewayOptions() {
    if (!submitter.equals(DEFAULT_SUBMITTER) && !submitter.equals("http")) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "': submitter must be 'jdbc' or 'http' (got '" + submitter + "')");
    }
    if (!onRewriteFailure.equals(DEFAULT_ON_REWRITE_FAILURE)
        && !onRewriteFailure.equals("PASSTHROUGH")) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "': onRewriteFailure must be REJECT or PASSTHROUGH (got '"
              + onRewriteFailure + "')");
    }
  }
}
