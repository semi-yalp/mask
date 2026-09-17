package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsertOverwriteTest {

  // Calcite 1.42.0 无 SqlParser.createParser(sql)，按 brief 注改用等价的
  // SqlParser.create(sql, config) 形态；7 个用例断言不变。
  private static SqlParser parser(String sql, boolean allowOverwrite) {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(
                org.apache.calcite.sql.validate.SqlConformanceEnum.BABEL, false, allowOverwrite)));
  }

  private SqlNode parse(String sql, boolean allowOverwrite) throws SqlParseException {
    return parser(sql, allowOverwrite).parseStmt();
  }

  @Test
  void parsesOverwriteWithTableKeyword() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE TABLE t SELECT id FROM t", true);
    assertEquals(SqlKind.INSERT, node.getKind());
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void parsesOverwriteWithoutTableKeyword() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE t SELECT id FROM t", true);
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void parsesOverwriteWithColumnList() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE TABLE t (a, b) SELECT 1, 2", true);
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void rejectsPartitionClause() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE TABLE t PARTITION (ds = '1') SELECT id FROM t", true));
    assertTrue(e.getMessage().contains("PARTITION"), () -> e.getMessage());
  }

  @Test
  void rejectsDirectoryTarget() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE DIRECTORY '/x' SELECT id FROM t", true));
    assertTrue(e.getMessage().contains("DIRECTORY"), () -> e.getMessage());
  }

  @Test
  void rejectsCompoundInsertColumns() {
    // p.right（列类型 extend 列表）仅在列名带类型注解时非空（基语法
    // AddCompoundIdentifierType）；裸复合名 (a.b) 落入 p.left、与基语法
    // INSERT INTO 行为一致，不在本拒绝范围。此用例钉住 fail-closed：
    // mask 产生式丢弃基语法会 extend 回表引用的 p.right，故非空即拒。
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE TABLE t (a.b VARCHAR(10)) SELECT 1", true));
    assertTrue(e.getMessage().contains("compound insert columns"), () -> e.getMessage());
  }

  @Test
  void failsWithClearMessageWhenDialectDisallows() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE TABLE t SELECT id FROM t", false));
    assertTrue(e.getMessage().contains("not enabled"), () -> e.getMessage());
  }

  @Test
  void plainInsertIntoStillWorks() throws SqlParseException {
    assertEquals(SqlKind.INSERT, parse("INSERT INTO t SELECT id FROM t", true).getKind());
  }
}
