package io.sqlmask.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class BabelEquivalenceTest {

  private static SqlParser.Config babel() {
    return SqlParser.config()
        .withParserFactory(org.apache.calcite.sql.parser.babel.SqlBabelParserImpl.FACTORY)
        .withConformance(SqlConformanceEnum.BABEL);
  }

  private static SqlParser.Config ours() {
    return SqlParser.config()
        .withParserFactory(SqlMaskParserImpl.FACTORY)
        .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false));
  }

  private static List<String> corpus() throws IOException {
    List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(
        java.nio.file.Path.of("src/test/resources/mask-parser-corpus.sql"),
        StandardCharsets.UTF_8));
    // golden 改写语料：mask-core golden 目录多语句文件，按分号切（spec §7.2）
    java.nio.file.Path golden = java.nio.file.Path.of(
        "../mask-core/src/test/resources/golden/tpcds-common.sql");
    if (java.nio.file.Files.exists(golden)) {
      for (String stmt : java.nio.file.Files.readString(golden, StandardCharsets.UTF_8)
          .split(";\\s*\\n")) {
        // 多语句按 "; + 换行" 切分时，文件末尾若以 ";" 收尾且无换行，
        // 会残留一个尾分号（切分残片），Babel 会因多余 ";" 拒绝整条语句；
        // 去掉残片后该条语句是合法 SQL，仍参与两侧差分比较。
        String flat = stmt.replace('\n', ' ').trim();
        if (flat.endsWith(";")) {
          flat = flat.substring(0, flat.length() - 1).trim();
        }
        if (!flat.isEmpty()) {
          lines.add(flat);
        }
      }
    }
    return lines.stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty() && !s.startsWith("#"))
        .toList();
  }

  @Test
  void newParserMatchesBabelWhenFlagsClosed() throws IOException {
    for (String sql : corpus()) {
      try {
        SqlNode base = SqlParser.create(sql, babel()).parseStmt();
        SqlNode masked = SqlParser.create(sql, ours()).parseStmt();
        assertEquals(base.getKind(), masked.getKind(), () -> "kind differs: " + sql);
        assertEquals(base.toString(), masked.toString(),
            () -> "unparse differs: " + sql);
      } catch (SqlParseException e) {
        fail("both parsers must accept corpus statement: " + sql + " -> " + e.getMessage());
      }
    }
    assertNotNull(corpus());
  }
}
