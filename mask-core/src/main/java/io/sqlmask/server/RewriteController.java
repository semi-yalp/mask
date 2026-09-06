package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL rewriting endpoint. The request carries its own YAML metadata and SQL
 * text; the whole input is processed atomically and any failure is reported
 * as a structured error.
 */
@RestController
@RequestMapping("/api")
public class RewriteController {

  private final RewriteEngine engine;

  public RewriteController(RewriteEngine engine) {
    this.engine = engine;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request) {
    if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml is required: paste the YAML configuration declaring tables, "
              + "columns and masking policies");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "sql is required: provide at least one SELECT statement");
    }
    String dialect = request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    List<StatementRewrite> statements = engine.rewrite(request.metadataYaml(), request.sql(), dialect);
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Per-statement rewrite request. */
  public record RewriteRequest(String metadataYaml, String sql, String dialect) {
  }

  /** Per-statement rewrite response plus the combined script. */
  public record RewriteResponse(List<StatementRewrite> statements, String rewrittenSql) {
  }
}
