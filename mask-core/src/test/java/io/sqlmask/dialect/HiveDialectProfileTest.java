package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HiveDialectProfileTest {

  /** Calcite 把子句分隔渲染成换行（CRLF/LF 随平台）；断言前做空白归一化。 */
  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static final String YAML = """
      metadata:
        tables:
          - catalog: hive
            schema: ods
            name: customer
            columns:
              - { name: id, type: bigint }
              - { name: phone, type: string }
      columns:
        - { catalog: hive, schema: ods, table: customer, column: phone, policy: m }
      policies:
        m: { udf: mask_phone, arguments: [3, 4] }
      """;

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void rewritesSelectWithBacktickWrapper() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "SELECT phone FROM ods.customer", "hive", io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out).hasSize(1);
    assertThat(out.get(0).kind()).isEqualTo(io.sqlmask.rewrite.StatementKind.SELECT);
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains(
        "SELECT `mask_phone`(`r`.`phone`, 3, 4) AS `phone`");
    assertThat(out.get(0).rewrittenSql()).contains(") AS `r`");
  }

  @Test
  void rowFilterInjectedForHiveTwoPartNames() {
    // 行过滤在 metadata 内嵌路径：单独声明 rowFilter 的表
    String filteredYaml = """
        metadata:
          tables:
            - catalog: hive
              schema: ods
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: string }
                - { name: status, type: string }
        policies: {}
        """;
    List<StatementRewrite> out = engine.rewrite(filteredYaml, null,
        "SELECT phone FROM ods.customer", "hive", io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out.get(0).rowFiltered()).isTrue();
    // 多子句渲染的换行随平台是 CRLF/LF（Calcite 用 lineSeparator），按
    // RowFilterRewriterTest 的既有做法做空白归一化后再断言形状
    assertThat(flat(out.get(0).rewrittenSql())).contains(
        "(SELECT * FROM ods.customer WHERE status = 'active') customer");
  }

  @Test
  void unnamedComputedColumnIsRefusedWithNamingHint() {
    // M1 修复：Hive/SparkSQL 不支持派生表列别名表（FROM (...) AS r (a, b)），
    // 未命名计算列（EXPR$N）改名后无法被包装层引用——fail-closed 报错并提示
    // 显式命名，而不是产出必然失败的 SQL
    assertThatThrownBy(() -> engine.rewrite(YAML, null,
        "SELECT upper(phone) FROM customer", "hive",
        io.sqlmask.policy.model.Subject.anonymous()))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("unnamed computed column")
        .hasMessageContaining("alias");
  }

  @Test
  void insertOverwriteIsParsedAndWrapped() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "INSERT OVERWRITE TABLE arch SELECT phone FROM customer", "hive",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out).hasSize(1);
    assertThat(out.get(0).kind()).isEqualTo(io.sqlmask.rewrite.StatementKind.INSERT_SELECT);
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).startsWith("INSERT OVERWRITE TABLE ");
  }

  @Test
  void typeResolverRejectsUnknownAndComplexTypes() {
    var resolver = new HiveTypeResolver();
    assertThat(resolver.parseColumn("a", "string").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "array<int>"))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("array");
    assertThatThrownBy(() -> resolver.parseColumn("a", "map<string,int>"))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("map");
    assertThatThrownBy(() -> resolver.parseColumn("a", "struct<x:int>"))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("struct");
  }

  @Test
  void decimalRequiresBothPrecisionAndScale() {
    // 批2终审修复（I2）：decimal(10)（precision=10, scale=null）曾漏过守卫、在下游
    // schema 构建时拆箱 NPE——decimal 声明必须 (p,s) 双精度，fail-closed 报支持清单
    var resolver = new HiveTypeResolver();
    assertThat(resolver.parseColumn("a", "decimal(10, 2)").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "decimal(10)"))
        .isInstanceOf(io.sqlmask.error.SqlMaskException.class)
        .hasMessageContaining("unsupported hive type 'decimal(10)'")
        .hasMessageContaining("decimal(p,s)");
  }

  @Test
  void betweenFormsRenderWithoutAsymmetricFlag() {
    // 对齐 MultiDialectRewriteTest.mysqlBetweenFormsRenderFaithfully：Calcite 的
    // SqlBetweenOperator 默认渲染 "BETWEEN ASYMMETRIC"，真实 Hive 无该关键字——
    // 两种形式在重组输出里必须保持原义且不出现 ASYMMETRIC（投影带 phone 强制
    // 语句走包装重组路径，钉住的是 unparse 层而非直通路径）
    String between = flat(engine.rewrite(YAML, null,
        "SELECT id, phone FROM customer WHERE id BETWEEN 1 AND 5", "hive",
        io.sqlmask.policy.model.Subject.anonymous()).get(0).rewrittenSql());
    assertThat(between).doesNotContain("ASYMMETRIC").contains(" id BETWEEN 1 AND 5");
    String notBetween = flat(engine.rewrite(YAML, null,
        "SELECT id, phone FROM customer WHERE id NOT BETWEEN 1 AND 5", "hive",
        io.sqlmask.policy.model.Subject.anonymous()).get(0).rewrittenSql());
    assertThat(notBetween).doesNotContain("ASYMMETRIC").contains(" id NOT BETWEEN 1 AND 5");
  }
}
