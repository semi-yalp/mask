package io.sqlmask.dialect;

import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparkSqlDialectProfileTest {

  /** Calcite 把子句分隔渲染成换行（CRLF/LF 随平台）；断言前做空白归一化。 */
  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static final String YAML = """
      metadata:
        tables:
          - catalog: spark
            schema: analytics
            name: customer
            columns:
              - { name: id, type: bigint }
              - { name: phone, type: string }
      columns:
        - { catalog: spark, schema: analytics, table: customer, column: phone, policy: m }
      policies:
        m: { udf: mask_phone, arguments: [3, 4] }
      """;

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void rewritesSelectWithBacktickWrapper() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "SELECT phone FROM analytics.customer", "sparksql",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out).hasSize(1);
    assertThat(out.get(0).kind()).isEqualTo(io.sqlmask.rewrite.StatementKind.SELECT);
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains(
        "SELECT `mask_phone`(`r`.`phone`, 3, 4) AS `phone`");
    assertThat(out.get(0).rewrittenSql()).contains(") AS `r`");
  }

  @Test
  void rowFilterInjectedForTwoPartNames() {
    // 行过滤在 metadata 内嵌路径：单独声明 rowFilter 的表
    String filteredYaml = """
        metadata:
          tables:
            - catalog: spark
              schema: analytics
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: string }
                - { name: status, type: string }
        policies: {}
        """;
    List<StatementRewrite> out = engine.rewrite(filteredYaml, null,
        "SELECT phone FROM analytics.customer", "sparksql",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out.get(0).rowFiltered()).isTrue();
    // 多子句渲染的换行随平台是 CRLF/LF（Calcite 用 lineSeparator），按
    // RowFilterRewriterTest 的既有做法做空白归一化后再断言形状
    assertThat(flat(out.get(0).rewrittenSql())).contains(
        "(SELECT * FROM analytics.customer WHERE status = 'active') customer");
  }

  @Test
  void unnamedOutputColumnIsRenderedWithQuotedAlias() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "SELECT upper(phone) FROM customer", "sparksql",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains("AS `EXPR$0`");
  }

  @Test
  void typeResolverRejectsUnknownAndComplexTypes() {
    var resolver = new SparkSqlTypeResolver();
    assertThat(resolver.parseColumn("a", "string").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "array<int>"))
        .hasMessageContaining("unsupported sparksql type")
        .hasMessageContaining("array");
    assertThatThrownBy(() -> resolver.parseColumn("a", "map<string,int>"))
        .hasMessageContaining("map");
    assertThatThrownBy(() -> resolver.parseColumn("a", "struct<x:int>"))
        .hasMessageContaining("struct");
    assertThatThrownBy(() -> resolver.parseColumn("a", "timestamp_ntz"))
        .hasMessageContaining("timestamp_ntz");
  }
}
