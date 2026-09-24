package io.sqlmask.server.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.server.RewriteController.RewriteResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Subject-aware instance rewrite over the {@link RewriteContextRepository}:
 * one place that resolves the caller's subject, assembles the compiled
 * context and runs the kernel. Both the REST surface
 * ({@code POST /api/rewrite/instances/{name}}) and the in-process query
 * gateway go through it, so they can never drift apart.
 */
@Service
public class InstanceRewriteService {

  private final RewriteEngine engine;
  private final RewriteContextRepository contexts;

  public InstanceRewriteService(RewriteEngine engine, RewriteContextRepository contexts) {
    this.engine = engine;
    this.contexts = contexts;
  }

  public RewriteResponse rewrite(String instance, String sql, String user, List<String> groups) {
    if (sql == null || sql.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    RewriteContextRepository.Context context = contexts.load(instance, Subject.of(user, groups));
    List<StatementRewrite> statements = engine.rewrite(context.config(), sql, context.dialect());
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }
}
