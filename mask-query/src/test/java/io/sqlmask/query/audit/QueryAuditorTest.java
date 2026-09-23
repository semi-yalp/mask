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
  void starrocksEngineMapsTopLevelDialectToMysql() {
    List<AuditEvent> captured = new ArrayList<>();
    QueryAuditor auditor = new QueryAuditor(captured::add);
    auditor.success(new QueryModels.QueryResult("sr", "starrocks", List.of(),
        List.of(List.of(1)), 1, false, false, false, 3L, null),
        "SELECT 1", "bob", List.of(), "127.0.0.1", "API_KEY");
    assertThat(captured).hasSize(1);
    // 顶层 dialect 由 engine 推导（starrocks→mysql，与 QueryEngine.dialect 一致），
    // detail.engine 保留原始 engine
    assertThat(captured.get(0).dialect()).isEqualTo("mysql");
    assertThat(captured.get(0).detail()).containsEntry("engine", "starrocks");
  }

  @Test
  void neverThrowsAndCarriesSqlAndCodes() {
    List<AuditEvent> captured = new ArrayList<>();
    QueryAuditor auditor = new QueryAuditor(captured::add);
    auditor.success(new QueryModels.QueryResult("pg", "postgresql", List.of(),
        List.of(List.of("x")), 1, false, true, false, 5L, null),
        "SELECT phone FROM customer", "alice", List.of("devs"), "127.0.0.1", "LDAP");
    auditor.failure(new QueryException(QueryException.QUERY_TIMEOUT, "t"),
        "SELECT pg_sleep(5)", "alice", List.of(), "127.0.0.1", "pg_prod", "API_KEY");
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0).outcome()).isEqualTo("SUCCESS");
    assertThat(captured.get(0).originalSql()).isEqualTo("SELECT phone FROM customer");
    assertThat(captured.get(0).authKind()).isEqualTo("LDAP");
    assertThat(captured.get(1).errorCode()).isEqualTo("QUERY_TIMEOUT");
    assertThat(captured.get(1).authKind()).isEqualTo("API_KEY");
    assertThat(captured.get(1).instance()).isEqualTo("pg_prod");
  }
}
