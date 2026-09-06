package io.sqlmask.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskApplicationTest {

  private final SqlMaskApplication app = new SqlMaskApplication();

  @Test
  void groupsWithPullMetadataExitsWithUsageError() {
    // --groups 只属于改写模式；pull-metadata 在任何数据库访问之前即拒绝
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code = app.run(new String[] {"--pull-metadata", "--groups", "devs"}, System.in,
        new PrintStream(out), new PrintStream(err, true, StandardCharsets.UTF_8));
    assertEquals(2, code);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("--groups"),
        () -> "stderr should mention --groups but was: "
            + err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void unknownDialectExitsWithUsageError() {
    // oracle 未注册；方言校验在任何文件读取之前发生（exit code 2 = 用法错误）
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code = app.run(new String[] {"--metadata", "whatever.yaml", "--sql", "SELECT 1",
        "--dialect", "oracle"}, System.in,
        new PrintStream(out), new PrintStream(err, true, StandardCharsets.UTF_8));
    assertEquals(2, code);
    String stderr = err.toString(StandardCharsets.UTF_8);
    assertTrue(stderr.contains("unsupported dialect"),
        () -> "stderr should mention the unsupported dialect but was: " + stderr);
    assertTrue(stderr.contains("trino") && stderr.contains("mysql"),
        () -> "stderr should list the supported dialects but was: " + stderr);
  }

  @Test
  void noArgumentsReturnsNonZeroAndWritesDiagnostic() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code = app.run(new String[0], System.in,
        new PrintStream(out), new PrintStream(err, true, StandardCharsets.UTF_8));
    assertNotEquals(0, code);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("metadata"),
        () -> "stderr should mention the missing --metadata option but was: "
            + err.toString(StandardCharsets.UTF_8));
  }
}
