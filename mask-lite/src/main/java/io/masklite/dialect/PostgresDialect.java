package io.masklite.dialect;

import io.masklite.error.SqlMaskException;
import io.masklite.sql.CteExpander;
import io.masklite.sql.SqlValidatorFactory;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL dialect: babel parser factory (PostgreSQL syntax extensions),
 * PostgreSQL identifier semantics (unquoted identifiers fold to lower case,
 * double-quoted identifiers keep their case) and PostgreSQL SQL rendering.
 * Parsing, validation/conversion and rendering of SELECT queries only.
 */
public final class PostgresDialect {

  public static final String NAME = "postgresql";

  private static final PostgresqlIdentifierPolicy IDENTIFIERS = new PostgresqlIdentifierPolicy();

  /** Parses a single statement; only {@code SELECT} and {@code WITH ... SELECT} are accepted. */
  public SqlNode parse(String sql, int statementOrdinal) {
    SqlNode node;
    try {
      node = SqlParser.create(sql, SqlParser.config()
              .withParserFactory(SqlBabelParserImpl.FACTORY)
              .withQuoting(Quoting.DOUBLE_QUOTE)
              .withUnquotedCasing(Casing.TO_LOWER)
              .withQuotedCasing(Casing.UNCHANGED)
              .withCaseSensitive(true)
              .withConformance(SqlConformanceEnum.DEFAULT))
          .parseStmt();
    } catch (SqlParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.PARSE_ERROR,
          "statement " + statementOrdinal + ": parse error (" + NAME + "): " + e.getMessage(), e);
    }
    classify(node, statementOrdinal);
    return node;
  }

  private void classify(SqlNode node, int statementOrdinal) {
    switch (node.getKind()) {
      case SELECT -> {
        // accepted
      }
      case ORDER_BY -> {
        SqlNode query = ((SqlOrderBy) node).query;
        if (!isQuery(query)) {
          throw unsupported(query.getKind(), statementOrdinal);
        }
      }
      case WITH -> {
        SqlNode body = ((SqlWith) node).body;
        if (!isQuery(body)) {
          throw unsupported(body.getKind(), statementOrdinal);
        }
      }
      default -> throw unsupported(node.getKind(), statementOrdinal);
    }
  }

  private boolean isQuery(SqlNode node) {
    return switch (node.getKind()) {
      case SELECT, WITH, ORDER_BY -> true;
      default -> false;
    };
  }

  private SqlMaskException unsupported(org.apache.calcite.sql.SqlKind kind, int statementOrdinal) {
    return new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
        "statement " + statementOrdinal + ": unsupported statement kind " + kind
            + "; only SELECT and WITH ... SELECT queries are supported in this version");
  }

  /** Validates and converts a parsed statement against {@code rootSchema}. */
  public ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema) {
    // Calcite's validator mutates the parse tree in place, so snapshot the
    // original SQL text before anything touches the tree; this snapshot is
    // the inner query of a generated wrapper.
    String originalSql = unparse(parsed);
    // Calcite keeps CTE bodies out of the relational tree (transient scans),
    // so the analysis tree inlines CTEs into derived tables first.
    SqlNode analysisTree = new CteExpander().expand(parsed);
    SqlValidatorFactory factory = new SqlValidatorFactory(rootSchema,
        schemaPaths(rootSchema), SqlConformanceEnum.DEFAULT,
        true, PostgresqlFunctions.TABLE);
    org.apache.calcite.sql.validate.SqlValidator validator = factory.createValidator();
    SqlNode validated;
    try {
      validated = validator.validate(analysisTree);
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "validation failed: " + e.getMessage(), e);
    }
    org.apache.calcite.sql2rel.SqlToRelConverter converter = factory.createConverter(validator);
    RelRoot root;
    try {
      root = converter.convertQuery(validated, false, true);
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "query conversion failed: " + e.getMessage(), e);
    }
    if (root.rel.getRowType().getFieldCount() != root.validatedRowType.getFieldCount()) {
      // ORDER BY referencing a column outside the SELECT projection: the
      // converter appends the sort keys to the relational output. Project them
      // away (root.fields maps the relational output onto the validated shape)
      // so lineage sees one column per validated field; the Sort's collation
      // sits below the added Project and stays intact.
      List<Integer> projection = new ArrayList<>();
      for (java.util.Map.Entry<Integer, String> field : root.fields) {
        projection.add(field.getKey());
      }
      root = root.withRel(org.apache.calcite.plan.RelOptUtil.createProject(root.rel, projection));
    }
    if (root.rel.getRowType().getFieldCount() != root.validatedRowType.getFieldCount()) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "converted query does not match the validated output shape");
    }
    return new ValidatedSql(originalSql, validated, root, validator);
  }

  /** Renders a statement as SQL text in this dialect (no trailing ';'). */
  public String unparse(SqlNode node) {
    return node.toSqlString(config -> config
        .withDialect(PostgresqlSqlDialect.DEFAULT)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withSelectListItemsOnSeparateLines(false)
        .withUpdateSetListNewline(false)
        .withIndentation(0)).getSql();
  }

  /** Identifier rendering rules of the generated outer projection. */
  public IdentifierPolicy identifiers() {
    return IDENTIFIERS;
  }

  /**
   * Search paths derived from the schema tree: {@code [catalog, schema]} pairs
   * so both fully-qualified and search-path table references resolve.
   */
  private List<List<String>> schemaPaths(SchemaPlus rootSchema) {
    List<List<String>> paths = new ArrayList<>();
    for (String catalog : rootSchema.getSubSchemaNames()) {
      SchemaPlus catalogSchema = rootSchema.getSubSchema(catalog);
      for (String schema : catalogSchema.getSubSchemaNames()) {
        paths.add(List.of(catalog, schema));
      }
    }
    return paths;
  }
}
