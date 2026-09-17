package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.QueryModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAuditorTest {

  @Test
  void neverThrowsAndCarriesSqlAndCodes() {
    List<AuditEvent> captured = new ArrayList<>();
    QueryAuditor auditor = new QueryAuditor(captured::add);
    auditor.success(new QueryModels.QueryResult("pg", "postgresql", List.of(),
        List.of(List.of("x")), 1, false, true, false, 5L, null),
        "SELECT phone FROM customer", "alice", List.of("devs"), "127.0.0.1");
    auditor.failure(new QueryException(QueryException.QUERY_TIMEOUT, "t"),
        "SELECT pg_sleep(5)", "alice", List.of(), "127.0.0.1", "pg_prod");
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0).outcome()).isEqualTo("SUCCESS");
    assertThat(captured.get(0).originalSql()).isEqualTo("SELECT phone FROM customer");
    assertThat(captured.get(1).errorCode()).isEqualTo("QUERY_TIMEOUT");
    assertThat(captured.get(1).instance()).isEqualTo("pg_prod");
  }
}
