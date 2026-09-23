package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.QueryModels.QueryResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Emits QUERY audit events; never throws into the request path. */
@Component
public class QueryAuditor {

  private static final String SERVICE = "mask-query";

  private final Consumer<AuditEvent> sink;

  public QueryAuditor(Consumer<AuditEvent> sink) {
    this.sink = sink;
  }

  /** authKind comes from the request context (LDAP bearer / API_KEY / ANONYMOUS). */
  public void success(QueryResult result, String originalSql, String user,
      List<String> groups, String sourceIp, String authKind) {
    try {
      sink.accept(AuditEvent.query(SERVICE, AuditEvent.SUCCESS, result.elapsedMs(), sourceIp,
          authKind, user, groups, result.instance(), dialectOf(result), result.masked(),
          result.rowFiltered(), originalSql, null, null,
          Map.of("engine", engineOf(result),
              "rowCount", result.rowCount(),
              "truncated", result.truncated())));
    } catch (RuntimeException ignored) {
      // audit must never break the query path
    }
  }

  public void failure(QueryException e, String originalSql, String user, List<String> groups,
      String sourceIp, String instance, String authKind) {
    try {
      sink.accept(AuditEvent.query(SERVICE, AuditEvent.FAILURE, null, sourceIp, authKind,
          user, groups, instance, null, null, null, originalSql, e.code(), e.getMessage(),
          null));
    } catch (RuntimeException ignored) {
      //同上
    }
  }

  private static String engineOf(QueryResult result) {
    return result.engine();
  }

  /** Top-level dialect derives from the engine exactly like
   * {@code QueryEngine.dialect()} (starrocks speaks the mysql dialect). */
  private static String dialectOf(QueryResult result) {
    return "starrocks".equals(result.engine()) ? "mysql" : result.engine();
  }
}
