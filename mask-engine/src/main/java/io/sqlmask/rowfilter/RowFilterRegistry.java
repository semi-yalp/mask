package io.sqlmask.rowfilter;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlVisitor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-rewrite cache of validated row-filter conditions, keyed by normalized
 * {@code catalog.schema.table}.
 *
 * <p>Each configured condition is checked in three steps: the dialect parses
 * it wrapped as {@code SELECT * FROM <table> WHERE <condition>}, a whitelist
 * visitor rejects every construct that would make the filter non-static or
 * non-deterministic (subqueries, function calls, dynamic parameters — the
 * validator alone would happily accept unknown UDFs and session functions),
 * and finally a validation pass catches unknown columns, type mismatches and
 * non-boolean conditions. All failures surface as {@code CONFIG_ERROR} with
 * the declaring table in the message.
 *
 * <p>The cached template is a tree the validator never touched: the
 * whitelist runs on one parse, validation consumes a second parse, so
 * downstream consumers always deep-copy an unmutated template.
 */
public final class RowFilterRegistry {

  /**
   * Expression constructs allowed in a row filter. Anything not listed here
   * is rejected: functions (known or UDF), CAST, aggregates, window
   * operators, dynamic parameters and subqueries all fall outside this set.
   */
  private static final Set<SqlKind> ALLOWED_OPERATORS = EnumSet.of(
      SqlKind.AND, SqlKind.OR, SqlKind.NOT,
      SqlKind.EQUALS, SqlKind.NOT_EQUALS,
      SqlKind.LESS_THAN, SqlKind.LESS_THAN_OR_EQUAL,
      SqlKind.GREATER_THAN, SqlKind.GREATER_THAN_OR_EQUAL,
      SqlKind.PLUS, SqlKind.MINUS, SqlKind.TIMES, SqlKind.DIVIDE, SqlKind.MOD,
      SqlKind.IS_NULL, SqlKind.IS_NOT_NULL,
      SqlKind.IS_DISTINCT_FROM, SqlKind.IS_NOT_DISTINCT_FROM,
      SqlKind.IN);

  private final Map<String, SqlNode> templates = new HashMap<>();

  private RowFilterRegistry() {
  }

  public static RowFilterRegistry build(LoadedConfig loaded, DialectAdapter dialect,
      SchemaPlus schema) {
    RowFilterRegistry registry = new RowFilterRegistry();
    for (TableMetadata table : loaded.tables()) {
      if (table.rowFilter() == null) {
        continue;
      }
      registry.register(table, dialect, schema);
    }
    return registry;
  }

  public boolean isEmpty() {
    return templates.isEmpty();
  }

  /** Unmutated condition template for a declared table; empty when absent. */
  public Optional<SqlNode> conditionTemplateOf(String catalog, String schema, String table) {
    return Optional.ofNullable(templates.get(key(catalog, schema, table)));
  }

  /** Registry-driven "controlled table" check: the single source of truth for the rewriter. */
  public boolean isControlled(String catalog, String schema, String table) {
    return templates.containsKey(key(catalog, schema, table));
  }

  /**
   * Registers the PDP's row-filter decisions for a subject: every declared
   * table is asked for hits; each hit is validated with a policy-name prefix
   * and multiple hits on one table combine into a single AND template.
   */
  public static RowFilterRegistry buildFromPolicies(java.util.List<TableMetadata> tables,
      io.sqlmask.policy.match.PolicyEngine engine, io.sqlmask.policy.model.Subject subject,
      DialectAdapter dialect, SchemaPlus schema) {
    RowFilterRegistry registry = new RowFilterRegistry();
    for (TableMetadata table : tables) {
      for (io.sqlmask.policy.model.RowFilterHit hit : engine.rowFiltersFor(
          table.catalog(), table.schema(), table.name(), subject)) {
        registry.registerCondition(table, hit.expr(),
            "policy '" + hit.policyName() + "': filterExpr", dialect, schema);
      }
    }
    return registry;
  }

  private void register(TableMetadata table, DialectAdapter dialect, SchemaPlus schema) {
    registerCondition(table, table.rowFilter(),
        "table '" + table.qualifiedName() + "': row filter", dialect, schema);
  }

  private void registerCondition(TableMetadata table, String expr, String prefix,
      DialectAdapter dialect, SchemaPlus schema) {
    String wrapped = "SELECT * FROM " + table.qualifiedName() + " WHERE " + expr;
    Set<String> columnNames = table.columns().stream()
        .map(column -> column.name().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    SqlNode parsed;
    try {
      parsed = dialect.parse(wrapped, 0);
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " cannot be parsed: " + e.getMessage(), e);
    }
    SqlNode condition = ((SqlSelect) parsed).getWhere();
    try {
      condition.accept(whitelistVisitor(columnNames));
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " " + e.getMessage(), e);
    }
    // validate a second parse so the validator's in-place mutations never
    // reach the cached template
    SqlNode validationTree;
    try {
      validationTree = dialect.parse(wrapped, 0);
      dialect.validate(validationTree, schema);
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          prefix + " is not a valid condition: " + e.getMessage(), e);
    }
    String tableKey = key(table.catalog(), table.schema(), table.name());
    SqlNode existing = templates.get(tableKey);
    templates.put(tableKey, existing == null ? condition
        : new SqlBasicCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.AND,
            java.util.List.of(existing, condition),
            org.apache.calcite.sql.parser.SqlParserPos.ZERO));
  }

  private static SqlVisitor<Void> whitelistVisitor(Set<String> columnNames) {
    return new SqlBasicVisitor<Void>() {
      @Override
      public Void visit(SqlIdentifier identifier) {
        // design promise: the condition references the filtered table's own
        // columns only — single-part names matching a declared column. This
        // also rejects session variables (CURRENT_USER, CURRENT_TIMESTAMP,
        // ...), which the parser surfaces as bare identifiers
        if (!identifier.isSimple()) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              "must reference the table's columns directly, without qualification");
        }
        String name = identifier.getSimple().toLowerCase(Locale.ROOT);
        if (!columnNames.contains(name)) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              "must only reference declared columns of the filtered table; '"
                  + identifier.getSimple() + "' is not one");
        }
        return null;
      }

      @Override
      public Void visit(SqlCall call) {
        SqlKind kind = call.getKind();
        if (kind == SqlKind.SELECT || kind == SqlKind.WITH
            || kind == SqlKind.UNION || kind == SqlKind.INTERSECT || kind == SqlKind.EXCEPT
            || kind == SqlKind.EXISTS) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              "must not contain subqueries");
        }
        if (!ALLOWED_OPERATORS.contains(kind)) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              "must only use columns, literals and basic comparisons; '"
                  + kind.lowerName + "' is not allowed");
        }
        for (SqlNode operand : call.getOperandList()) {
          if (operand != null) {
            operand.accept(this);
          }
        }
        return null;
      }

      @Override
      public Void visit(SqlDynamicParam param) {
        // the validator would accept ? / $1 and defer binding to execution
        // time — a row filter must be static, so it can never take parameters
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "must not contain dynamic parameters");
      }

      @Override
      public Void visit(SqlNodeList nodeList) {
        for (SqlNode node : nodeList) {
          node.accept(this);
        }
        return null;
      }
    };
  }

  private static String key(String catalog, String schema, String table) {
    return ColumnKey.normalize(catalog, "catalog") + "."
        + ColumnKey.normalize(schema, "schema") + "."
        + ColumnKey.normalize(table, "table");
  }
}
