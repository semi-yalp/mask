package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对抗性 fail-closed 战斗测试：TPC-DS 良性语料打不到的安全分支。
 * break 对应：任何一条本应拒绝的输入开始放行（受保护数据走私）。
 */
class FailClosedTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - { name: id, type: bigint }
              - { name: phone, type: varchar(20) }
              - { name: status, type: varchar(10) }
          - catalog: crm
            schema: public
            name: orders
            columns:
              - { name: id, type: bigint }
              - { name: note, type: varchar(100) }
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      columns:
        - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_phone }
      """;

  /** 标量子查询把受保护列带到输出 = 绕过外层包装走私，必须整条拒绝。 */
  @Test
  void scalarSubquerySmugglingProtectedColumnIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("SELECT (SELECT phone FROM customer LIMIT 1) FROM customer"));
    assertTrue(e.getMessage().contains("no safely traceable origin"),
        "expected the lineage fail-closed diagnostic, got: " + e.getMessage());
  }

  /** 子查询输出不命中任何策略（q09 形态）必须照常放行，不能过度拒绝。 */
  @Test
  void scalarSubqueryOverUnprotectedColumnPasses() {
    List<MaskLite.StatementRewrite> statements = MaskLite.fromYaml(YAML)
        .rewriteStatements("SELECT id, (SELECT max(id) FROM customer) FROM customer");
    assertFalse(statements.get(0).masked(), "no protected column in the output");
  }

  /** 两段名 public.customer 在 PG 搜索路径口径下解析不到声明的 crm.public.customer。 */
  @Test
  void twoPartTableNameThatEscapesTheSearchPathIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("SELECT id FROM public.customer"));
    assertTrue(e.getMessage().contains("validation failed"),
        "expected a validation rejection, got: " + e.getMessage());
  }

  /** 未限定列名在两表同名时歧义，校验必须拒绝而不是任选一支。 */
  @Test
  void ambiguousUnqualifiedColumnIsRejected() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("SELECT id FROM customer, orders"));
  }

  /** 递归 CTE 的血缘不可追踪，必须拒绝而不是放行原文。 */
  @Test
  void recursiveCteIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t"
            + " WHERE n < 5) SELECT n FROM t"));
    assertTrue(e.getMessage().contains("recursive"),
        "expected the recursive-CTE diagnostic, got: " + e.getMessage());
  }

  @Test
  void createTableAsIsRejected() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("CREATE TABLE t AS SELECT id FROM customer"));
  }

  @Test
  void deleteIsRejected() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("DELETE FROM customer"));
  }

  /** 行过滤条件只许列间比较：函数调用进白名单 = 表达式注入面。 */
  @Test
  void rowFilterRejectsFunctionCalls() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(
            YAML.replace("status = 'active'", "length(status) > 3"))
        .rewriteStatements("SELECT id FROM customer"));
  }

  @Test
  void rowFilterRejectsSubqueries() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(
            YAML.replace("status = 'active'", "status = (SELECT 'active')"))
        .rewriteStatements("SELECT id FROM customer"));
  }

  /** 会话变量（CURRENT_USER 等）以裸标识符出现，不是本表声明列，必须拒绝。 */
  @Test
  void rowFilterRejectsSessionVariables() {
    assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(
            YAML.replace("status = 'active'", "current_user = 'x'"))
        .rewriteStatements("SELECT id FROM customer"));
  }

  /** 无顶层 ORDER BY 的 WITH…SELECT 是合法 PG 查询，不能被引擎按语句种类误拒。 */
  @Test
  void withQueryWithoutOrderByPasses() {
    List<MaskLite.StatementRewrite> statements = MaskLite.fromYaml(YAML)
        .rewriteStatements("WITH active AS (SELECT id FROM customer) SELECT id FROM active");
    assertFalse(statements.get(0).masked(), "no protected column in the output");
  }

  /** 多语句中任何一条失败 = 整批失败，绝无部分输出。 */
  @Test
  void multiStatementFailureIsAtomic() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml(YAML)
        .rewriteStatements("SELECT id FROM customer; SELECT id FROM nope"));
    assertTrue(e.getMessage().contains("statement 2"),
        "the diagnostic must point at the failing statement, got: " + e.getMessage());
  }

  /** 计算列（无别名，EXPR$N 重命名路径）落在受保护列上仍要得到包装。 */
  @Test
  void computedColumnOverProtectedColumnIsMasked() {
    MaskLite mask = MaskLite.fromYaml(YAML);
    MaskLite.StatementRewrite s = mask.rewriteStatements(
        "SELECT phone || 'x' FROM customer").get(0);
    assertTrue(s.masked(), "the computed column carries phone's lineage");
    assertTrue(s.rewrittenSql().contains("mask_phone("),
        "the wrapper must apply the UDF, got: " + s.rewrittenSql());
    assertFalse(s.rewrittenSql().contains("EXPR$"),
        "unnamed computed columns must be renamed, not leak EXPR$N: "
            + s.rewrittenSql());
  }
}
