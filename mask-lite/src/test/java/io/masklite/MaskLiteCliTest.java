package io.masklite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CLI 参数解析。break 对应：选项缺值时数组越界（旧实现抛 AIOOBE，输出
 * "fatal: null" 按失败退出而非用法错误）、未知选项/缺必填项静默或报错口径不一。
 */
class MaskLiteCliTest {

  @Test
  void parsesSqlVariant() {
    MaskLite.CliOptions options = MaskLite.parseArguments(
        new String[] {"--metadata", "m.yaml", "--sql", "SELECT 1"});
    assertEquals(Path.of("m.yaml"), options.metadata());
    assertEquals("SELECT 1", options.sql());
    assertEquals(null, options.input());
  }

  @Test
  void parsesInputVariant() {
    MaskLite.CliOptions options = MaskLite.parseArguments(
        new String[] {"--input", "q.sql", "--metadata", "m.yaml"});
    assertEquals(Path.of("q.sql"), options.input());
    assertEquals(null, options.sql());
  }

  @Test
  void rejectsOptionWithoutValue() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> MaskLite.parseArguments(new String[] {"--metadata"}));
    assertEquals("missing value for --metadata", e.getMessage());
    assertEquals("missing value for --sql",
        assertThrows(IllegalArgumentException.class,
            () -> MaskLite.parseArguments(
                new String[] {"--metadata", "m.yaml", "--sql"})).getMessage());
  }

  @Test
  void rejectsUnknownOption() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> MaskLite.parseArguments(new String[] {"--nope", "x"}));
    assertEquals("unknown argument: --nope", e.getMessage());
  }

  @Test
  void rejectsMissingMetadata() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> MaskLite.parseArguments(new String[] {"--sql", "SELECT 1"}));
    assertEquals("--metadata is required", e.getMessage());
  }

  @Test
  void rejectsBothOrNeitherOfSqlAndInput() {
    assertEquals("exactly one of --sql / --input is required",
        assertThrows(IllegalArgumentException.class, () -> MaskLite.parseArguments(
            new String[] {"--metadata", "m", "--sql", "s", "--input", "f"})).getMessage());
    assertEquals("exactly one of --sql / --input is required",
        assertThrows(IllegalArgumentException.class,
            () -> MaskLite.parseArguments(new String[] {"--metadata", "m"})).getMessage());
  }
}
