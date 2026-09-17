package io.sqlmask.query.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Base64;

/** Normalizes JDBC values into JSON-safe objects (driver date/time classes
 * would otherwise serialize as arrays or epoch numbers). */
final class ValueJson {

  private ValueJson() {}

  static Object toSerializable(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Double d) {
      // NaN/Infinity 不是合法 JSON 数字，降级为字符串形式
      return Double.isFinite(d) ? d : String.valueOf(d);
    }
    if (value instanceof Float f) {
      return Float.isFinite(f) ? f : String.valueOf(f);
    }
    if (value instanceof Number) {
      return value;
    }
    if (value instanceof Timestamp || value instanceof Date || value instanceof Time
        || value instanceof LocalDateTime || value instanceof LocalDate
        || value instanceof LocalTime || value instanceof OffsetDateTime
        || value instanceof OffsetTime || value instanceof java.util.UUID) {
      return value.toString();
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    return String.valueOf(value);
  }
}
