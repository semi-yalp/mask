package io.sqlmask.policyserver.model;

import java.time.Instant;
import java.util.List;

/**
 * A registered masking UDF of one engine instance: its name plus the overload
 * signatures installed in the engine (PG identifies functions by name +
 * argument types). The first parameter of every signature binds the masked
 * column's value; the remaining ones bind the policy's ordered scalar
 * arguments.
 *
 * <p>{@code source} records where the definition came from
 * ({@code REGISTERED} via the CRUD API, {@code IMPORTED} via the UDF center's
 * engine import) and {@code lastSyncedAt} when the engine was last read; both
 * are informational — signature equality ignores neither, they ride along on
 * import refreshes. The two-argument constructor keeps pre-UDF-center callers
 * (and their tests) compiling with the historical defaults.</p>
 */
public record UdfDefinition(String name, List<UdfSignature> signatures, String source,
    Instant lastSyncedAt) {

  /** Historical shape: a manually registered definition, never synced. */
  public UdfDefinition(String name, List<UdfSignature> signatures) {
    this(name, signatures, "REGISTERED", null);
  }

  public UdfDefinition {
    signatures = List.copyOf(signatures);
  }

  /** One overload: ordered positional parameter type declarations and the return type. */
  public record UdfSignature(List<String> params, String returns) {

    public UdfSignature {
      params = List.copyOf(params);
    }
  }
}
