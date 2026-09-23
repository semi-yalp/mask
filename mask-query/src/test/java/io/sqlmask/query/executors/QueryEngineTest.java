package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineTest {

  private static final int TIMEOUT = 30;

  private static ConnectionView conn(String sslmode) {
    return new ConnectionView("h", 1234, "db", "u", "REF", sslmode, 10);
  }

  @Test
  void resolvesEngineAndDialect() {
    assertThat(QueryEngine.of(" StarRocks ")).isEqualTo(QueryEngine.STARROCKS);
    assertThat(QueryEngine.STARROCKS.dialect()).isEqualTo("mysql");
    assertThatThrownBy(() -> QueryEngine.of("oracle"))
        .hasFieldOrPropertyWithValue("code", "UNSUPPORTED_ENGINE");
  }

  @Test
  void buildsUrlsLikeConnectionSpec() {
    // socketTimeout 略高于语句超时（PG 秒 / Connector-J、HiveServer2 毫秒）
    assertThat(QueryEngine.POSTGRESQL.jdbcUrl(conn("disable"), TIMEOUT))
        .isEqualTo("jdbc:postgresql://h:1234/db?sslmode=disable&connectTimeout=10"
            + "&socketTimeout=35&readOnly=true");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("disable"), TIMEOUT))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&socketTimeout=35000"
            + "&sslMode=DISABLED&allowPublicKeyRetrieval=true&useCursorFetch=true");
    assertThat(QueryEngine.STARROCKS.jdbcUrl(conn("require"), TIMEOUT))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&socketTimeout=35000"
            + "&sslMode=REQUIRED&verifyServerCertificate=false&useCursorFetch=true");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("disable"), TIMEOUT))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=false");
    assertThatThrownBy(() -> QueryEngine.MYSQL.jdbcUrl(conn("prefer"), TIMEOUT))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void pgSslmodeIsWhitelistedToo() {
    // PG 分支此前不校验 sslmode：任意串会直接拼进 URL，需要与其他引擎同口径
    assertThat(QueryEngine.POSTGRESQL.jdbcUrl(conn("require"), TIMEOUT))
        .contains("sslmode=require");
    assertThatThrownBy(() -> QueryEngine.POSTGRESQL.jdbcUrl(conn("prefer"), TIMEOUT))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void jdbcParameterSmugglingIsRejected() {
    // host/database 白名单：挡掉借 URL 语法注入额外 JDBC 参数的实例配置
    for (String hostile : new String[] {"h?preferQueryMode=simple", "h/x", "h&x=1", "h x",
        "h;x"}) {
      ConnectionView evil = new ConnectionView(hostile, 1234, "db", "u", "REF", "disable", 10);
      assertThatThrownBy(() -> QueryEngine.POSTGRESQL.jdbcUrl(evil, TIMEOUT))
          .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
    }
    ConnectionView evilDb = new ConnectionView("h", 1234, "db?sslmode=1", "u", "REF", "disable", 10);
    assertThatThrownBy(() -> QueryEngine.POSTGRESQL.jdbcUrl(evilDb, TIMEOUT))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void hiveAndSparksqlBuildHive2Urls() {
    ConnectionView plain = conn("disable");
    ConnectionView secure = conn("require");
    assertThat(QueryEngine.HIVE.jdbcUrl(plain, TIMEOUT))
        .isEqualTo("jdbc:hive2://h:1234/db?connectTimeout=10000&socketTimeout=35000");
    assertThat(QueryEngine.HIVE.jdbcUrl(secure, TIMEOUT))
        .isEqualTo("jdbc:hive2://h:1234/db;ssl=true?connectTimeout=10000&socketTimeout=35000");
    assertThat(QueryEngine.SPARKSQL.jdbcUrl(plain, TIMEOUT))
        .isEqualTo("jdbc:hive2://h:1234/db?connectTimeout=10000&socketTimeout=35000");
    ConnectionView emptyDb = new ConnectionView("h", 10000, "", "u", "REF", "disable", 10);
    assertThat(QueryEngine.SPARKSQL.jdbcUrl(emptyDb, TIMEOUT))
        .isEqualTo("jdbc:hive2://h:10000/?connectTimeout=10000&socketTimeout=35000");
    assertThat(QueryEngine.of(" Hive ")).isEqualTo(QueryEngine.HIVE);
    assertThat(QueryEngine.of("sparksql")).isEqualTo(QueryEngine.SPARKSQL);
    assertThat(QueryEngine.HIVE.dialect()).isEqualTo("hive");
    assertThat(QueryEngine.SPARKSQL.dialect()).isEqualTo("sparksql");
    assertThat(QueryEngine.HIVE.defaultPort()).isEqualTo(10000);
  }

  @Test
  void uppercaseRequireIsAcceptedAcrossEngines() {
    // sslmode 归一小写化比较：大写 REQUIRE 与 require 等价（防大小写回归钉）
    assertThat(QueryEngine.HIVE.jdbcUrl(conn("REQUIRE"), TIMEOUT))
        .isEqualTo("jdbc:hive2://h:1234/db;ssl=true?connectTimeout=10000&socketTimeout=35000");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("REQUIRE"), TIMEOUT))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&socketTimeout=35000"
            + "&sslMode=REQUIRED&verifyServerCertificate=false&useCursorFetch=true");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("REQUIRE"), TIMEOUT))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=true");
  }

  @Test
  void nullDatabaseIsToleratedOnHive2Url() {
    // metadata 契约下 database 非空；驱动层对 null 的容忍是防御性的
    ConnectionView nullDb = new ConnectionView("h", 10000, null, "u", "REF", "disable", 10);
    assertThat(QueryEngine.HIVE.jdbcUrl(nullDb, TIMEOUT))
        .isEqualTo("jdbc:hive2://h:10000/?connectTimeout=10000&socketTimeout=35000");
  }

  @Test
  void nonHiveEnginesRequireADatabase() {
    ConnectionView nullDb = new ConnectionView("h", 5432, null, "u", "REF", "disable", 10);
    assertThatThrownBy(() -> QueryEngine.POSTGRESQL.jdbcUrl(nullDb, TIMEOUT))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }
}
