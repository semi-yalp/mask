package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteEnginePolicyTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
      policies: {}
      """;

  private static final String MASK_POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {groups: ["*"], udf: mask_phone, arguments: [3, 4]}
      """;

  private static final String POLICIES = MASK_POLICIES + """
        - name: filter-active
          resources:
            - {catalog: crm, schema: public, table: customer}
          rowFilterItems:
            - {groups: ["*"], filterExpr: "status = 'active'"}
      """;

  private static final String SQL = "SELECT phone FROM customer;";

  @Test
  void policyYamlMaskMatchesLegacyEquivalent() {
    String legacy = METADATA.replace("policies: {}", """
        columns:
          - {catalog: crm, schema: public, table: customer, column: phone, policy: p}
        policies:
          p:
            udf: mask_phone
            arguments: [3, 4]
        """);
    String viaLegacy = new RewriteEngine().rewrite(legacy, SQL, "postgresql")
        .get(0).rewrittenSql();
    String viaPolicyFile = new RewriteEngine()
        .rewrite(METADATA, MASK_POLICIES, SQL, "postgresql", Subject.anonymous())
        .get(0).rewrittenSql();
    assertEquals(viaLegacy, viaPolicyFile);
  }

  @Test
  void subjectWithoutMatchPassesThroughUnmasked() {
    String policies = POLICIES.replace("groups: [\"*\"]", "users: [\"alice\"]");
    List<RewriteEngine.StatementRewrite> result = new RewriteEngine()
        .rewrite(METADATA, policies, SQL, "postgresql", Subject.anonymous());
    // 无包装时输出 = 输入语句的规范化渲染（与旧行为一致），原文不丢
    assertFalse(result.get(0).masked());
    assertFalse(result.get(0).rowFiltered());
    assertEquals(result.get(0).originalSql(), result.get(0).rewrittenSql());
  }

  @Test
  void namedUserGetsMaskAndRowFilter() {
    List<RewriteEngine.StatementRewrite> result = new RewriteEngine()
        .rewrite(METADATA, POLICIES, SQL, "postgresql", Subject.of("alice", List.of()));
    String sql = result.get(0).rewrittenSql();
    assertTrue(result.get(0).masked());
    assertTrue(sql.contains("mask_phone"));
    assertTrue(result.get(0).rowFiltered());
    assertTrue(sql.contains("status = 'active'"));
  }

  @Test
  void policyYamlConflictingWithLegacyPoliciesIsRejected() {
    String legacyWithPolicies = METADATA.replace("policies: {}", """
        policies:
          p:
            udf: mask_phone
            arguments: []
        """);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(legacyWithPolicies, POLICIES, SQL,
            "postgresql", Subject.anonymous()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("single source"));
  }

  @Test
  void policyYamlConflictingWithRowFilterIsRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - {name: id, type: bigint}
                - {name: phone, type: varchar}
        policies: {}
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(yaml, POLICIES, SQL, "postgresql",
            Subject.anonymous()));
    assertTrue(e.getMessage().contains("rowFilter"));
  }

  @Test
  void invalidPolicyExpressionFailsBeforeAnyStatement() {
    String policies = POLICIES.replace("status = 'active'",
        "status = 'active' AND random() > 0");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new RewriteEngine().rewrite(METADATA, policies, SQL, "postgresql",
            Subject.anonymous()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().startsWith("policy 'filter-active'"));
  }

  @Test
  void twoRowFilterPoliciesComposeWithAnd() {
    String policies = """
        policies:
          - name: f1
            resources:
              - {catalog: crm, schema: public, table: customer}
            rowFilterItems:
              - {groups: ["*"], filterExpr: "status = 'active'"}
          - name: f2
            resources:
              - {catalog: crm, schema: public, table: customer}
            rowFilterItems:
              - {groups: ["*"], filterExpr: "id > 0"}
        """;
    String sql = new RewriteEngine()
        .rewrite(METADATA, policies, SQL, "postgresql", Subject.anonymous())
        .get(0).rewrittenSql();
    assertTrue(sql.contains("status = 'active'"));
    assertTrue(sql.contains("id > 0"));
  }

  @Test
  void multiOriginOutputPicksSmallestColumnKeyAmongHits() {
    String metadata = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: a
              columns: [{name: x, type: varchar}]
            - catalog: crm
              schema: public
              name: b
              columns: [{name: y, type: varchar}]
        policies: {}
        """;
    String policies = """
        policies:
          - name: mask-b-y
            resources:
              - {catalog: crm, schema: public, table: b, column: y}
            dataMaskItems:
              - {groups: ["*"], udf: mask_b}
          - name: mask-a-x
            resources:
              - {catalog: crm, schema: public, table: a, column: x}
            dataMaskItems:
              - {groups: ["*"], udf: mask_a}
        """;
    String sql = new RewriteEngine()
        .rewrite(metadata, policies, "SELECT a.x || b.y FROM a, b;", "postgresql",
            Subject.anonymous())
        .get(0).rewrittenSql();
    // crm.public.a.x < crm.public.b.y：只有 mask_a 生效
    assertTrue(sql.contains("mask_a("));
    assertFalse(sql.contains("mask_b("));
  }
}
