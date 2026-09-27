package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 日期算术口径锁定。break 对应：放宽版 +/- 操作符误收 PG 拒绝的形态
 * （{@code integer - date}、{@code date ± bigint}）或误拒 PG 接受的形态
 * （{@code date ± integer}、{@code integer + date}、{@code date ± interval}）。
 */
class DialectDateArithmeticTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: date_dim
            columns:
              - { name: d_date, type: date }
              - { name: d_date_sk, type: integer }
              - { name: d_watermark, type: bigint }
              - { name: d_note, type: varchar(30) }
      policies:
        mask_note:
          udf: mask_name
          arguments: []
      columns:
        - { catalog: crm, schema: public, table: date_dim, column: d_note, policy: mask_note }
      """;

  @Test
  void datePlusIntegerRewrites() {
    assertRewrites("SELECT d_date + 5 FROM date_dim");
  }

  @Test
  void integerPlusDateRewrites() {
    assertRewrites("SELECT 5 + d_date FROM date_dim");
  }

  @Test
  void dateMinusIntegerRewrites() {
    assertRewrites("SELECT d_date - 5 FROM date_dim");
  }

  @Test
  void datePlusIntegerColumnRewrites() {
    assertRewrites("SELECT d_date + d_date_sk FROM date_dim");
  }

  /** PG 没有 {@code integer - date} 操作符：必须在校验期拒绝，而不是放行到执行期。 */
  @Test
  void integerMinusDateIsRejected() {
    assertRejected("SELECT 5 - d_date FROM date_dim");
  }

  /** PG 的 date±integer 只有 int4（含隐式提升的 int2）；bigint 无隐式转换，必须拒绝。 */
  @Test
  void datePlusBigintIsRejected() {
    assertRejected("SELECT d_date + d_watermark FROM date_dim");
  }

  @Test
  void dateMinusBigintIsRejected() {
    assertRejected("SELECT d_date - d_watermark FROM date_dim");
  }

  /** numeric/decimal 不是 PG date 算术的整数族。 */
  @Test
  void datePlusDecimalIsRejected() {
    assertRejected("SELECT d_date + 1.5 FROM date_dim");
  }

  /**
   * 语料从未覆盖 interval：date±interval 走标准委托路径，不能被放宽版误伤。
   * PG 的裸 {@code INTERVAL '1 day'} 已支持（解析期规范化，见 PgBareIntervalTest）；
   * {@code ::interval} 转换仍是已知缺口（PARSE_ERROR，fail-closed 过拒绝）。
   */
  @Test
  void datePlusIntervalRewrites() {
    assertRewrites("SELECT d_date + INTERVAL '1' DAY FROM date_dim");
  }

  @Test
  void dateMinusIntervalRewrites() {
    assertRewrites("SELECT d_date - INTERVAL '2' HOUR FROM date_dim");
  }

  private static void assertRewrites(String sql) {
    MaskLite mask = MaskLite.fromYaml(YAML);
    assertEquals(1, mask.rewriteStatements(sql).size(),
        "expected exactly one rewritten statement for: " + sql);
  }

  private static void assertRejected(String sql) {
    MaskLite mask = MaskLite.fromYaml(YAML);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewriteStatements(sql),
        "PG rejects this form, so validation must reject it too: " + sql);
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode(),
        "rejection must come from validation, got: " + e.getMessage());
    assertTrue(e.getMessage() != null, "rejection carries a diagnostic");
  }
}
