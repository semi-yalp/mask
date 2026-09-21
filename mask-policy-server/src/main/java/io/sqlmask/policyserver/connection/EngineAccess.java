package io.sqlmask.policyserver.connection;

import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.TableDef;

import java.util.List;

/**
 * The policy service's direct link to a query engine: connectivity testing and
 * read-only table-structure listing. Implementations resolve the engine
 * password from {@code ConnectionConfig.passwordRef} via the environment at
 * call time and never expose it. A failed test throws
 * {@code SqlMaskException(CONNECTION_FAILED)} with diagnostic text that never
 * contains the password.
 */
public interface EngineAccess {

  /** Tests connectivity; throws {@code CONNECTION_FAILED} on failure. */
  ConnectionTestResult test(ConnectionConfig cfg);

  /** Lists tables (with typed columns) from the engine's system catalog. */
  List<TableDef> fetch(ConnectionConfig cfg);
}