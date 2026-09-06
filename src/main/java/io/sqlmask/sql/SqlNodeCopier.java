package io.sqlmask.sql;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;

/**
 * Deep copy of a parsed subtree. Every SqlCall is rebuilt so no node is
 * shared with the source tree; leaves are duplicated through their own
 * clone. This is what isolates Calcite validator-side in-place mutations
 * between consumers of the same template — CTE reference sites and row-filter
 * injection sites alike.
 */
public final class SqlNodeCopier {

  private SqlNodeCopier() {
  }

  public static SqlNode copy(SqlNode node) {
    if (node == null) {
      return null;
    }
    if (node instanceof SqlNodeList list) {
      SqlNodeList copy = new SqlNodeList(list.getParserPosition());
      for (SqlNode item : list) {
        copy.add(copy(item));
      }
      return copy;
    }
    if (node instanceof SqlOrderBy orderBy) {
      // rebuild explicitly: the generic operator path would drop the
      // SqlOrderBy node type
      var operands = orderBy.getOperandList();
      return new SqlOrderBy(orderBy.getParserPosition(),
          copy(operands.get(0)), (SqlNodeList) copy(operands.get(1)),
          copy(operands.get(2)), copy(operands.get(3)));
    }
    if (node instanceof SqlJoin join) {
      // getOperandList() order: left, natural, joinType, right, conditionType, condition
      var operands = join.getOperandList();
      return new SqlJoin(join.getParserPosition(),
          copy(operands.get(0)), (org.apache.calcite.sql.SqlLiteral) copy(operands.get(1)),
          (org.apache.calcite.sql.SqlLiteral) copy(operands.get(2)), copy(operands.get(3)),
          (org.apache.calcite.sql.SqlLiteral) copy(operands.get(4)), copy(operands.get(5)));
    }
    if (node instanceof SqlCall call) {
      var operands = call.getOperandList();
      SqlNode[] copies = new SqlNode[operands.size()];
      for (int i = 0; i < operands.size(); i++) {
        copies[i] = copy(operands.get(i));
      }
      return call.getOperator().createCall(
          call.getFunctionQuantifier(), call.getParserPosition(), copies);
    }
    return node.clone(node.getParserPosition());
  }
}
