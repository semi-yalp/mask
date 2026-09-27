package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 裸 INTERVAL 字面量（{@code INTERVAL '1 day'}，无限定词）。break 对应：
 * 裸形式不能解析（原样拒绝），或规范化产物与原值不等（日期算术错值）。
 *
 * <p>实现口径：解析期把裸形式规范化为真实 Calcite interval 字面量——
 * 输出为限定词形式，值与原 PG 语义等价（如 {@code '1 day'} →
 * {@code INTERVAL '1' DAY}）。跨族混合（年月 + 日时）、分数月等
 * Calcite 无法表达的形态保持 fail-closed 拒绝。
 */
class PgBareIntervalTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: date_dim
            columns:
              - { name: d_date, type: date }
              - { name: d_date_sk, type: integer }
      policies:
        mask_note:
          udf: mask_name
          arguments: []
      columns:
        - { catalog: crm, schema: public, table: date_dim, column: d_date_sk, policy: mask_note }
      """;

  @Test
  void bareDayRewritesAsQualifiedDay() {
    assertNormalized("SELECT d_date + INTERVAL '1 day' FROM date_dim",
        "INTERVAL '1' DAY");
  }

  @Test
  void bareHoursNormalizeToDayToSecond() {
    assertNormalized("SELECT d_date + INTERVAL '2 hours' FROM date_dim",
        "INTERVAL '0 02:00:00' DAY TO SECOND");
  }

  @Test
  void bareHourAndMinutesAccumulate() {
    assertNormalized("SELECT d_date + INTERVAL '2 hours 30 minutes' FROM date_dim",
        "INTERVAL '0 02:30:00' DAY TO SECOND");
  }

  @Test
  void bareYearAndMonthsNormalizeToYearToMonth() {
    assertNormalized("SELECT d_date + INTERVAL '1 year 2 mons' FROM date_dim",
        "INTERVAL '1-2' YEAR TO MONTH");
  }

  @Test
  void bareMonthsNormalizeToMonth() {
    assertNormalized("SELECT d_date + INTERVAL '6 months' FROM date_dim",
        "INTERVAL '6' MONTH");
  }

  @Test
  void bareYearsNormalizeToYear() {
    assertNormalized("SELECT d_date + INTERVAL '2 years' FROM date_dim",
        "INTERVAL '2' YEAR");
  }

  @Test
  void bareTimeFormWithDayCount() {
    assertNormalized("SELECT d_date + INTERVAL '1 02:03:04' FROM date_dim",
        "INTERVAL '1 02:03:04' DAY TO SECOND");
  }

  @Test
  void bareClockTimeForm() {
    assertNormalized("SELECT d_date + INTERVAL '02:03' FROM date_dim",
        "INTERVAL '0 02:03:00' DAY TO SECOND");
  }

  @Test
  void bareFractionalSecondsNormalize() {
    assertNormalized("SELECT d_date + INTERVAL '1.5 seconds' FROM date_dim",
        "INTERVAL '0 00:00:01.500000' DAY TO SECOND");
  }

  @Test
  void bareMillisecondsNormalize() {
    assertNormalized("SELECT d_date + INTERVAL '500 milliseconds' FROM date_dim",
        "INTERVAL '0 00:00:00.500000' DAY TO SECOND");
  }

  @Test
  void bareNegativeDayFoldsSignInsideString() {
    assertNormalized("SELECT d_date + INTERVAL '-1 day' FROM date_dim",
        "INTERVAL '-1' DAY");
  }

  @Test
  void bareMixedSignComponentsAccumulateToNetValue() {
    // PG: 1 day - 2 hours = 22 hours
    assertNormalized("SELECT d_date + INTERVAL '1 day -2 hours' FROM date_dim",
        "INTERVAL '0 22:00:00' DAY TO SECOND");
  }

  @Test
  void bareWeeksNormalizeToDays() {
    assertNormalized("SELECT d_date + INTERVAL '2 weeks' FROM date_dim",
        "INTERVAL '14' DAY");
  }

  @Test
  void largeDayCountsGetExplicitLeadPrecision() {
    assertNormalized("SELECT d_date + INTERVAL '400 days' FROM date_dim",
        "INTERVAL '400' DAY(3)");
  }

  /** 跨族混合：PG 可表达（1 年 1 天），Calcite 类型系统不能——保持 fail-closed。 */
  @Test
  void mixedYearMonthAndDayTimeFamiliesAreRejected() {
    assertRejected("SELECT d_date + INTERVAL '1 year 1 day' FROM date_dim");
  }

  @Test
  void fractionalMonthsAreRejected() {
    assertRejected("SELECT d_date + INTERVAL '1.5 months' FROM date_dim");
  }

  @Test
  void garbageIsRejected() {
    assertRejected("SELECT d_date + INTERVAL 'fortnight' FROM date_dim");
    assertRejected("SELECT d_date + INTERVAL '' FROM date_dim");
    assertRejected("SELECT d_date + INTERVAL '1 fortnights' FROM date_dim");
  }

  @Test
  void pgLegacyDecorationsAreRejected() {
    assertRejected("SELECT d_date + INTERVAL '@ 1 day' FROM date_dim");
    assertRejected("SELECT d_date + INTERVAL '1 day ago' FROM date_dim");
  }

  private static void assertNormalized(String sql, String expectedIntervalText) {
    String rewritten = MaskLite.fromYaml(YAML).rewrite(sql);
    assertTrue(rewritten.contains(expectedIntervalText),
        "expected " + expectedIntervalText + " in: " + rewritten);
  }

  private static void assertRejected(String sql) {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> MaskLite.fromYaml(YAML).rewrite(sql),
        "unsupported bare interval form must be rejected: " + sql);
    assertEquals(SqlMaskException.Code.PARSE_ERROR, e.getCode(),
        "rejection must surface as a parse error, got: " + e.getMessage());
  }
}
