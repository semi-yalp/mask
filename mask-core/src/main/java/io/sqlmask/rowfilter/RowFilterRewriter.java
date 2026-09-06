package io.sqlmask.rowfilter;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injects row-filter conditions into a parsed statement before validation:
 * every FROM reference that resolves to a declared table with a configured
 * row filter is replaced by a derived table
 * {@code (SELECT * FROM <table> WHERE <condition>) AS <alias>}.
 *
 * <p>Traversal boundaries mirror SQL scoping: queries (SELECT bodies, WITH
 * clauses, set-operation branches, expression-level subqueries) are rewritten
 * recursively, FROM items are rewritten with table-reference semantics, and
 * everything else is walked as an expression that may contain subqueries. A
 * newly injected derived table is never re-entered — its own FROM stays the
 * original reference.
 *
 * <p>Name resolution is stricter than the validator's: a CTE name visible in
 * the current lexical scope always wins over a same-named base table, and an
 * unqualified name matching several declared tables fails outright instead
 * of silently binding whichever candidate the catalog reader finds first
 * (the validator resolves search paths first-match, so deferring the
 * decision could leave a filtered table unfiltered). Two-part names are not
 * matched at all: they do not resolve against the declared
 * {@code catalog.schema} paths today, and injecting for a reference that
 * fails validation anyway would invent behavior.
 *
 * <p>The rewriter never mutates the input tree; unchanged subtrees are
 * returned by reference, so statements without any hit keep their identity.
 */
public final class RowFilterRewriter {

  /** Rewritten node plus the number of injected filter conditions. */
  public record Result(SqlNode node, int injections) {
  }

  private final DialectAdapter dialect;

  public RowFilterRewriter(DialectAdapter dialect) {
    this.dialect = dialect;
  }

  public Result apply(SqlNode parsed, LoadedConfig loaded, RowFilterRegistry registry) {
    if (registry.isEmpty()) {
      return new Result(parsed, 0);
    }
    Context context = new Context(loaded, registry);
    SqlNode rewritten = rewriteQuery(parsed, context, new ArrayDeque<>());
    if (context.injections > 0) {
      rejectQualifiedColumnReferences(parsed, context);
    }
    return new Result(rewritten, context.injections);
  }

  /**
   * An injected derived table only exposes its alias, so fully qualified
   * column references {@code catalog.schema.table.column} of an injected
   * table no longer bind. The rewrite is refused instead of producing SQL
   * that fails validation with a confusing diagnostic (or, worse, re-binds).
   */
  private static void rejectQualifiedColumnReferences(SqlNode parsed, Context context) {
    for (TableMetadata table : context.loaded.tables()) {
      if (!context.registry.isControlled(table.catalog(), table.schema(), table.name())) {
        continue;
      }
      if (hasQualifiedColumnReference(parsed, table)) {
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "statement references filtered table '" + table.qualifiedName()
                + "' with fully qualified columns, which an injected row filter "
                + "would break; alias the table and qualify columns with it instead");
      }
    }
  }

  /** Matches four-part identifiers {@code catalog.schema.table.column}. */
  private static boolean hasQualifiedColumnReference(SqlNode node, TableMetadata table) {
    if (node == null) {
      return false;
    }
    if (node instanceof SqlIdentifier identifier && identifier.names.size() == 4
        && nameMatches(identifier.names.get(0), table.catalog())
        && nameMatches(identifier.names.get(1), table.schema())
        && nameMatches(identifier.names.get(2), table.name())) {
      return true;
    }
    if (node instanceof SqlNodeList list) {
      for (SqlNode item : list) {
        if (hasQualifiedColumnReference(item, table)) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof SqlCall call) {
      for (SqlNode operand : call.getOperandList()) {
        if (hasQualifiedColumnReference(operand, table)) {
          return true;
        }
      }
    }
    return false;
  }

  private static final class Context {
    final LoadedConfig loaded;
    final RowFilterRegistry registry;
    int injections;

    Context(LoadedConfig loaded, RowFilterRegistry registry) {
      this.loaded = loaded;
      this.registry = registry;
    }
  }

  /** Query-position nodes: SELECT, WITH, set operations, ORDER BY wrappers. */
  private SqlNode rewriteQuery(SqlNode node, Context context, Deque<Set<String>> cteScopes) {
    if (node == null) {
      return null;
    }
    switch (node.getKind()) {
      case SELECT:
        return rewriteSelect((SqlSelect) node, context, cteScopes);
      case WITH:
        return rewriteWith((SqlWith) node, context, cteScopes);
      case ORDER_BY: {
        SqlCall orderBy = (SqlCall) node;
        // operands: query, orderList, offset, fetch
        List<SqlNode> operands = orderBy.getOperandList();
        SqlNode newQuery = rewriteQuery(operands.get(0), context, cteScopes);
        SqlNode newOrderList = rewriteExpression(operands.get(1), context, cteScopes);
        SqlNode newOffset = rewriteExpression(operands.get(2), context, cteScopes);
        SqlNode newFetch = rewriteExpression(operands.get(3), context, cteScopes);
        if (newQuery == operands.get(0) && newOrderList == operands.get(1)
            && newOffset == operands.get(2) && newFetch == operands.get(3)) {
          return orderBy;
        }
        // build the wrapper as a concrete SqlOrderBy the way SqlNodeCopier
        // does; never depend on a private anonymous-class operator override
        return new SqlOrderBy(orderBy.getParserPosition(), newQuery,
            (SqlNodeList) newOrderList, newOffset, newFetch);
      }
      case UNION:
      case INTERSECT:
      case EXCEPT:
        return rewriteOperands((SqlCall) node, operand ->
            rewriteQuery(operand, context, cteScopes));
      default:
        // the pipeline only routes read statements here (write statements
        // contribute their source query); anything unexpected passes through
        // and faces the validator unchanged
        return node;
    }
  }

  private SqlNode rewriteWith(SqlWith with, Context context, Deque<Set<String>> cteScopes) {
    Set<String> scope = new HashSet<>();
    cteScopes.push(scope);
    try {
      List<SqlNode> newItems = new ArrayList<>();
      boolean itemsChanged = false;
      for (SqlNode itemNode : with.withList) {
        SqlWithItem item = (SqlWithItem) itemNode;
        SqlNode newQuery = rewriteQuery(item.query, context, cteScopes);
        // register each name only AFTER its own body is rewritten: PostgreSQL
        // and Calcite both bind a forward reference to the base table
        // (non-recursive WITH items only see earlier siblings), so
        // register-after-rewrite makes the rewriter match and filtered tables
        // are injected, not skipped
        scope.add(item.name.getSimple());
        if (newQuery != item.query) {
          newItems.add(new SqlWithItem(item.getParserPosition(), item.name, item.columnList,
              newQuery, item.recursive));
          itemsChanged = true;
        } else {
          newItems.add(itemNode);
        }
      }
      SqlNode newBody = rewriteQuery(with.body, context, cteScopes);
      if (!itemsChanged && newBody == with.body) {
        return with;
      }
      return new SqlWith(with.getParserPosition(),
          new SqlNodeList(newItems, with.withList.getParserPosition()), newBody);
    } finally {
      cteScopes.pop();
    }
  }

  private SqlNode rewriteSelect(SqlSelect select, Context context, Deque<Set<String>> cteScopes) {
    List<SqlNode> operands = select.getOperandList();
    SqlNode[] rewritten = new SqlNode[operands.size()];
    boolean changed = false;
    for (int i = 0; i < operands.size(); i++) {
      SqlNode operand = operands.get(i);
      SqlNode newOperand;
      if (i == SqlSelect.FROM_OPERAND) {
        newOperand = operand == null ? null : rewriteFromItem(operand, context, cteScopes);
      } else {
        newOperand = rewriteExpression(operand, context, cteScopes);
      }
      rewritten[i] = newOperand;
      changed |= newOperand != operand;
    }
    return changed
        ? select.getOperator().createCall(
            select.getFunctionQuantifier(), select.getParserPosition(), rewritten)
        : select;
  }

  /** FROM-item nodes: table references, aliases, joins, derived queries. */
  private SqlNode rewriteFromItem(SqlNode from, Context context, Deque<Set<String>> cteScopes) {
    switch (from.getKind()) {
      case IDENTIFIER:
        return rewriteTableReference((SqlIdentifier) from, context, cteScopes);
      case AS: {
        // operands: table expression, alias, optional column alias list —
        // replace only the table operand so the alias (and any column alias
        // list) survive untouched; the derived table must not grow its own
        // duplicate AS
        SqlCall as = (SqlCall) from;
        List<SqlNode> operands = as.getOperandList();
        SqlNode newTable = operands.get(0) instanceof SqlIdentifier reference
            ? resolveTableReference(reference, context, cteScopes, false)
            : rewriteFromItem(operands.get(0), context, cteScopes);
        if (newTable == operands.get(0)) {
          return as;
        }
        List<SqlNode> rewritten = new ArrayList<>(operands);
        rewritten.set(0, newTable);
        return as.getOperator().createCall(
            as.getFunctionQuantifier(), as.getParserPosition(), rewritten.toArray(new SqlNode[0]));
      }
      case JOIN: {
        SqlCall join = (SqlCall) from;
        // operands: left, natural, joinType, right, conditionType, condition
        List<SqlNode> operands = join.getOperandList();
        SqlNode newLeft = rewriteFromItem(operands.get(0), context, cteScopes);
        SqlNode newRight = rewriteFromItem(operands.get(3), context, cteScopes);
        SqlNode newCondition = rewriteExpression(operands.get(5), context, cteScopes);
        if (newLeft == operands.get(0) && newRight == operands.get(3)
            && newCondition == operands.get(5)) {
          return join;
        }
        List<SqlNode> rewritten = new ArrayList<>(operands);
        rewritten.set(0, newLeft);
        rewritten.set(3, newRight);
        rewritten.set(5, newCondition);
        return join.getOperator().createCall(
            join.getFunctionQuantifier(), join.getParserPosition(), rewritten.toArray(new SqlNode[0]));
      }
      case SELECT:
      case WITH:
        return rewriteQuery(from, context, cteScopes);
      case UNION:
      case INTERSECT:
      case EXCEPT:
        // a parenthesized set operation as a FROM item; its branches are
        // queries and keep receiving query treatment
        return rewriteOperands((SqlCall) from, operand ->
            rewriteQuery(operand, context, cteScopes));
      default:
        return failClosedOnUnknownFrom(from, context);
    }
  }

  /**
   * FROM shapes outside the supported whitelist must not silently pass a
   * filtered table through: if the subtree mentions any table whose row
   * filter would apply, refuse the statement; only provably unrelated FROM
   * items (UNNEST over literals, ...) keep their identity.
   */
  private SqlNode failClosedOnUnknownFrom(SqlNode from, Context context) {
    for (TableMetadata table : context.loaded.tables()) {
      if (!context.registry.isControlled(table.catalog(), table.schema(), table.name())) {
        continue;
      }
      if (subtreeMentionsTable(from, table)) {
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "unsupported FROM clause shape " + from.getKind() + " involving filtered table '"
                + table.qualifiedName() + "'; the row filter cannot be injected safely");
      }
    }
    return from;
  }

  private static boolean subtreeMentionsTable(SqlNode node, TableMetadata table) {
    if (node == null) {
      return false;
    }
    if (node instanceof SqlIdentifier identifier) {
      List<String> names = identifier.names;
      if (names.size() == 1 && nameMatches(names.get(0), table.name())) {
        return true;
      }
      return names.size() == 3
          && nameMatches(names.get(0), table.catalog())
          && nameMatches(names.get(1), table.schema())
          && nameMatches(names.get(2), table.name());
    }
    if (node instanceof SqlNodeList list) {
      for (SqlNode item : list) {
        if (subtreeMentionsTable(item, table)) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof SqlCall call) {
      for (SqlNode operand : call.getOperandList()) {
        if (subtreeMentionsTable(operand, table)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Expression-position walk: descends into operands, entering subqueries. */
  private SqlNode rewriteExpression(SqlNode node, Context context, Deque<Set<String>> cteScopes) {
    if (node == null || !(node instanceof SqlCall call)) {
      return node instanceof SqlNodeList list ? rewriteList(list, context, cteScopes) : node;
    }
    switch (call.getKind()) {
      case SELECT:
      case WITH:
        return rewriteQuery(call, context, cteScopes);
      default:
        return rewriteOperands(call, operand -> rewriteExpression(operand, context, cteScopes));
    }
  }

  private SqlNodeList rewriteList(SqlNodeList list, Context context, Deque<Set<String>> cteScopes) {
    SqlNode[] rewritten = new SqlNode[list.size()];
    boolean changed = false;
    for (int i = 0; i < list.size(); i++) {
      SqlNode newItem = rewriteExpression(list.get(i), context, cteScopes);
      rewritten[i] = newItem;
      changed |= newItem != list.get(i);
    }
    return changed ? new SqlNodeList(java.util.Arrays.asList(rewritten), list.getParserPosition())
        : list;
  }

  private interface OperandRewriter {
    SqlNode rewrite(SqlNode operand);
  }

  private SqlNode rewriteOperands(SqlCall call, OperandRewriter rewriter) {
    List<SqlNode> operands = call.getOperandList();
    SqlNode[] rewritten = new SqlNode[operands.size()];
    boolean changed = false;
    for (int i = 0; i < operands.size(); i++) {
      SqlNode operand = operands.get(i);
      SqlNode newOperand = operand == null ? null : rewriter.rewrite(operand);
      rewritten[i] = newOperand;
      changed |= newOperand != operand;
    }
    return changed
        ? call.getOperator().createCall(
            call.getFunctionQuantifier(), call.getParserPosition(), rewritten)
        : call;
  }

  private SqlNode rewriteTableReference(SqlIdentifier reference, Context context,
      Deque<Set<String>> cteScopes) {
    return resolveTableReference(reference, context, cteScopes, true);
  }

  /**
   * Resolves one table reference and, on a hit, returns the injected derived
   * table — wrapped in the reference's alias when the caller needs one
   * (bare reference); aliased references keep their own AS, so they receive
   * the bare derived table.
   */
  private SqlNode resolveTableReference(SqlIdentifier reference, Context context,
      Deque<Set<String>> cteScopes, boolean addAliasWrapper) {
    List<String> names = reference.names;
    if (names.size() == 1) {
      if (isVisibleCte(names.get(0), cteScopes)) {
        return reference;
      }
      List<TableMetadata> candidates = new ArrayList<>();
      for (TableMetadata table : context.loaded.tables()) {
        if (nameMatches(names.get(0), table.name())) {
          candidates.add(table);
        }
      }
      if (candidates.isEmpty()) {
        return reference;
      }
      if (candidates.size() > 1) {
        List<String> qualified = candidates.stream().map(TableMetadata::qualifiedName).sorted().toList();
        throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
            "unqualified table reference '" + names.get(0)
                + "' matches multiple declared tables " + qualified
                + "; qualify the reference so the row filter decision is unambiguous");
      }
      return injectIfFiltered(candidates.get(0), reference, context, addAliasWrapper);
    }
    if (names.size() == 3) {
      for (TableMetadata table : context.loaded.tables()) {
        if (nameMatches(names.get(0), table.catalog())
            && nameMatches(names.get(1), table.schema())
            && nameMatches(names.get(2), table.name())) {
          return injectIfFiltered(table, reference, context, addAliasWrapper);
        }
      }
    }
    // two-part names do not resolve against the declared catalog.schema
    // paths today; leave them for the validator to reject as-is
    return reference;
  }

  private static boolean isVisibleCte(String name, Deque<Set<String>> cteScopes) {
    for (Set<String> scope : cteScopes) {
      if (scope.contains(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * PostgreSQL identifier semantics: unquoted references were folded to lower
   * case at parse time, so a reference spelling that is not all-lowercase
   * was quoted and must match the declaration exactly (case-sensitive) — a
   * quoted {@code "Customer"} is a different table from declared
   * {@code customer}.
   *
   * <p>Strictness consequence for row filters: they are only usable on
   * lowercase-declared (or exactly lowercase-spelled) table names — a
   * non-lowercase declared name cannot carry a filter, because the
   * registry's own wrapped parse folds unquoted names to lower case and such
   * a table therefore fails registry build before any reference comparison.
   */
  private static boolean nameMatches(String reference, String declared) {
    return reference.equals(declared);
  }

  private SqlNode injectIfFiltered(TableMetadata table, SqlIdentifier reference, Context context,
      boolean addAliasWrapper) {
    // the registry is the single source of truth for "controlled": in the
    // new-format path the table itself never carries a rowFilter field
    SqlNode template = context.registry.conditionTemplateOf(
        table.catalog(), table.schema(), table.name()).orElse(null);
    if (template == null) {
      return reference;
    }
    // rebuild from text: the fresh parse owns every node, so injection sites
    // never share subtrees with each other or with the original statement
    String derived = "SELECT * FROM " + dialect.unparse(reference)
        + " WHERE " + dialect.unparse(template);
    SqlNode parsed;
    try {
      parsed = dialect.parse(derived, 0);
    } catch (SqlMaskException e) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "cannot build a filtered derived table for '" + table.qualifiedName() + "': "
              + e.getMessage(), e);
    }
    context.injections++;
    return addAliasWrapper ? wrapWithAlias(parsed, reference) : parsed;
  }

  /**
   * Wraps the derived table in the alias the original reference exposes:
   * the reference's own spelling (last segment) so qualified column
   * references keep resolving against the same name.
   */
  private static SqlNode wrapWithAlias(SqlNode derivedTable, SqlIdentifier reference) {
    String alias = reference.names.get(reference.names.size() - 1);
    return new SqlBasicCall(org.apache.calcite.sql.fun.SqlStdOperatorTable.AS,
        List.of(derivedTable, new SqlIdentifier(alias, reference.getParserPosition())),
        reference.getParserPosition());
  }
}
