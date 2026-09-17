package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ParserSmokeTest {

  private SqlNode parse(String sql) throws Exception {
    return SqlParser.create(sql,
        SqlParser.config().withParserFactory(SqlMaskParserImpl.FACTORY)).parseStmt();
  }

  @Test
  void parsesPlainSelect() throws Exception {
    assertEquals(SqlKind.SELECT, parse("SELECT 1").getKind());
  }

  @Test
  void parsesInsertInto() throws Exception {
    assertEquals(SqlKind.INSERT, parse("INSERT INTO t SELECT id FROM t").getKind());
  }

  @Test
  void factoryIsNotNull() {
    assertNotNull(SqlMaskParserImpl.FACTORY);
  }
}
