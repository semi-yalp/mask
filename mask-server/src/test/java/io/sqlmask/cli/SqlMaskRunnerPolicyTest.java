package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskRunnerPolicyTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
      policies: {}
      """;

  private static final String POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {users: ["alice"], udf: mask_phone}
      """;

  @TempDir
  Path dir;

  private Path write(String name, String content) throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  @Test
  void subjectUserDeterminesMasking() throws Exception {
    Path metadata = write("metadata.yaml", METADATA);
    Path policies = write("policies.yaml", POLICIES);
    SqlMaskRunner runner = new SqlMaskRunner();
    CliOptions anonymous = new CliOptions(metadata, policies, null, null,
        "SELECT phone FROM customer;", null, null, "postgresql");
    String anonymousSql = runner.run(anonymous);
    assertTrue(anonymousSql.contains("SELECT phone"), () -> anonymousSql);
    // 输出是 Calcite 规范化文本；关键行为差异：匿名不命中 UDF
    assertTrue(!anonymousSql.contains("mask_phone("), () -> anonymousSql);
    CliOptions alice = new CliOptions(metadata, policies, "alice", List.of("devs"),
        "SELECT phone FROM customer;", null, null, "postgresql");
    assertTrue(runner.run(alice).contains("mask_phone("));
  }

  @Test
  void conflictingPolicySourcesFailWithConfigError() throws Exception {
    Path metadata = write("metadata.yaml", METADATA.replace("policies: {}", """
        policies:
          p:
            udf: mask_phone
        """));
    Path policies = write("policies.yaml", POLICIES);
    SqlMaskRunner runner = new SqlMaskRunner();
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> runner.run(new CliOptions(metadata, policies, null, null,
            "SELECT phone FROM customer;", null, null, "postgresql")));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
