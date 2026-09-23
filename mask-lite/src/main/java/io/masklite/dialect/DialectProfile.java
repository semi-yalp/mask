package io.masklite.dialect;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.implicit.TypeCoercionFactory;

/**
 * Declarative description of one query engine's SQL dialect. All engine
 * differences live here; the rewrite pipeline stays dialect-agnostic.
 */
public record DialectProfile(
    String name,
    SqlParser.Config parserConfig,
    SqlConformance validatorConformance,
    boolean caseSensitiveNameMatching,
    SqlOperatorTable functionTable,
    TypeResolver typeResolver,
    SqlDialect sqlDialect,
    IdentifierPolicy identifierPolicy,
    SchemaPathStyle schemaPathStyle,
    TypeCoercionFactory typeCoercionFactory) {

  /**
   * Search paths for unqualified table references: {@code CATALOG_SCHEMA}
   * yields {@code [catalog, schema]} pairs (PostgreSQL); {@code
   * CATALOG_SCHEMA_AND_SCHEMA} additionally yields one-element {@code
   * [catalog]} paths so two-part names ({@code db.table}) resolve — Calcite
   * concatenates each search-path entry with the name's leading schema parts.
   */
  public enum SchemaPathStyle { CATALOG_SCHEMA, CATALOG_SCHEMA_AND_SCHEMA }
}
