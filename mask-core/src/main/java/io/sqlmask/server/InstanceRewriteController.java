package io.sqlmask.server;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.InstanceQueryAssembler;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.common.metadata.MetadataClient;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Instance-scoped rewrite for the query data plane: metadata tables come from
 * the metadata service snapshot, policies from the subject's compiled
 * effective config; the response adds per-statement {@code kind} so callers
 * can enforce read-only data planes. When the upstream services are not
 * configured (blank {@code base-url}) the endpoint fails closed with
 * {@code CONFIG_ERROR} instead of falling back to unmasked output.
 */
@RestController
@RequestMapping("/api/rewrite/instances")
public class InstanceRewriteController {

  private final RewriteEngine engine;
  private final ObjectProvider<MetadataClient> metadataClients;
  private final ObjectProvider<InstanceRewriteConfig.PolicySourceProvider> policySources;
  private final InstanceQueryAssembler assembler;

  public InstanceRewriteController(RewriteEngine engine,
      ObjectProvider<MetadataClient> metadataClients,
      ObjectProvider<InstanceRewriteConfig.PolicySourceProvider> policySources,
      InstanceQueryAssembler assembler) {
    this.engine = engine;
    this.metadataClients = metadataClients;
    this.policySources = policySources;
    this.assembler = assembler;
  }

  @PostMapping("/{name}")
  public RewriteController.RewriteResponse rewrite(@PathVariable("name") String name,
      @RequestBody InstanceRewriteRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request == null || request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    MetadataClient metadataClient = metadataClients.getIfAvailable();
    InstanceRewriteConfig.PolicySourceProvider sources = policySources.getIfAvailable();
    if (metadataClient == null || sources == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance-scoped rewrite requires sqlmask.metadata-service.base-url "
              + "and sqlmask.policy-service.base-url");
    }
    // a verified console identity wins over caller-asserted subject fields
    io.sqlmask.auth.AuthPrincipal principal = io.sqlmask.auth.AuthTokens.principal(httpRequest);
    String subjectUser = principal != null ? principal.username() : request.user();
    java.util.List<String> subjectGroups =
        principal != null ? principal.groups() : request.groups();
    MetadataClient.MetadataSnapshot snapshot = metadataClient.fetch(name);
    PolicyServiceConfigSource source = sources.forInstance(name);
    ConfigSource.ResolvedConfig effective = source.load(Subject.of(subjectUser, subjectGroups));
    LoadedConfig loaded = assembler.assemble(snapshot, effective);
    List<StatementRewrite> statements = engine.rewrite(loaded, request.sql(), snapshot.dialect());
    return new RewriteController.RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Per-statement rewrite request for one metadata-service instance. */
  public record InstanceRewriteRequest(String sql, String user, List<String> groups) {
  }
}
