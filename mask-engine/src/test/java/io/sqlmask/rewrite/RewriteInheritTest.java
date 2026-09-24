package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复制表语句的脱敏策略继承:继承列不包装、输出 InheritedColumn、冲突/歧义 fail-closed。 */
class RewriteInheritTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns: [ {name: phone, type: varchar}, {name: name, type: varchar} ]
      policies: {}
      """;

  private static final String POLICY = """
      policies:
        - name: crm.phone
          type: dataMask
          resources:
            - catalog: crm
              schema: public
              table: customer
              column: phone
              inheritOnCopy: true
          dataMaskItems:
            - groups: ["*"]
              udf: mask_phone
        - name: crm.name
          type: dataMask
          resources:
            - catalog: crm
              schema: public
              table: customer
              column: name
          dataMaskItems:
            - groups: ["*"]
              udf: mask_name
      """;

  @Test
  void ctasInheritingColumnStaysUnwrappedAndReportsInheritedColumn() {
    List<RewriteEngine.StatementRewrite> results = new RewriteEngine()
        .rewrite(METADATA, POLICY,
            "CREATE TABLE crm.public.customer_copy AS SELECT phone FROM crm.public.customer",
            "postgresql", Subject.anonymous());
    RewriteEngine.StatementRewrite st = results.get(0);
    assertEquals(StatementKind.CTAS, st.kind());
    assertFalse(st.masked(), "继承列不应被脱敏包装");
    assertEquals(st.originalSql(), st.rewrittenSql(), "纯继承时语句应原样透传");
    assertEquals(1, st.inheritedColumns().size());
    InheritedColumn ic = st.inheritedColumns().get(0);
    assertEquals("crm", ic.targetCatalog());
    assertEquals("public", ic.targetSchema());
    assertEquals("customer_copy", ic.targetTable());
    assertEquals("phone", ic.targetColumn());
    assertEquals("mask_phone", ic.items().get(0).udf());
  }

  @Test
  void mixedInheritAndMaskedColumnsWrapOnlyTheMaskedColumn() {
    List<RewriteEngine.StatementRewrite> results = new RewriteEngine()
        .rewrite(METADATA, POLICY,
            "INSERT INTO crm.public.customer_copy (phone, name) "
                + "SELECT phone, name FROM crm.public.customer",
            "postgresql", Subject.anonymous());
    RewriteEngine.StatementRewrite st = results.get(0);
    assertEquals(StatementKind.INSERT_SELECT, st.kind());
    assertTrue(st.masked(), "name 列无继承标志,应被脱敏包装");
    assertEquals(1, st.inheritedColumns().size());
    assertTrue(st.rewrittenSql().contains("mask_name"), "改写里应保留非继承列的脱敏调用");
  }

  @Test
  void expressionColumnOverInheritedSourceIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> new RewriteEngine()
        .rewrite(METADATA, POLICY,
            "CREATE TABLE crm.public.customer_copy AS SELECT phone || '-' FROM crm.public.customer",
            "postgresql", Subject.anonymous()));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void insertIntoTableWithExistingColumnPolicyIsRejected() {
    // 目标表 customer_copy 已声明 phone 策略的情况下,INSERT 继承被拒
    String meta = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns: [ {name: phone, type: varchar}, {name: name, type: varchar} ]
            - catalog: crm
              schema: public
              name: customer_copy
              columns: [ {name: phone, type: varchar}, {name: name, type: varchar} ]
        policies: {}
        """;
    String policy = POLICY + """
        \s\s- name: crm.copy.phone
        \s\s\s\stype: dataMask
        \s\s\s\sresources:
        \s\s\s\s\s\s- catalog: crm
        \s\s\s\s\s\s\s\sschema: public
        \s\s\s\s\s\s\s\stable: customer_copy
        \s\s\s\s\s\s\s\scolumn: phone
        \s\s\s\sdataMaskItems:
        \s\s\s\s\s\s- groups: ["*"]
        \s\s\s\s\s\s\s\sudf: mask_phone
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> new RewriteEngine()
        .rewrite(meta, policy,
            "INSERT INTO crm.public.customer_copy SELECT phone, name FROM crm.public.customer",
            "postgresql", Subject.anonymous()));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }
}
