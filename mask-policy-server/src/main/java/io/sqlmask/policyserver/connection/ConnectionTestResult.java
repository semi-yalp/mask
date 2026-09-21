package io.sqlmask.policyserver.connection;

import java.util.List;

/**
 * Result of a successful connection test. A failed test is not represented
 * here — {@link EngineAccess#test} throws {@code SqlMaskException(
 * CONNECTION_FAILED)} instead, so callers never see a partial connection.
 */
public record ConnectionTestResult(boolean ok, String dialect, long latencyMs,
    List<String> warnings) {

  public ConnectionTestResult {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}