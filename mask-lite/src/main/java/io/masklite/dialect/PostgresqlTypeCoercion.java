package io.masklite.dialect;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.implicit.TypeCoercionImpl;

import java.math.BigDecimal;

/**
 * PostgreSQL type coercion: {@code date + integer} / {@code date - integer}
 * add or subtract days (and {@code integer + date} adds days too), while
 * Calcite's standard rules only accept {@code datetime ± interval}.
 *
 * <p>When one operand of {@code +}/{@code -} is a DATE and the other an
 * integer <em>literal</em>, the literal is rewritten in place to
 * {@code INTERVAL 'n' DAY}, which keeps PG semantics and produces SQL every
 * engine in scope can parse. Anything else (non-literal integer expressions,
 * timestamp ± integer, which PG itself rejects) stays fail-closed: the
 * standard type check rejects it exactly as before.
 */
public class PostgresqlTypeCoercion extends TypeCoercionImpl {

  public PostgresqlTypeCoercion(RelDataTypeFactory typeFactory, SqlValidator validator) {
    super(typeFactory, validator);
  }

  @Override public boolean binaryArithmeticCoercion(SqlCallBinding binding) {
    if (rewriteDateIntegerDayArithmetic(binding)) {
      return true;
    }
    return super.binaryArithmeticCoercion(binding);
  }

  /**
   * Rewrites {@code date ± <int literal>} (and {@code <int literal> + date})
   * into day-interval arithmetic. Returns false — leaving the call to the
   * standard checks — unless the rewrite actually happened.
   */
  private boolean rewriteDateIntegerDayArithmetic(SqlCallBinding binding) {
    SqlKind kind = binding.getOperator().getKind();
    if ((kind != SqlKind.PLUS && kind != SqlKind.MINUS) || binding.getOperandCount() != 2) {
      return false;
    }
    RelDataType left = binding.getOperandType(0);
    RelDataType right = binding.getOperandType(1);
    int literalIndex;
    if (isDate(left) && SqlTypeUtil.isIntType(right)) {
      literalIndex = 1;
    } else if (kind == SqlKind.PLUS && SqlTypeUtil.isIntType(left) && isDate(right)) {
      literalIndex = 0;
    } else {
      return false;
    }
    SqlNode operand = binding.operand(literalIndex);
    if (!(operand instanceof SqlLiteral literal)) {
      // no PG cast exists from a general integer expression to interval
      return false;
    }
    BigDecimal days = literal.getValueAs(BigDecimal.class);
    if (days == null || days.stripTrailingZeros().scale() > 0) {
      return false;
    }
    SqlParserPos pos = literal.getParserPosition();
    SqlIntervalQualifier dayQualifier =
        new SqlIntervalQualifier(TimeUnit.DAY, null, pos);
    SqlIntervalLiteral interval = new DayIntervalLiteral(days, dayQualifier, pos);
    binding.getCall().setOperand(literalIndex, interval);
    // the new node must carry a validated type before the operand checks re-run
    validator.deriveType(binding.getScope(), interval);
    return true;
  }

  private static boolean isDate(RelDataType type) {
    return type.getSqlTypeName() == SqlTypeName.DATE;
  }

  /** {@code INTERVAL 'n' DAY} with the sign folded into the literal text. */
  private static final class DayIntervalLiteral extends SqlIntervalLiteral {
    DayIntervalLiteral(BigDecimal days, SqlIntervalQualifier qualifier, SqlParserPos pos) {
      super(days.signum() < 0 ? -1 : 1, days.abs().toBigInteger().toString(),
          qualifier, SqlTypeName.INTERVAL_DAY, pos);
    }
  }
}
