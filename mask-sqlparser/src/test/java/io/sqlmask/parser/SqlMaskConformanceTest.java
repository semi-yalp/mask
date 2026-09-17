package io.sqlmask.parser;

import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskConformanceTest {

  @Test
  void flagsDefaultToClosed() {
    SqlMaskConformance c = SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false);
    assertFalse(c.isTopNAllowed());
    assertFalse(c.isInsertOverwriteAllowed());
  }

  @Test
  void flagsOpenIndependently() {
    SqlMaskConformance c = SqlMaskConformance.of(SqlConformanceEnum.BABEL, true, false);
    assertTrue(c.isTopNAllowed());
    assertFalse(c.isInsertOverwriteAllowed());
  }

  @Test
  void delegatesToWrappedConformance() {
    // 语义委托：包装 DEFAULT 后，DEFAULT 独有的限制保持生效
    SqlConformance c = SqlMaskConformance.of(SqlConformanceEnum.DEFAULT, true, true);
    assertFalse(c.isLimitStartCountAllowed());   // DEFAULT 拒绝 LIMIT n, m
    SqlConformance m = SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5, true, true);
    assertTrue(m.isLimitStartCountAllowed());    // MYSQL_5 允许
  }
}
