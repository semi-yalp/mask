package io.sqlmask.parser;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentifierRegressionTest {

  private static SqlNode parse(String sql) throws SqlParseException {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false)))
        .parseStmt();
  }

  @Test
  void topUsableAsColumnName() {
    assertDoesNotThrow(() -> parse("SELECT top FROM t"));
    assertDoesNotThrow(() -> parse("SELECT t.top FROM t"));
    assertDoesNotThrow(() -> parse("SELECT id AS top FROM t"));
  }

  @Test
  void overwriteUsableAsColumnName() {
    assertDoesNotThrow(() -> parse("SELECT overwrite FROM t"));
    assertDoesNotThrow(() -> parse("SELECT id AS overwrite FROM t"));
  }

  @Test
  void topUsableAsTableName() {
    assertDoesNotThrow(() -> parse("SELECT id FROM top"));
  }

  @Test
  void topAsFunctionCallFailsClosed() {
    // 已知边界（spec §4.2）：名为 top 的函数调用会被识别为 TOP 子句 → 解析失败，
    // 语义上 fail-closed（错误而非错误掩码），钉死行为防止未来悄悄改变。
    assertThrows(SqlParseException.class, () -> parse("SELECT top(1) FROM t"));
  }
}
