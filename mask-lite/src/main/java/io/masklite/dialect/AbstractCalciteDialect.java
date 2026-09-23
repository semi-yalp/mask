package io.masklite.dialect;

import io.masklite.error.SqlMaskException;
import io.masklite.sql.CteExpander;
import io.masklite.sql.SqlValidatorFactory;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialect-agnostic SELECT pipeline: parse (with the profile's parser config),
 * snapshot the original text, inline CTEs, validate and convert with the
 * profile's validator settings, and unparse with the profile's SqlDialect.
 * Subclasses declare a {@link DialectProfile}.
 */
public abstract class AbstractCalciteDialect implements Dialect {

  protected final DialectProfile profile;

  protected AbstractCalciteDialect(DialectProfile profile) {
    this.profile = profile;
  }

  @Override
  public final DialectProfile profile() {
    return profile;
  }

  @Override
  public final String name() {
    return profile.name();
  }

  @Override
  public final SqlNode parse(String sql, int statementOrdinal) {
    SqlNode node;
    try {
      node = SqlParser.create(sql, profile.parserConfig()).parseStmt();
    } catch (SqlParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.PARSE_ERROR,
          "statement " + statementOrdinal + ": parse error (" + profile.name()
              + "): " + e.getMessage(), e);
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

  @Override
  public final ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema) {
    // Calcite's validator mutates the parse tree in place, so snapshot the
    // original SQL text before anything touches the tree; this snapshot is
    // the inner query of a generated wrapper.
    String originalSql = unparse(parsed);
    // Calcite keeps CTE bodies out of the relational tree (transient scans),
    // so the analysis tree inlines CTEs into derived tables first.
    SqlNode analysisTree = new CteExpander().expand(parsed);
    SqlValidatorFactory factory = new SqlValidatorFactory(rootSchema,
        schemaPaths(rootSchema), profile.validatorConformance(),
        profile.caseSensitiveNameMatching(), profile.functionTable(),
        profile.typeCoercionFactory());
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

  @Override
  public final String unparse(SqlNode node) {
    return node.toSqlString(config -> config
        .withDialect(profile.sqlDialect())
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withSelectListItemsOnSeparateLines(false)
        .withUpdateSetListNewline(false)
        .withIndentation(0)).getSql();
  }

  @Override
  public final IdentifierPolicy identifiers() {
    return profile.identifierPolicy();
  }

  /**
   * Search paths derived from the schema tree, honoring the profile's style:
   * {@code [catalog, schema]} pairs so both fully-qualified and search-path
   * table references resolve.
   */
  private List<List<String>> schemaPaths(SchemaPlus rootSchema) {
    List<List<String>> paths = new ArrayList<>();
    for (String catalog : rootSchema.getSubSchemaNames()) {
      SchemaPlus catalogSchema = rootSchema.getSubSchema(catalog);
      for (String schema : catalogSchema.getSubSchemaNames()) {
        paths.add(List.of(catalog, schema));
        if (profile.schemaPathStyle() == DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA) {
          paths.add(List.of(catalog));
        }
      }
    }
    return paths;
  }
}
