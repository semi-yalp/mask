package io.sqlmask.dialect;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-instance syntax-extension overrides: a dialect can opt into TOP n or
 * INSERT OVERWRITE without a new dialect, while the defaults stay unchanged
 * (TOP off everywhere; INSERT OVERWRITE only on hive/sparksql).
 */
class DialectFeaturesTest {

  private static final String METADATA = """
      policies: {}
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
      """;

  private final RewriteEngine engine = new RewriteEngine();

  private LoadedConfig config(String dialect) {
    return new io.sqlmask.config.YamlConfigLoader().loadContent(
        METADATA.formatted(dialect), "metadata.yaml", dialect);
  }

  @Test
  void topNIsOffByDefaultAndOptInWorks() {
    LoadedConfig pg = config("postgresql");
    // default: TOP is refused
    assertThrows(SqlMaskException.class,
        () -> engine.rewrite(pg, "SELECT TOP (5) id FROM customer", "postgresql"));
    // opt-in: TOP parses and renders as the PostgreSQL-equivalent FETCH clause
    var statements = engine.rewrite(pg, "SELECT TOP (5) id FROM customer", "postgresql",
        new DialectFeatures(true, null));
    assertTrue(statements.get(0).rewrittenSql().contains("FETCH"),
        "rewritten sql should keep the row limit: " + statements.get(0).rewrittenSql());
  }

  @Test
  void insertOverwriteOptInForMysql() {
    LoadedConfig mysql = config("mysql");
    String sql = "INSERT OVERWRITE TABLE customer SELECT id FROM customer";
    // default: mysql refuses INSERT OVERWRITE
    assertThrows(SqlMaskException.class,
        () -> engine.rewrite(mysql, sql, "mysql"));
    // opt-in: parses and renders back
    var statements = engine.rewrite(mysql, sql, "mysql", new DialectFeatures(null, true));
    assertTrue(statements.get(0).rewrittenSql().contains("INSERT OVERWRITE TABLE"),
        statements.get(0).rewrittenSql());
  }

  @Test
  void insertOverwriteOptOutForHive() {
    LoadedConfig hive = config("hive");
    String sql = "INSERT OVERWRITE TABLE customer SELECT id FROM customer";
    // default: hive accepts
    assertTrue(engine.rewrite(hive, sql, "hive").size() == 1);
    // explicit opt-out refuses
    assertThrows(SqlMaskException.class,
        () -> engine.rewrite(hive, sql, "hive", new DialectFeatures(null, false)));
  }
}
