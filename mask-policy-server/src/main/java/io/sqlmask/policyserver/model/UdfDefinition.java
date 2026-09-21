package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * A registered masking UDF of one engine instance: its name plus the overload
 * signatures installed in the engine (PG identifies functions by name +
 * argument types). The first parameter of every signature binds the masked
 * column's value; the remaining ones bind the policy's ordered scalar
 * arguments.
 */
public record UdfDefinition(String name, List<UdfSignature> signatures) {

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