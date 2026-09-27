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
 *       操作符（接受 date±integer、其余形态沿用标准检查与返回类型推导）。</li>
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
   */
  public static final SqlBinaryOperator PLUS = new SqlBinaryOperator(
      "+", SqlKind.PLUS, 60, true,
      binding -> isDateArithmetic(binding)
          ? dateType(binding)
          : SqlStdOperatorTable.PLUS.getReturnTypeInference().inferReturnType(binding),
      InferTypes.FIRST_KNOWN,
      delegateOrDateArithmetic(SqlStdOperatorTable.PLUS));

  /** {@code date - integer → date}，其余形态沿用标准 {@code -}。 */
  public static final SqlBinaryOperator MINUS = new SqlBinaryOperator(
      "-", SqlKind.MINUS, 60, true,
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

  /** {@code date ± integer}（或 {@code integer + date}）：要么一侧是 date 且另一侧是整数族。 */
  private static boolean isDateArithmetic(
      org.apache.calcite.sql.SqlOperatorBinding binding) {
    RelDataType left = binding.getOperandType(0);
    RelDataType right = binding.getOperandType(1);
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

  /** PG 的 date±integer 只接受整型（numeric/decimal 不行），Calcite 的 family 映射不区分，按类型名判断。 */
  private static boolean isIntegerFamily(RelDataType type) {
    return switch (type.getSqlTypeName()) {
      case TINYINT, SMALLINT, INTEGER, BIGINT -> true;
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
