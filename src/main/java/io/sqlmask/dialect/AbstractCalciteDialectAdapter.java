package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.sql.CteExpander;
import io.sqlmask.sql.SqlValidatorFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialect-agnostic pipeline: parse (with the profile's parser config),
 * snapshot the original text, inline CTEs, validate and convert with the
 * profile's validator settings, and unparse with the profile's SqlDialect.
 * Subclasses declare a {@link DialectProfile} and may hook CREATE TABLE
 * variant checks.
 */
public abstract class AbstractCalciteDialectAdapter implements DialectAdapter {

  protected final DialectProfile profile;

  protected AbstractCalciteDialectAdapter(DialectProfile profile) {
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
    try {
      SqlNode node = SqlParser.create(sql, profile.parserConfig()).parseStmt();
      classify(node, statementOrdinal);
      return node;
    } catch (SqlParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.PARSE_ERROR,
          "statement " + statementOrdinal + ": parse error (" + profile.name()
              + "): " + e.getMessage(), e);
    }
  }

  private void classify(SqlNode node, int statementOrdinal) {
    switch (node.getKind()) {
      case SELECT, INSERT -> {
        // accepted
      }
      case ORDER_BY -> {
        SqlNode query = ((org.apache.calcite.sql.SqlOrderBy) node).query;
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
      case CREATE_TABLE -> {
        if (((SqlCreateTable) node).query == null) {
          throw unsupported(node.getKind(), statementOrdinal);
        }
      }
      default -> throw unsupported(node.getKind(), statementOrdinal);
    }
  }

  private boolean isQuery(SqlNode node) {
    SqlKind kind = node.getKind();
    return kind == SqlKind.SELECT || kind == SqlKind.WITH || kind == SqlKind.ORDER_BY;
  }

  private SqlMaskException unsupported(SqlKind kind, int statementOrdinal) {
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
        profile.caseSensitiveNameMatching(), profile.functionTable());
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
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "converted query does not match the validated output shape");
    }
    return new ValidatedSql(parsed, originalSql, validated, root, validator);
  }

  @Override
  public final SqlNode querySourceOf(SqlNode writeStatement) {
    switch (writeStatement.getKind()) {
      case INSERT: {
        SqlNode source = ((org.apache.calcite.sql.SqlInsert) writeStatement).getSource();
        return source != null && isQuery(source) ? source : null;
      }
      case CREATE_TABLE:
        return ((SqlCreateTable) writeStatement).query;
      default:
        throw unsupported(writeStatement.getKind(), 0);
    }
  }

  @Override
  public final boolean isPassThroughWrite(SqlNode writeStatement) {
    if (writeStatement.getKind() == SqlKind.INSERT) {
      SqlNode source = ((org.apache.calcite.sql.SqlInsert) writeStatement).getSource();
      // plain literal VALUES carry no base columns; but a query hidden inside
      // VALUES (subquery in an expression) must not slip through unmasked
      return source != null && source.getKind() == SqlKind.VALUES && !containsQuery(source);
    }
    return false;
  }

  private boolean containsQuery(SqlNode node) {
    if (node == null) {
      return false;
    }
    if (node.getKind() == SqlKind.SELECT || node.getKind() == SqlKind.WITH) {
      return true;
    }
    if (node instanceof SqlCall call) {
      for (SqlNode operand : call.getOperandList()) {
        if (containsQuery(operand)) {
          return true;
        }
      }
    }
    if (node instanceof SqlNodeList list) {
      for (SqlNode item : list) {
        if (containsQuery(item)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public final String composeWriteStatement(SqlNode writeStatement, String wrappedQuery) {
    switch (writeStatement.getKind()) {
      case INSERT: {
        org.apache.calcite.sql.SqlInsert insert =
            (org.apache.calcite.sql.SqlInsert) writeStatement;
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(unparse(insert.getTargetTable()));
        sql.append(renderColumnList(insert.getTargetColumnList()));
        sql.append(' ').append(wrappedQuery);
        return sql.toString();
      }
      case CREATE_TABLE: {
        SqlCreateTable create = (SqlCreateTable) writeStatement;
        checkCreateTableVariant(writeStatement);
        StringBuilder sql = new StringBuilder("CREATE TABLE ");
        if (create.ifNotExists) {
          sql.append("IF NOT EXISTS ");
        }
        sql.append(unparse(create.name));
        sql.append(renderColumnList(create.columnList));
        sql.append(" AS ").append(wrappedQuery);
        return sql.toString();
      }
      default:
        throw unsupported(writeStatement.getKind(), 0);
    }
  }

  /** Hook for dialect-specific CREATE TABLE variant rejection (default: none). */
  protected void checkCreateTableVariant(SqlNode writeStatement) {
  }

  private String renderColumnList(SqlNodeList columnList) {
    if (columnList == null || columnList.isEmpty()) {
      return "";
    }
    StringBuilder sql = new StringBuilder(" (");
    for (int i = 0; i < columnList.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(unparse(columnList.get(i)));
    }
    return sql.append(')').toString();
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
  public final DialectCapabilities capabilities() {
    return profile.capabilities();
  }

  /**
   * Search paths derived from the schema tree, honoring the profile's style.
   * Order matters: the {@code [catalog, schema]} pair precedes the bare
   * {@code [schema]} path so fully-qualified resolution wins.
   */
  private List<List<String>> schemaPaths(SchemaPlus rootSchema) {
    List<List<String>> paths = new ArrayList<>();
    for (String catalog : rootSchema.getSubSchemaNames()) {
      SchemaPlus catalogSchema = rootSchema.getSubSchema(catalog);
      for (String schema : catalogSchema.getSubSchemaNames()) {
        paths.add(List.of(catalog, schema));
        if (profile.schemaPathStyle() == DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA) {
          paths.add(List.of(schema));
        }
      }
    }
    return paths;
  }
}
