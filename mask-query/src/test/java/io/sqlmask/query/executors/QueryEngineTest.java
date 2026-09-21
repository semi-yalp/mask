package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineTest {

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
    assertThat(QueryEngine.POSTGRESQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:postgresql://h:1234/db?sslmode=disable&connectTimeout=10&readOnly=true");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=DISABLED"
            + "&allowPublicKeyRetrieval=true&useCursorFetch=true");
    assertThat(QueryEngine.STARROCKS.jdbcUrl(conn("require")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=REQUIRED"
            + "&verifyServerCertificate=false&useCursorFetch=true");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=false");
    assertThatThrownBy(() -> QueryEngine.MYSQL.jdbcUrl(conn("prefer")))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void hiveAndSparksqlBuildHive2Urls() {
    ConnectionView plain = conn("disable");
    ConnectionView secure = conn("require");
    assertThat(QueryEngine.HIVE.jdbcUrl(plain))
        .isEqualTo("jdbc:hive2://h:1234/db");
    assertThat(QueryEngine.HIVE.jdbcUrl(secure))
        .isEqualTo("jdbc:hive2://h:1234/db;ssl=true");
    assertThat(QueryEngine.SPARKSQL.jdbcUrl(plain))
        .isEqualTo("jdbc:hive2://h:1234/db");
    ConnectionView emptyDb = new ConnectionView("h", 10000, "", "u", "REF", "disable", 10);
    assertThat(QueryEngine.SPARKSQL.jdbcUrl(emptyDb))
        .isEqualTo("jdbc:hive2://h:10000/");
    assertThat(QueryEngine.of(" Hive ")).isEqualTo(QueryEngine.HIVE);
    assertThat(QueryEngine.of("sparksql")).isEqualTo(QueryEngine.SPARKSQL);
    assertThat(QueryEngine.HIVE.dialect()).isEqualTo("hive");
    assertThat(QueryEngine.SPARKSQL.dialect()).isEqualTo("sparksql");
    assertThat(QueryEngine.HIVE.defaultPort()).isEqualTo(10000);
  }

  @Test
  void uppercaseRequireIsAcceptedAcrossEngines() {
    // sslmode 归一小写化比较：大写 REQUIRE 与 require 等价（防大小写回归钉）
    assertThat(QueryEngine.HIVE.jdbcUrl(conn("REQUIRE")))
        .isEqualTo("jdbc:hive2://h:1234/db;ssl=true");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("REQUIRE")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=REQUIRED"
            + "&verifyServerCertificate=false&useCursorFetch=true");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("REQUIRE")))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=true");
  }

  @Test
  void nullDatabaseIsToleratedOnHive2Url() {
    // metadata 契约下 database 非空；驱动层对 null 的容忍是防御性的
    ConnectionView nullDb = new ConnectionView("h", 10000, null, "u", "REF", "disable", 10);
    assertThat(QueryEngine.HIVE.jdbcUrl(nullDb)).isEqualTo("jdbc:hive2://h:10000/");
  }
}
