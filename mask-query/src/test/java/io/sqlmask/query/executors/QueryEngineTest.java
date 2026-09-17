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
    assertThatThrownBy(() -> QueryEngine.of("hive"))
        .hasFieldOrPropertyWithValue("code", "UNSUPPORTED_ENGINE");
  }

  @Test
  void buildsUrlsLikeConnectionSpec() {
    assertThat(QueryEngine.POSTGRESQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:postgresql://h:1234/db?sslmode=disable&connectTimeout=10&readOnly=true");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=DISABLED&allowPublicKeyRetrieval=true");
    assertThat(QueryEngine.STARROCKS.jdbcUrl(conn("require")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=REQUIRED&verifyServerCertificate=false");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=false");
    assertThatThrownBy(() -> QueryEngine.MYSQL.jdbcUrl(conn("prefer")))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }
}
