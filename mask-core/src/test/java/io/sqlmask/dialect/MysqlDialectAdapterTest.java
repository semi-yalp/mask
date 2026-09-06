package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDialectAdapterTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: shop
            schema: app
            name: orders
            columns:
              - name: id
                type: bigint
              - name: amount
                type: decimal(10,2)
              - name: taken_at
                type: datetime
              - name: memo
                type: text
              - name: state
                type: tinyint
      policies: {}
      """;

  private final MysqlDialectAdapter adapter = new MysqlDialectAdapter();
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "mysql-test.yaml", "mysql"));
  }

  @Test
  void parsesWithBacktickQuoting() {
    assertEquals(SqlKind.SELECT, adapter.parse("SELECT `memo` FROM `orders`", 0).getKind());
  }

  @Test
  void doubleQuoteIsRejectedUnderBacktickQuoting() {
    // 观察钉定（Calcite 语法事实，brief 原名 doubleQuoteIsAStringLiteralNotIdentifier）：
    // MySQL 默认（ANSI_QUOTES 关闭）把 "..." 当字符串，但 Calcite 1.42 在
    // quoting=BACK_TICK 下（标准与 babel 解析器皆然）不接受 "..." 作为字符串字面量，
    // 也没有解析器配置可以打开；本工具选择 fail-closed，直接拒绝该写法。
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT \"memo\" FROM orders", 0));
  }

  @Test
  void unquotedIdentifiersMatchCaseInsensitively() {
    // 大小写不敏感解析命中小写声明列（校验通过即证明匹配成功）；
    // 输出列名保留书写原样（UNCHANGED casing 下 Calcite 用解析到的拼写命名输出列）
    SqlNode parsed = adapter.parse("SELECT MEMO, TAKEN_AT FROM ORDERS", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("MEMO", "TAKEN_AT"), validated.rowType().getFieldNames());
  }

  @Test
  void mysqlTypeMapping() {
    MysqlTypeResolver resolver = new MysqlTypeResolver();
    assertEquals(SqlTypeName.TIMESTAMP, resolver.parseColumn("c", "datetime").sqlTypeName());
    assertEquals(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
        resolver.parseColumn("c", "timestamp").sqlTypeName());
    assertEquals(SqlTypeName.TINYINT, resolver.parseColumn("c", "tinyint").sqlTypeName());
    assertEquals(SqlTypeName.VARCHAR, resolver.parseColumn("c", "longtext").sqlTypeName());
    assertEquals(SqlTypeName.INTEGER, resolver.parseColumn("c", "mediumint").sqlTypeName());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> resolver.parseColumn("c", "json"));
    assertTrue(e.getMessage().contains("mysql"), () -> e.getMessage());
  }

  @Test
  void havingAliasAllowedByMysqlConformance() {
    // MYSQL_5 允许 HAVING 引用 SELECT 别名（DEFAULT 会拒绝）
    SqlNode parsed = adapter.parse(
        "SELECT state, count(*) AS c FROM orders GROUP BY state HAVING c > 1", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void mysqlLibraryFunctionsResolve() {
    // SqlLibrary.MYSQL 中的函数按真实签名解析
    SqlNode parsed = adapter.parse("SELECT IFNULL(state, 0) AS s FROM orders", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void ctasWithoutAsFailsAtParse() {
    // 已按 D1 偏差用 babel 解析器实测：CREATE TABLE 后缺 AS 时 SELECT 不是合法
    // 的接续 token（Encountered "SELECT"），仍按 brief 预期在解析期失败
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("CREATE TABLE t SELECT memo FROM orders", 1));
  }

  @Test
  void limitOffsetCommaFormParsesUnderMysql5Conformance() {
    // 观察钉定（Calcite 语法事实，brief 原名 limitOffsetCommaFormFailsAtParse）：
    // MYSQL_5 一致性 isLimitStartCountAllowed()=true，"LIMIT start, count" 是合法
    // 语法（MySQL 5.x 语义），解析为 ORDER_BY，不会抛 PARSE_ERROR
    assertEquals(SqlKind.ORDER_BY,
        adapter.parse("SELECT memo FROM orders LIMIT 5, 10", 0).getKind());
  }

  @Test
  void insertValuesIsPlainSelectFreeWrite() {
    assertEquals(SqlKind.INSERT,
        adapter.parse("INSERT INTO app.orders (id) VALUES (1)", 2).getKind());
  }
}
