package io.masklite.sql;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.parser.SqlParserUtil;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes PostgreSQL bare interval strings ({@code INTERVAL '1 day'}, no
 * qualifier) into real Calcite interval literals so parsing, validation and
 * conversion all run on stock semantics.
 *
 * <p>The emitted literal carries a qualifier form whose value is equal to the
 * original PG semantics ({@code '1 day'} → {@code INTERVAL '1' DAY}; hours
 * accumulate into {@code DAY TO SECOND}). Forms Calcite's type system cannot
 * express stay unsupported and the caller keeps rejecting them fail-closed:
 * mixed year-month plus day-time families ({@code '1 year 1 day'}), fractional
 * months ({@code '1.5 months'}) and the legacy {@code @} / {@code ago}
 * decorations. Sub-microsecond precision is likewise rejected.
 *
 * <p>Value equivalences rely on PG treating day/time units as absolute
 * (1 day = 86400s, 1 week = 7 days) and year/month as calendar units, matching
 * Calcite's DAY_TO_SECOND / YEAR_TO_MONTH evaluation. Lead fields wider than
 * the default precision (2 digits) get an explicit lead precision, which both
 * Calcite and PG accept ({@code INTERVAL '400' DAY(3)}).
 */
public final class PgBareIntervalLiterals {

  /** number token: optional sign, integer or decimal */
  private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+)(?:\\.(\\d+))?");

  /** clock token: HH[:MM[:SS[.f{1,6}]]], no own sign, hours capped against overflow */
  private static final Pattern CLOCK =
      Pattern.compile("(\\d{1,7}):(\\d{1,2})(?::(\\d{1,2})(?:\\.(\\d{1,6}))?)?");

  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND;
  private static final long MICROS_PER_HOUR = 60 * MICROS_PER_MINUTE;
  private static final long MICROS_PER_DAY = 24 * MICROS_PER_HOUR;

  private static final Map<String, Long> UNIT_MONTHS = Map.ofEntries(
      Map.entry("y", 12L), Map.entry("yr", 12L), Map.entry("yrs", 12L),
      Map.entry("year", 12L), Map.entry("years", 12L),
      Map.entry("mon", 1L), Map.entry("mons", 1L),
      Map.entry("month", 1L), Map.entry("months", 1L),
      Map.entry("decade", 120L), Map.entry("decades", 120L),
      Map.entry("century", 1200L), Map.entry("centuries", 1200L),
      Map.entry("millennium", 12000L), Map.entry("millennia", 12000L));

  private static final Map<String, Long> UNIT_DAYS = Map.of(
      "w", 7L, "week", 7L, "weeks", 7L,
      "d", 1L, "day", 1L, "days", 1L);

  private static final Map<String, Long> UNIT_MICROS = Map.ofEntries(
      Map.entry("h", MICROS_PER_HOUR), Map.entry("hr", MICROS_PER_HOUR),
      Map.entry("hrs", MICROS_PER_HOUR), Map.entry("hour", MICROS_PER_HOUR),
      Map.entry("hours", MICROS_PER_HOUR),
      Map.entry("m", MICROS_PER_MINUTE), Map.entry("min", MICROS_PER_MINUTE),
      Map.entry("mins", MICROS_PER_MINUTE), Map.entry("minute", MICROS_PER_MINUTE),
      Map.entry("minutes", MICROS_PER_MINUTE),
      Map.entry("s", MICROS_PER_SECOND), Map.entry("sec", MICROS_PER_SECOND),
      Map.entry("secs", MICROS_PER_SECOND), Map.entry("second", MICROS_PER_SECOND),
      Map.entry("seconds", MICROS_PER_SECOND),
      Map.entry("ms", 1_000L), Map.entry("millis", 1_000L),
      Map.entry("millisecond", 1_000L), Map.entry("milliseconds", 1_000L),
      Map.entry("us", 1L), Map.entry("usec", 1L), Map.entry("micros", 1L),
      Map.entry("microsecond", 1L), Map.entry("microseconds", 1L));

  /**
   * Builds the normalized literal, or returns null when the string is not a
   * supported bare PG interval (caller rejects fail-closed).
   *
   * @param sign the sign parsed between INTERVAL and the string literal
   */
  public static SqlIntervalLiteral literal(SqlParserPos pos, int sign, String raw) {
    String text = raw.trim();
    if (text.isEmpty() || text.startsWith("@")
        || text.toLowerCase(Locale.ROOT).endsWith("ago")) {
      return null;
    }
    long months = 0;
    long micros = 0;
    String[] tokens = text.split("\\s+");
    int i = 0;
    while (i < tokens.length) {
      String token = tokens[i];
      Matcher clock = CLOCK.matcher(token);
      if (clock.matches()) {
        Long added = clockMicros(clock);
        if (added == null) {
          return null;
        }
        micros += added;
        i++;
        continue;
      }
      Matcher number = NUMBER.matcher(token);
      if (!number.matches()) {
        return null;
      }
      if (i + 1 >= tokens.length) {
        return null;
      }
      // "<day-count> HH:MM[:SS]" — the number is a day count; a leading
      // sign applies to the whole value (PG whole-string sign semantics)
      Matcher nextClock = CLOCK.matcher(tokens[i + 1]);
      if (nextClock.matches()) {
        if (number.group(2) != null) {
          return null;
        }
        Long added = clockMicros(nextClock);
        if (added == null) {
          return null;
        }
        long dayCount = Long.parseLong(number.group(0));
        micros += dayCount * MICROS_PER_DAY + (dayCount < 0 ? -added : added);
        i += 2;
        continue;
      }
      String unit = tokens[i + 1].toLowerCase(Locale.ROOT);
      Long monthMult = UNIT_MONTHS.get(unit);
      if (monthMult != null) {
        if (number.group(2) != null) {
          return null;
        }
        months += Long.parseLong(number.group(0)) * monthMult;
        i += 2;
        continue;
      }
      Long dayMult = UNIT_DAYS.get(unit);
      if (dayMult != null) {
        Long scaled = scaledIntegral(number, dayMult * MICROS_PER_DAY);
        if (scaled == null) {
          return null;
        }
        micros += scaled;
        i += 2;
        continue;
      }
      Long microMult = UNIT_MICROS.get(unit);
      if (microMult != null) {
        Long scaled = scaledIntegral(number, microMult);
        if (scaled == null) {
          return null;
        }
        micros += scaled;
        i += 2;
        continue;
      }
      return null;
    }
    if (sign == -1) {
      months = -months;
      micros = -micros;
    }
    if (months != 0 && micros != 0) {
      // PG keeps both families in one interval; Calcite's type system cannot
      return null;
    }
    return months != 0 ? yearMonthLiteral(pos, months)
        : dayTimeLiteral(pos, micros);
  }

  /** value × multiplier as an exact long, keeping the token's sign; null if not integral or overflow. */
  private static Long scaledIntegral(Matcher number, long multiplier) {
    BigDecimal value = new BigDecimal(number.group(0));
    BigDecimal scaled = value.multiply(BigDecimal.valueOf(multiplier));
    try {
      return scaled.longValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
  }

  private static Long clockMicros(Matcher clock) {
    long value = Long.parseLong(clock.group(1)) * MICROS_PER_HOUR
        + Long.parseLong(clock.group(2)) * MICROS_PER_MINUTE;
    if (clock.group(3) != null) {
      value += Long.parseLong(clock.group(3)) * MICROS_PER_SECOND;
    }
    if (clock.group(4) != null) {
      String fraction = clock.group(4);
      long micros = Long.parseLong(fraction);
      for (int pad = fraction.length(); pad < 6; pad++) {
        micros *= 10;
      }
      value += micros;
    }
    if (clock.group(0).startsWith("-") || clock.group(0).startsWith("+")) {
      // a clock token never carries its own sign in supported forms
      return null;
    }
    return value;
  }

  private static SqlIntervalLiteral yearMonthLiteral(SqlParserPos pos, long totalMonths) {
    long absolute = Math.abs(totalMonths);
    String prefix = totalMonths < 0 ? "-" : "";
    long years = absolute / 12;
    long months = absolute % 12;
    if (months == 0) {
      Integer precision = leadPrecision(years);
      return precision == null ? null
          : SqlParserUtil.parseIntervalLiteral(pos, 1, prefix + years,
              new SqlIntervalQualifier(TimeUnit.YEAR, precision, null, -1, pos));
    }
    if (years == 0) {
      return SqlParserUtil.parseIntervalLiteral(pos, 1, prefix + months,
          new SqlIntervalQualifier(TimeUnit.MONTH, 2, null, -1, pos));
    }
    Integer precision = leadPrecision(years);
    return precision == null ? null
        : SqlParserUtil.parseIntervalLiteral(pos, 1, prefix + years + "-" + months,
            new SqlIntervalQualifier(TimeUnit.YEAR, precision, TimeUnit.MONTH, -1, pos));
  }

  private static SqlIntervalLiteral dayTimeLiteral(SqlParserPos pos, long totalMicros) {
    long absolute = Math.abs(totalMicros);
    String prefix = totalMicros < 0 ? "-" : "";
    long days = absolute / MICROS_PER_DAY;
    long remainder = absolute % MICROS_PER_DAY;
    if (remainder == 0) {
      Integer precision = leadPrecision(days);
      return precision == null ? null
          : SqlParserUtil.parseIntervalLiteral(pos, 1, prefix + days,
              new SqlIntervalQualifier(TimeUnit.DAY, precision, null, -1, pos));
    }
    long hours = remainder / MICROS_PER_HOUR;
    long minutes = (remainder % MICROS_PER_HOUR) / MICROS_PER_MINUTE;
    long secondAndFraction = remainder % MICROS_PER_MINUTE;
    long seconds = secondAndFraction / MICROS_PER_SECOND;
    long fraction = secondAndFraction % MICROS_PER_SECOND;
    Integer precision = leadPrecision(days);
    if (precision == null) {
      return null;
    }
    String value = prefix + days + " " + two(hours) + ":" + two(minutes) + ":" + two(seconds);
    if (fraction > 0) {
      value += "." + String.format("%06d", fraction);
    }
    return SqlParserUtil.parseIntervalLiteral(pos, 1, value,
        new SqlIntervalQualifier(TimeUnit.DAY, precision, TimeUnit.SECOND, -1, pos));
  }

  /**
   * Lead precision for the qualifier: fields within Calcite's default (2
   * digits) take the unspecified marker so unparse stays plain
   * ({@code DAY TO SECOND}); wider fields carry an explicit precision
   * ({@code DAY(3)}); beyond 9 digits is unsupported.
   */
  private static Integer leadPrecision(long value) {
    int digits = String.valueOf(value).length();
    if (digits > 9) {
      return null;
    }
    return digits <= 2 ? org.apache.calcite.rel.type.RelDataType.PRECISION_NOT_SPECIFIED
        : digits;
  }

  private static String two(long value) {
    return value < 10 ? "0" + value : String.valueOf(value);
  }

  private PgBareIntervalLiterals() {
  }
}
