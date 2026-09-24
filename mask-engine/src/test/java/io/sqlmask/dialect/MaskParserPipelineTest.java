package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端管线验证（spec §7.3 / §11.3）：开关全开的 mask-sqlparser 自定义解析器
 * 经 parse（SqlInsertOverwrite/SqlBabelCreateTable 子类按 kind 分派，与普通
 * INSERT/CTAS 同路）→ 校验提取出的源查询（生产管线口径，见
 * RewriteEngine.rewriteOne）→ compose 重组 → 产物 round-trip 再解析的完整
 * 链路。构造方式镜像
 * {@link MysqlDialectAdapterTest} 的 YAML + schema 模式，profile 复用
 * {@link MaskParserProfileTest#openAdapter()}（开关全开 + MYSQL_5 语法）。
 */
class MaskParserPipelineTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: shop
            schema: app
            name: orders
            columns:
              - name: id
                type: bigint
              - name: memo
                type: text
      policies: {}
      """;

  private AbstractCalciteDialectAdapter adapter;
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    // 开关全开 + MYSQL_5 语法的测试 profile；构造方式与 MaskParserProfileTest.OPEN_PROFILE 一致
    adapter = MaskParserProfileTest.openAdapter();
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "pipeline-test.yaml", "mysql"));
  }

  @Test
  void insertOverwriteFlowsThroughValidateAndComposes() {
    SqlNode node = adapter.parse(
        "INSERT OVERWRITE TABLE `orders` SELECT `id`, `memo` FROM `orders`", 0);
    // SqlInsertOverwrite 子类 kind 保持 INSERT：classify/querySourceOf/
    // isPassThroughWrite/composeWriteStatement 全按 kind==INSERT 分派（spec §4.3）
    assertEquals(SqlKind.INSERT, node.getKind());
    // 生产管线口径（RewriteEngine.rewriteOne）：写入语句的校验对象是
    // querySourceOf 提取出的源查询；校验+转换全程无需「替换为普通
    // SqlInsert」回退方案（spec §11.3 的子类分派风险实测不存在）
    ValidatedSql validated = adapter.validate(adapter.querySourceOf(node), schema);
    assertEquals(SqlKind.SELECT, validated.original().getKind());
    assertEquals(java.util.List.of("id", "memo"), validated.rowType().getFieldNames());
    String composed = adapter.composeWriteStatement(node,
        "(SELECT `id`, mask(`memo`) AS `memo` FROM `orders`)");
    assertTrue(composed.startsWith("INSERT OVERWRITE TABLE `orders`"), () -> composed);
    // round-trip：重组产物可被本解析器再次解析
    assertNotNull(adapter.parse(composed, 0));
  }

  @Test
  void topSelectValidatesWithFetchIntact() {
    SqlNode node = adapter.parse("SELECT TOP (5) `id` FROM `orders`", 0);
    ValidatedSql validated = adapter.validate(node, schema);
    assertEquals(java.util.List.of("id"), validated.rowType().getFieldNames());
  }

  @Test
  void rejectedVariantsFailClosedWithClearMessages() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("INSERT OVERWRITE TABLE `orders` PARTITION (x = 1) SELECT 1", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT TOP 5 PERCENT `id` FROM `orders`", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT TOP 5 `id` FROM `orders` LIMIT 3", 0));
  }
}
