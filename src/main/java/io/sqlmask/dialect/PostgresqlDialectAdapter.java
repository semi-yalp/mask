package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.sql.CteExpander;
import io.sqlmask.sql.SqlValidatorFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
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
 */
public final class PostgresqlDialectAdapter implements DialectAdapter {

  public static final String NAME = "postgresql";

  private final SqlParser.Config parserConfig;

  public PostgresqlDialectAdapter() {
    this.parserConfig = SqlParser.config()
        .withParserFactory(SqlBabelParserImpl.FACTORY)
        .withQuoting(Quoting.DOUBLE_QUOTE)
        .withUnquotedCasing(Casing.TO_LOWER)
        .withQuotedCasing(Casing.UNCHANGED)
        .withCaseSensitive(true)
        .withConformance(SqlConformanceEnum.DEFAULT);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public SqlNode parse(String sql, int statementOrdinal) {
    try {
      SqlNode node = SqlParser.create(sql, parserConfig).parseStmt();
      classify(node, statementOrdinal);
      return node;
    } catch (SqlParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.PARSE_ERROR,
          "statement " + statementOrdinal + ": parse error: " + e.getMessage(), e);
    }
  }

  private void classify(SqlNode node, int statementOrdinal) {
    SqlKind kind = node.getKind();
    switch (kind) {
      case SELECT -> {
        // accepted
      }
      case ORDER_BY -> {
        // top-level ORDER BY / LIMIT wraps the query (SqlOrderBy)
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
      case INSERT -> {
        // accepted: rows come from the source query/VALUES
      }
      case CREATE_TABLE -> {
        // only CREATE TABLE ... AS <query>; plain DDL stays unsupported
        if (((org.apache.calcite.sql.ddl.SqlCreateTable) node).query == null) {
          throw unsupported(kind, statementOrdinal);
        }
      }
      default -> throw unsupported(kind, statementOrdinal);
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
  public ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema) {
    // Calcite's validator mutates the parse tree in place, so snapshot the
    // original SQL text before anything touches the tree; this snapshot is
    // the inner query of a generated wrapper.
    String originalSql = unparse(parsed);
    // Calcite keeps CTE bodies out of the relational tree (transient scans),
    // so the analysis tree inlines CTEs into derived tables first.
    SqlNode analysisTree = new CteExpander().expand(parsed);
    SqlValidatorFactory factory = new SqlValidatorFactory(rootSchema, schemaPaths(rootSchema));
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
    root = alignWithRowType(root);
    return new ValidatedSql(parsed, originalSql, validated, root, validator);
  }

  /**
   * Guards that the converted root's field count matches the validated
   * output row type so lineage can map output ordinals positionally.
   */
  private RelRoot alignWithRowType(RelRoot root) {
    if (root.rel.getRowType().getFieldCount() != root.validatedRowType.getFieldCount()) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "converted query does not match the validated output shape");
    }
    return root;
  }

  @Override
  public SqlNode querySourceOf(SqlNode writeStatement) {
    switch (writeStatement.getKind()) {
      case INSERT: {
        SqlNode source = ((org.apache.calcite.sql.SqlInsert) writeStatement).getSource();
        return source != null && isQuery(source) ? source : null;
      }
      case CREATE_TABLE:
        return ((org.apache.calcite.sql.ddl.SqlCreateTable) writeStatement).query;
      default:
        throw unsupported(writeStatement.getKind(), 0);
    }
  }

  @Override
  public boolean isPassThroughWrite(SqlNode writeStatement) {
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
  public String composeWriteStatement(SqlNode writeStatement, String wrappedQuery) {
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
        org.apache.calcite.sql.ddl.SqlCreateTable create =
            (org.apache.calcite.sql.ddl.SqlCreateTable) writeStatement;
        requirePlainCreateTable(writeStatement);
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

  /** Refuses babel-only CREATE TABLE variants whose syntax the composer cannot reproduce. */
  private void requirePlainCreateTable(SqlNode writeStatement) {
    if (!(writeStatement instanceof org.apache.calcite.sql.babel.SqlBabelCreateTable babel)) {
      return;
    }
    List<SqlNode> operands = babel.getOperandList();
    boolean replace = ((org.apache.calcite.sql.SqlLiteral) operands.get(0)).booleanValue();
    // UNSPECIFIED/null means the plain default
    org.apache.calcite.sql.babel.TableCollectionType collectionType =
        ((org.apache.calcite.sql.SqlLiteral) operands.get(1))
            .symbolValue(org.apache.calcite.sql.babel.TableCollectionType.class);
    boolean volatileTable = ((org.apache.calcite.sql.SqlLiteral) operands.get(2)).booleanValue();
    if (replace || volatileTable
        || collectionType == org.apache.calcite.sql.babel.TableCollectionType.MULTISET) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "unsupported CREATE TABLE variant (REPLACE / VOLATILE / SET / MULTISET); "
              + "only plain CREATE TABLE [IF NOT EXISTS] ... AS SELECT is supported");
    }
  }

  private String renderColumnList(org.apache.calcite.sql.SqlNodeList columnList) {
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
  public String unparse(SqlNode node) {
    return node.toSqlString(config -> config
        .withDialect(PostgresqlSqlDialect.DEFAULT)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withSelectListItemsOnSeparateLines(false)
        .withUpdateSetListNewline(false)
        .withIndentation(0)).getSql();
  }

  @Override
  public DialectCapabilities capabilities() {
    return DialectCapabilities.POSTGRESQL;
  }

  /**
   * Derives default search paths {@code [catalog, schema]} from the schema
   * tree so unqualified table references resolve when unique.
   */
  static List<List<String>> schemaPaths(SchemaPlus rootSchema) {
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
