package io.sqlmask.policyserver.model;

/**
 * One declared column: its identifier and the dialect-specific type
 * declaration, parsed by the engine's {@code TypeResolver} at validation time.
 */
public record ColumnDef(String name, String typeDeclaration) {
}
