package io.sqlmask.server.gateway;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.QueryRewriter;
import io.sqlmask.query.rewrite.RewrittenQuery;
import io.sqlmask.query.rewrite.StatementView;
import io.sqlmask.query.service.QueryService;
import io.sqlmask.server.rewrite.InstanceRewriteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * In-process wiring of the query gateway: the instance directory reads the
 * metadata domain directly, the rewrite step calls the same
 * {@link InstanceRewriteService} the REST surface exposes, and the JDBC
 * connection factory keeps the engine-url and login-timeout discipline of
 * the standalone query service.
 */
@Configuration
public class QueryGatewayConfig {

  /** Backs the gateway with the metadata domain (same fetch contract). */
  @Bean
  QueryService.InstanceDirectory queryInstanceDirectory(MetadataService metadata) {
    return name -> {
      InstanceRow row = metadata.get(name);
      ConnectionInfo c = row.connection();
      ConnectionView connection = c == null ? null : new ConnectionView(
          c.host(), c.port(), c.database(), c.dbUser(), c.passwordRef(),
          c.sslmode(), c.connectTimeoutSeconds());
      return new InstanceView(row.name(), row.effectiveEngine(), row.dialect(),
          row.metadataVersion(), connection,
          row.submitter(), row.onRewriteFailure(), row.topN(), row.insertOverwrite());
    };
  }

  /** The gateway's rewrite step is the instance-rewrite service itself. */
  @Bean
  QueryRewriter localQueryRewriter(InstanceRewriteService rewrites) {
    return (instance, sql, user, groups) -> {
      var response = rewrites.rewrite(instance, sql, user, groups);
      List<StatementView> statements = response.statements().stream()
          .map(s -> new StatementView(s.ordinal(), s.originalSql(), s.rewrittenSql(),
              s.masked(), s.rowFiltered(), s.kind().name()))
          .toList();
      return new RewrittenQuery(statements);
    };
  }

  @Bean
  QueryService.ConnectionFactory queryConnectionFactory(QueryProperties props) {
    return (engine, c, password) -> {
      // Trino's driver has no connect-timeout URL property; the global login
      // timeout is the connect backstop for every engine.
      int loginTimeout = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
      java.sql.DriverManager.setLoginTimeout(loginTimeout);
      return java.sql.DriverManager.getConnection(
          engine.jdbcUrl(c, props.timeoutSeconds()), c.dbUser(), password);
    };
  }
}
