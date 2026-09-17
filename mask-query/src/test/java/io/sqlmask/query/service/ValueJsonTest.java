package io.sqlmask.query.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueJsonTest {

  @Test
  void nonFiniteFloatingPointBecomesItsStringForm() {
    // NaN/Infinity 不是合法 JSON 数字：序列化前统一降级为字符串形式
    assertThat(ValueJson.toSerializable(Double.NaN)).isEqualTo("NaN");
    assertThat(ValueJson.toSerializable(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
    assertThat(ValueJson.toSerializable(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    assertThat(ValueJson.toSerializable(Float.NaN)).isEqualTo("NaN");
    assertThat(ValueJson.toSerializable(Float.POSITIVE_INFINITY)).isEqualTo("Infinity");
  }

  @Test
  void finiteNumbersAndStringsPassThroughUnchanged() {
    assertThat(ValueJson.toSerializable(1.5d)).isEqualTo(1.5d);
    assertThat(ValueJson.toSerializable(-2.5f)).isEqualTo(-2.5f);
    assertThat(ValueJson.toSerializable(42)).isEqualTo(42);
    assertThat(ValueJson.toSerializable("x")).isEqualTo("x");
    assertThat(ValueJson.toSerializable(null)).isNull();
  }
}
