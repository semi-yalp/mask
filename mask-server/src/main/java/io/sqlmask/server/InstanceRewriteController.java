package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.server.rewrite.InstanceRewriteService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Instance-scoped rewrite for the query data plane: metadata comes from the
 * metadata domain, policies from the subject's compiled effective config —
 * both assembled in-process by the
 * {@link io.sqlmask.server.rewrite.RewriteContextRepository}. The response
 * adds per-statement {@code kind} so callers can enforce read-only data
 * planes.
 */
@RestController
@RequestMapping("/api/rewrite/instances")
public class InstanceRewriteController {

  private final InstanceRewriteService rewrites;

  public InstanceRewriteController(InstanceRewriteService rewrites) {
    this.rewrites = rewrites;
  }

  @PostMapping("/{name}")
  public RewriteController.RewriteResponse rewrite(@PathVariable("name") String name,
      @RequestBody InstanceRewriteRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request == null || request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    // a verified console identity wins over caller-asserted subject fields
    io.sqlmask.auth.AuthPrincipal principal = io.sqlmask.auth.AuthTokens.principal(httpRequest);
    String subjectUser = principal != null ? principal.username() : request.user();
    List<String> subjectGroups = principal != null ? principal.groups() : request.groups();
    return rewrites.rewrite(name, request.sql(), subjectUser, subjectGroups);
  }

  /** Per-statement rewrite request for one instance. */
  public record InstanceRewriteRequest(String sql, String user, List<String> groups) {
  }
}
