package io.masklite.dialect;

import io.masklite.metadata.TableMetadata;

/**
 * Translates the engine's scalar type declarations (the YAML metadata
 * vocabulary) into Calcite types. One implementation per query engine.
 */
public interface TypeResolver {

  /** Parses one column type declaration; malformed declarations fail with {@code CONFIG_ERROR}. */
  TableMetadata.Column parseColumn(String name, String typeDeclaration);
}
