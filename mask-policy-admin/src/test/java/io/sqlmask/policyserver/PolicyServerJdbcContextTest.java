package io.sqlmask.policyserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JDBC/profile branch of the Spring context (gap §4.6 #6 / §6.4): a datasource
 * bean is supplied (embedded PostgreSQL, schema applied up front), so
 * {@code PolicyServerApplication.policyStore} must select {@link JdbcPolicyStore}
 * inside the container — verifying the JDBC branch wiring, the @Transactional
 * proxies (used by every JdbcPolicyStore mutation) and that a REST mutation lands
 * in real rows rather than a memoizing bean.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyServerJdbcContextTest {

  private static EmbeddedPostgres embedded;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyStore store;

  @Autowired
  private JdbcTemplate jdbc;

  @TestConfiguration
  static class EmbeddedDatabase {
    @Bean
    DataSource dataSource() throws IOException {
      embedded = EmbeddedPostgres.builder().start();
      DataSource ds = embedded.getPostgresDatabase();
      new ResourceDatabasePopulator(new ClassPathResource("sql/policy-schema.sql")).execute(ds);
      return ds;
    }
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  @Test
  void contextLoadsWithJdbcStoreBranch() {
    assertTrue(store instanceof JdbcPolicyStore,
        "with a JdbcTemplate available the store must be JdbcPolicyStore");
  }

  @Test
  void restMutationPersistsToSqlThroughTransactionalProxy() throws Exception {
    String name = "it_ctx_" + UUID.randomUUID().toString().substring(0, 8);

    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "name", name, "dialect", "postgresql",
                "tables", List.of(Map.of(
                    "catalog", "crm", "schema", "public", "name", "customer",
                    "columns", List.of(Map.of("name", "phone", "type", "varchar"))))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(name));

    // persisted at storage level, config_version seeded at 1 (transaction committed)
    Long version = jdbc.queryForObject(
        "SELECT config_version FROM policy_instance WHERE name = ?", Long.class, name);
    assertEquals(1L, version);
    Long tables = jdbc.queryForObject(
        "SELECT COUNT(*) FROM instance_table t JOIN policy_instance i ON i.id = t.instance_id"
            + " WHERE i.name = ?", Long.class, name);
    assertEquals(1L, tables);
    Long columns = jdbc.queryForObject(
        "SELECT COUNT(*) FROM instance_column c JOIN instance_table t ON c.table_id = t.id"
            + " JOIN policy_instance i ON i.id = t.instance_id WHERE i.name = ?", Long.class, name);
    assertEquals(1L, columns);
  }
}