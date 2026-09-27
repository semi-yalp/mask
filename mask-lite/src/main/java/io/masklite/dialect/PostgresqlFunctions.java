package io.masklite.dialect;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlBasicFunction;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;

import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;

import java.util.List;

/**
 * PostgreSQL-flavored operator table: Calcite's PostgreSQL library functions
 * plus common PostgreSQL scalar functions with semantics the libraries get
 * wrong or miss.
 *
 * <p>Routine names resolve case-insensitively (as PostgreSQL does) even
 * though the table/column matcher of the session is case-sensitive: with
 * {@code unquotedCasing=TO_LOWER} the parser hands routine names over in
 * lower case while Calcite registers them in upper case.
 *
 * <p>两条方言补丁（TPC-DS 全量语料的实证缺口）：
 *
 * <ul>
 *   <li>{@code concat}：PG library 自带的 CONCAT 在 UNION 强制推导的严格
 *       路径下拒绝 char 参数（双候选歧义），这里从 library 列表剔除、仅保留
 *       本方言语义的可变参定义（PG 的 {@code concat} 对任意字符串类型成立）。</li>
 *   <li>{@code date ± integer}：PG 以整数天进退日期，Calcite 标准只认
 *       datetime + interval。parse 后把 {@code +} / {@code -} 重绑定到放宽版
 *       操作符：PLUS 认 {@code date±int} 两侧、MINUS 只认 date 在左，
 *       整数族止步 int4（PG 口径）；其余形态沿用标准检查与返回类型推导。</li>
 * </ul>
 */
public final class PostgresqlFunctions {

  /** {@code concat(arg, ...)}: PostgreSQL concatenates strings. */
  public static final SqlFunction CONCAT = SqlBasicFunction.create("CONCAT",
      ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
      OperandTypes.repeat(SqlOperandCountRanges.from(1), OperandTypes.STRING),
      SqlFunctionCategory.STRING)
      .withOperandTypeInference(InferTypes.RETURN_TYPE);

  /**
   * PG library operators minus {@code CONCAT}: our vararg definition above is
   * the single CONCAT candidate, so the strict re-derivation path of union
   * type coercion cannot pick the library's narrower checker and fail on
   * {@code char} arguments.
   */
  private static final List<SqlOperator> POSTGRESQL_LIBRARY =
      SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(SqlLibrary.POSTGRESQL)
          .getOperatorList().stream()
          .filter(operator -> !operator.getName().equalsIgnoreCase("CONCAT"))
          .toList();

  /** PG 日期算术：{@code date ± integer} 与 {@code integer + date}。 */
  private static final org.apache.calcite.sql.type.SqlOperandTypeChecker DATE_ARITHMETIC =
      new org.apache.calcite.sql.type.SqlOperandTypeChecker() {
        @Override
        public boolean checkOperandTypes(
            org.apache.calcite.sql.SqlCallBinding binding, boolean throwOnFailure) {
          return isDateArithmetic(binding);
        }

        @Override
        public org.apache.calcite.sql.SqlOperandCountRange getOperandCountRange() {
          return org.apache.calcite.sql.type.SqlOperandCountRanges.of(2);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
          return "'" + opName + "(<DATE>, <INTEGER>)'"
              + ", '" + opName + "(<INTEGER>, <DATE>)'";
        }
      };

  /**
   * {@code date + integer → date}（整数进位天数；{@code integer + date} 同），
   * 其余形态沿用标准 {@code +} 的检查与返回类型推导。
   *
   * <p>precedence 必须与 std 的 +/- 一致（40）：校验期 deriveType 会把调用上的
   * 操作符替换成本操作符，输出 unparse 用它决定加括号——声称比 std 更高的
   * 优先级会在 {@code (date + n) * m} 一类形状下丢括号。rel 层注记：
   * {@code StandardConvertletTable} 按操作符<b>实例</b>注册 convertlet，重绑定后
   * {@code date + interval} 不再走 {@code DATETIME_PLUS} 特判而保持普通 RexCall；
   * 本模块只消费血缘，不受影响——未来若引入 rel 树消费者须重估此处。
   */
  public static final SqlBinaryOperator PLUS = new SqlBinaryOperator(
      "+", SqlKind.PLUS, 40, true,
      binding -> isDateArithmetic(binding)
          ? dateType(binding)
          : SqlStdOperatorTable.PLUS.getReturnTypeInference().inferReturnType(binding),
      InferTypes.FIRST_KNOWN,
      delegateOrDateArithmetic(SqlStdOperatorTable.PLUS));

  /**
   * {@code date - integer → date}，其余形态沿用标准 {@code -}。
   *
   * <p>PG 的 MINUS 侧没有 {@code integer - date} 操作符（只有
   * {@code date - date} 与 {@code date - integer}），方向必须收紧。
   */
  public static final SqlBinaryOperator MINUS = new SqlBinaryOperator(
      "-", SqlKind.MINUS, 40, true,
      binding -> isDateArithmetic(binding)
          ? dateType(binding)
          : SqlStdOperatorTable.MINUS.getReturnTypeInference().inferReturnType(binding),
      InferTypes.FIRST_KNOWN,
      delegateOrDateArithmetic(SqlStdOperatorTable.MINUS));

  /**
   * 组合检查器：date±integer 命中 PG 语义即通过；否则回落标准操作符的检查
   * （错误报告由 OR 组合器合并两种签名）。
   */
  private static org.apache.calcite.sql.type.SqlOperandTypeChecker delegateOrDateArithmetic(
      SqlBinaryOperator standard) {
    return DATE_ARITHMETIC.or(standard.getOperandTypeChecker());
  }

  /**
   * PG 口径的 date 算术：PLUS 两侧均可（{@code date + integer} 与
   * {@code integer + date}）；MINUS 只认 date 在左（PG 无
   * {@code integer - date} 操作符，放行只会把错误推迟到执行期）。
   */
  private static boolean isDateArithmetic(
      org.apache.calcite.sql.SqlOperatorBinding binding) {
    RelDataType left = binding.getOperandType(0);
    RelDataType right = binding.getOperandType(1);
    if (binding.getOperator().getKind() == SqlKind.MINUS) {
      return isDate(left) && isIntegerFamily(right);
    }
    return isDate(left) && isIntegerFamily(right)
        || isIntegerFamily(left) && isDate(right);
  }

  private static RelDataType dateType(
      org.apache.calcite.sql.SqlOperatorBinding binding) {
    RelDataTypeFactory typeFactory = binding.getTypeFactory();
    RelDataType date = typeFactory.createSqlType(SqlTypeName.DATE);
    boolean nullable = binding.getOperandType(0).isNullable()
        || binding.getOperandType(1).isNullable();
    return typeFactory.createTypeWithNullability(date, nullable);
  }

  private static boolean isDate(RelDataType type) {
    return type.getSqlTypeName() == SqlTypeName.DATE;
  }

  /**
   * PG 的 date±integer 只认 int4（及隐式提升到 int4 的 int1/int2）；bigint 对
   * integer 没有隐式转换，PG 直接报无此操作符——校验期同步拒绝。
   */
  private static boolean isIntegerFamily(RelDataType type) {
    return switch (type.getSqlTypeName()) {
      case TINYINT, SMALLINT, INTEGER -> true;
      default -> false;
    };
  }

  /**
   * 操作符表：放宽版 +/- 必须排在 std 之前——deriveType 按名字重新解析并
   * 替换调用上的操作符，且 BINARY 语法不做类型过滤、取链序第一个候选；
   * PG 版对 numeric 形态委托 std 的检查与返回类型推导（语义不变），
   * date±integer 只有它能匹配。
   */
  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      CaseInsensitiveOperatorTable.of(List.of(PLUS, MINUS)),
      SqlStdOperatorTable.instance(),
      CaseInsensitiveOperatorTable.of(POSTGRESQL_LIBRARY),
      CaseInsensitiveOperatorTable.of(List.of(CONCAT)));

  private PostgresqlFunctions() {
  }
}
