package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;

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
    DialectCapabilities capabilities) {

  /**
   * Search paths for unqualified table references: {@code CATALOG_SCHEMA}
   * yields {@code [catalog, schema]} pairs (PostgreSQL/Trino);
   * {@code CATALOG_SCHEMA_AND_SCHEMA} additionally yields one-element
   * {@code [schema]} paths so MySQL two-part names ({@code db.table}) resolve.
   */
  public enum SchemaPathStyle { CATALOG_SCHEMA, CATALOG_SCHEMA_AND_SCHEMA }
}
