package io.sqlmask.query.web;

import io.sqlmask.query.audit.QueryAuditor;
import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.CancelRegistry;
import io.sqlmask.query.service.QueryModels.QueryRequest;
import io.sqlmask.query.service.QueryModels.QueryResult;
import io.sqlmask.query.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

@RestController
public class QueryController {

  private final QueryService service;
  private final CancelRegistry cancels;
  private final QueryProperties props;
  private final QueryAuditor auditor;

  public QueryController(QueryService service, CancelRegistry cancels, QueryProperties props,
      QueryAuditor auditor) {
    this.service = service;
    this.cancels = cancels;
    this.props = props;
    this.auditor = auditor;
  }

  @PostMapping("/api/v1/query")
  public WebAsyncTask<ResponseEntity<QueryResult>> query(@RequestBody QueryRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request == null || request.instance() == null || request.instance().isBlank()) {
      throw new QueryException(QueryException.CONFIG_ERROR, "instance is required");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      throw new QueryException(QueryException.CONFIG_ERROR, "sql is required");
    }
    // 请求层的正负校验：QueryService 只钳硬上限
    if (request.maxRows() != null && request.maxRows() <= 0) {
      throw new QueryException(QueryException.CONFIG_ERROR, "maxRows must be positive");
    }
    // 已验证的控制台身份优先于调用方自报主体：审计与策略按真实登录者计
    io.sqlmask.auth.AuthPrincipal principal = io.sqlmask.auth.AuthTokens.principal(httpRequest);
    final String subjectUser = principal != null ? principal.username() : request.user();
    final java.util.List<String> subjectGroups =
        principal != null ? principal.groups() : request.groups();
    // WebAsyncTask 的超时兜底比语句超时略长；断连/容器超时先 cancel 再回 503
    CancelRegistry.Registration registration = cancels.begin();
    final String authKind = io.sqlmask.audit.AuditEvents.authKind(httpRequest);
    WebAsyncTask<ResponseEntity<QueryResult>> task =
        new WebAsyncTask<>(props.timeoutSeconds() * 1000L + 10_000L, () -> {
          try {
            QueryResult result = service.execute(request, registration);
            auditor.success(result, request.sql(), subjectUser, subjectGroups,
                httpRequest.getRemoteAddr(), authKind);
            return ResponseEntity.ok(result);
          } catch (QueryException e) {
            if (!e.rewritePhase()) {
              auditor.failure(e, request.sql(), subjectUser, subjectGroups,
                  httpRequest.getRemoteAddr(), request.instance(), authKind);
            }
            throw e;
          }
        });
    task.onTimeout(() -> {
      cancels.cancel(registration.id());
      return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).build();
    });
    task.onCompletion(() -> cancels.end(registration.id()));
    return task;
  }
}
