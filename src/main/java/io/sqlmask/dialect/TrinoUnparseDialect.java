package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.fun.SqlBetweenOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.dialect.TrinoSqlDialect;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.util.Util;

/**
 * Trino-flavored SqlDialect used for unparsing. Calcite's
 * {@link SqlBetweenOperator} always emits its flag keyword, so a plain
 * {@code x BETWEEN lo AND hi} renders as {@code BETWEEN ASYMMETRIC ...} —
 * syntax the real Trino grammar rejects (verified against trino-parser:
 * {@code ASYMMETRIC} and {@code SYMMETRIC} are both no keywords after
 * BETWEEN). This dialect intercepts the plain (ASYMMETRIC) form and renders
 * the identical keyword structure without the flag; ASYMMETRIC is the SQL
 * default, so the semantics are unchanged.
 *
 * <p>The SYMMETRIC form (semantics Trino cannot express) keeps Calcite's
 * default rendering, which the real Trino parser then rejects loudly — it
 * must never be silently downgraded to plain BETWEEN, because that would
 * change the meaning of the masked SQL.
 */
final class TrinoUnparseDialect extends TrinoSqlDialect {

  TrinoUnparseDialect() {
    super(TrinoSqlDialect.DEFAULT_CONTEXT);
  }

  @Override
  public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    if (call.getOperator() instanceof SqlBetweenOperator between
        && between.flag == SqlBetweenOperator.Flag.ASYMMETRIC) {
      unparsePlainBetween(writer, call, between);
      return;
    }
    super.unparseCall(writer, call, leftPrec, rightPrec);
  }

  /**
   * {@link SqlBetweenOperator#unparse} minus the flag keyword: operand 0,
   * "BETWEEN", the lower bound (parenthesized when it contains a top-level
   * AND, exactly like Calcite's AndFinder guard), "AND", the upper bound.
   * The keyword must be the literal "BETWEEN": {@link SqlBetweenOperator#getName()}
   * returns "BETWEEN ASYMMETRIC" — Calcite bakes the flag into the virtual
   * operator name — so calling {@code writer.sep(between.getName())} would
   * re-emit the very keyword this dialect exists to remove.
   */
  private static void unparsePlainBetween(SqlWriter writer, SqlCall call,
      SqlBetweenOperator between) {
    SqlWriter.Frame frame = writer.startList("", "");
    call.operand(SqlBetweenOperator.VALUE_OPERAND)
        .unparse(writer, between.getLeftPrec(), 0);
    writer.sep("BETWEEN");
    SqlNode lower = call.operand(SqlBetweenOperator.LOWER_OPERAND);
    SqlNode upper = call.operand(SqlBetweenOperator.UPPER_OPERAND);
    int lowerPrec = containsAnd(lower) ? 100 : 0;
    lower.unparse(writer, lowerPrec, lowerPrec);
    writer.sep("AND");
    upper.unparse(writer, 0, between.getRightPrec());
    writer.endList(frame);
  }

  /** Mirrors Calcite's SqlBetweenOperator.AndFinder: any nested std AND call. */
  private static boolean containsAnd(SqlNode node) {
    try {
      node.accept(new SqlBasicVisitor<Void>() {
        @Override public Void visit(SqlCall call) {
          if (call.getOperator() == SqlStdOperatorTable.AND) {
            throw new Util.FoundOne(call);
          }
          return super.visit(call);
        }
      });
      return false;
    } catch (Util.FoundOne e) {
      return true;
    }
  }
}
