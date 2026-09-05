package io.sqlmask.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskApplicationTest {

  private final SqlMaskApplication app = new SqlMaskApplication();

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
