package io.sqlmask.sql;

import io.sqlmask.error.SqlMaskException;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Inlines non-recursive common table expressions into their referencing
 * queries, producing an equivalent statement with no CTE references.
 *
 * <p>Calcite converts CTE references to bare "transient" table scans whose
 * bodies never enter the relational tree, so column-origin metadata cannot
 * see through them. Expanding CTEs at the SQL level keeps the analysis tree
 * fully self-contained (every reference becomes a plain derived table) while
 * the original parse tree stays untouched for SQL output.
 *
 * <p>Scoping follows PostgreSQL: each {@code WITH} clause introduces a
 * lexical scope that shadows outer scopes, an inner {@code WITH} shadows
 * outer CTE names, and each item may reference items declared before it in
 * the same clause. A reference to the item currently being expanded (a
 * self/cycle reference through the scope chain) fails with a recursive-CTE
 * diagnostic instead of recursing forever.
 *
 * <p>The expander never mutates the input tree: subtrees are rebuilt as
 * clones with the rewritten operands, or returned unchanged when nothing
 * below them changed. Every CTE reference receives its own deep copy of the
 * expanded body, so downstream in-place processing (validation) cannot leak
 * changes between reference sites.
 */
public final class CteExpander {

  // SqlSelect operand indices per getOperandList() order
  private static final int SELECT_LIST_OPERAND = 1;
  private static final int GROUP_BY_OPERAND = 4;
  private static final int ORDER_BY_OPERAND = 8;

  // SqlJoin operand indices per getOperandList() order
  private static final int JOIN_LEFT_OPERAND = 0;
  private static final int JOIN_RIGHT_OPERAND = 3;

  /** One declared CTE: its body (already expanded) and optional column alias list. */
  private record Cte(String name, SqlNode body, List<String> columnNames) {
  }

  /**
   * One WITH clause's lexical scope. {@code inFlight} holds the name of the
   * item currently being expanded (items expand in order, so at most one is
   * in flight per scope); referencing it from anywhere inside means a cycle.
   */
  private static final class Scope {
    final Map<String, Cte> completed = new HashMap<>();
    String inFlight;
  }

  public SqlNode expand(SqlNode parsed) {
    return expand(parsed, new ArrayDeque<>());
  }

  private SqlNode expand(SqlNode node, Deque<Scope> scopes) {
    if (node == null) {
      return null;
    }
    switch (node.getKind()) {
      case WITH:
        return expandWith((SqlWith) node, scopes);
      case SELECT:
        return expandSelect((SqlSelect) node, scopes);
      default:
        if (node instanceof SqlCall call) {
          return rewriteCall(call, scopes);
        }
        return node;
    }
  }

  private SqlNode expandWith(SqlWith with, Deque<Scope> scopes) {
    Scope scope = new Scope();
    scopes.push(scope);
    try {
      for (SqlNode itemNode : with.withList) {
        SqlWithItem item = (SqlWithItem) itemNode;
        String name = item.name.getSimple();
        scope.inFlight = name;
        try {
          SqlNode expandedBody = expand(item.query, scopes);
          scope.completed.put(name, new Cte(name, expandedBody, columnNames(item)));
        } finally {
          scope.inFlight = null;
        }
      }
      return expand(with.body, scopes);
    } finally {
      scopes.pop();
    }
  }

  private List<String> columnNames(SqlWithItem item) {
    if (item.columnList == null) {
      return null;
    }
    List<String> names = new ArrayList<>();
    for (SqlNode nameNode : item.columnList) {
      names.add(((SqlIdentifier) nameNode).getSimple());
    }
    return names;
  }

  private SqlNode expandSelect(SqlSelect select, Deque<Scope> scopes) {
    SqlNode from = select.getFrom();
    SqlNode newFrom = from == null ? null : rewriteFrom(from, scopes);
    SqlNode newWhere = expand(select.getWhere(), scopes);
    SqlNodeList newSelectList = expandList(select.getSelectList(), scopes);
    SqlNode newHaving = expand(select.getHaving(), scopes);
    SqlNodeList newGroupBy = expandList(select.getGroup(), scopes);
    SqlNodeList newOrderBy = expandList(select.getOrderList(), scopes);
    if (newFrom == from && newWhere == select.getWhere()
        && newSelectList == select.getSelectList()
        && newHaving == select.getHaving()
        && newGroupBy == select.getGroup()
        && newOrderBy == select.getOrderList()) {
      return select;
    }
    SqlSelect copy = (SqlSelect) select.clone(select.getParserPosition());
    if (newFrom != from) {
      copy.setOperand(SqlSelect.FROM_OPERAND, newFrom);
    }
    if (newWhere != select.getWhere()) {
      copy.setOperand(SqlSelect.WHERE_OPERAND, newWhere);
    }
    if (newSelectList != select.getSelectList()) {
      copy.setOperand(SELECT_LIST_OPERAND, newSelectList);
    }
    if (newHaving != select.getHaving()) {
      copy.setOperand(SqlSelect.HAVING_OPERAND, newHaving);
    }
    if (newGroupBy != select.getGroup()) {
      copy.setOperand(GROUP_BY_OPERAND, newGroupBy);
    }
    if (newOrderBy != select.getOrderList()) {
      copy.setOperand(ORDER_BY_OPERAND, newOrderBy);
    }
    return copy;
  }

  private SqlNode rewriteFrom(SqlNode from, Deque<Scope> scopes) {
    switch (from.getKind()) {
      case IDENTIFIER: {
        SqlIdentifier id = (SqlIdentifier) from;
        if (!id.isSimple()) {
          return from;
        }
        Cte cte = lookup(id.getSimple(), scopes);
        return cte == null ? from : derivedTable(cte, id);
      }
      case AS: {
        // table-expression context: the aliased node is itself a FROM item,
        // so its first operand must keep receiving FROM-item treatment
        // (column aliases elsewhere are never routed here)
        SqlCall as = (SqlCall) from;
        List<SqlNode> operands = as.getOperandList();
        SqlNode newTable = rewriteFrom(operands.get(0), scopes);
        if (newTable == operands.get(0)) {
          return as;
        }
        List<SqlNode> rewritten = new ArrayList<>(operands);
        rewritten.set(0, newTable);
        return as.getOperator().createCall(
            as.getFunctionQuantifier(), as.getParserPosition(), rewritten.toArray(new SqlNode[0]));
      }
      case JOIN: {
        SqlJoin join = (SqlJoin) from;
        SqlNode newLeft = rewriteFrom(join.getLeft(), scopes);
        SqlNode newRight = rewriteFrom(join.getRight(), scopes);
        if (newLeft == join.getLeft() && newRight == join.getRight()) {
          return join;
        }
        // getOperandList() order: left, natural, joinType, right, conditionType, condition
        List<SqlNode> rewritten = new ArrayList<>(join.getOperandList());
        rewritten.set(JOIN_LEFT_OPERAND, newLeft);
        rewritten.set(JOIN_RIGHT_OPERAND, newRight);
        return join.getOperator().createCall(
            join.getFunctionQuantifier(), join.getParserPosition(),
            rewritten.toArray(new SqlNode[0]));
      }
      case WITH:
        return expandWith((SqlWith) from, scopes);
      case SELECT:
        return expandSelect((SqlSelect) from, scopes);
      default:
        if (from instanceof SqlCall call) {
          return rewriteCall(call, scopes);
        }
        return from;
    }
  }

  /**
   * Renders a CTE reference as a derived table {@code <body> AS <alias>
   * [<column aliases>]}; the alias keeps the original spelling so qualified
   * column references continue to resolve. The body is deep-copied per
   * reference so no two sites share mutable nodes.
   */
  private SqlNode derivedTable(Cte cte, SqlIdentifier reference) {
    List<SqlNode> operands = new ArrayList<>();
    operands.add(SqlNodeCopier.copy(cte.body()));
    operands.add(new SqlIdentifier(reference.getSimple(), SqlParserPos.ZERO));
    if (cte.columnNames() != null) {
      for (String column : cte.columnNames()) {
        operands.add(new SqlIdentifier(column, SqlParserPos.ZERO));
      }
    }
    return new SqlBasicCall(SqlStdOperatorTable.AS, operands, reference.getParserPosition());
  }

  /** Rewrites the operands of a generic call (joins, IN/EXISTS subqueries, ...). */
  private SqlNode rewriteCall(SqlCall call, Deque<Scope> scopes) {
    List<SqlNode> operands = call.getOperandList();
    int changedIndex = -1;
    SqlNode[] rewritten = new SqlNode[operands.size()];
    for (int i = 0; i < operands.size(); i++) {
      SqlNode operand = operands.get(i);
      SqlNode newOperand = operand == null ? null : expand(operand, scopes);
      rewritten[i] = newOperand;
      if (newOperand != operand) {
        changedIndex = i;
      }
    }
    if (changedIndex < 0) {
      return call;
    }
    // Generic copy-on-write: not every SqlCall subclass supports setOperand
    // (e.g. classes that only override parts of the operand contract), so
    // rebuild through the operator the same way Calcite's SqlShuttle does.
    return call.getOperator().createCall(
        call.getFunctionQuantifier(),
        call.getParserPosition(),
        rewritten);
  }

  private SqlNodeList expandList(SqlNodeList list, Deque<Scope> scopes) {
    if (list == null) {
      return null;
    }
    SqlNode[] rewritten = new SqlNode[list.size()];
    boolean changed = false;
    for (int i = 0; i < list.size(); i++) {
      SqlNode item = list.get(i);
      SqlNode newItem = expand(item, scopes);
      rewritten[i] = newItem;
      changed |= newItem != item;
    }
    return changed ? new SqlNodeList(Arrays.asList(rewritten), list.getParserPosition()) : list;
  }

  /**
   * Resolves a simple name against the scope stack, innermost scope first.
   * A name still being expanded in its own scope (directly or through an
   * intermediate scope) is a self/cycle reference and fails.
   */
  private Cte lookup(String name, Deque<Scope> scopes) {
    Iterator<Scope> innermostFirst = scopes.iterator();
    while (innermostFirst.hasNext()) {
      Scope scope = innermostFirst.next();
      Cte cte = scope.completed.get(name);
      if (cte != null) {
        return cte;
      }
      if (name.equals(scope.inFlight)) {
        throw recursiveCte(name);
      }
    }
    return null;
  }

  private SqlMaskException recursiveCte(String name) {
    return new SqlMaskException(SqlMaskException.Code.LINEAGE_UNKNOWN,
        "cannot trace lineage through recursive CTE '" + name
            + "'; recursive common table expressions are not supported in this version");
  }
}
