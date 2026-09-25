package io.sqlmask.server.rewrite;

import io.sqlmask.dialect.DialectFeatures;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.server.InheritedPolicyRegistrar;
import io.sqlmask.server.RewriteController.RewriteResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Subject-aware instance rewrite over the {@link RewriteContextRepository}:
 * one place that resolves the caller's subject, assembles the compiled
 * context and runs the kernel. Both the REST surface
 * ({@code POST /api/rewrite/instances/{name}}) and the in-process query
 * gateway go through it, so they can never drift apart.
 *
 * <p>复制表策略继承(arch-v2 合并 rr):改写产物携带继承列(inheritOnCopy)时,
 * 返回前调用 {@link InheritedPolicyRegistrar} 把目标表结构与列策略注册到策略/元
 * 数据服务;任一注册失败即整体失败(fail-closed)。legacy 通道(inline YAML/CLI)
 * 没有注册能力,在 {@code RewriteController} 显式拒绝继承语句。</p>
 */
@Service
public class InstanceRewriteService {

  private final RewriteEngine engine;
  private final RewriteContextRepository contexts;
  private final MetadataService metadata;
  private final ObjectProvider<InheritedPolicyRegistrar> registrar;

  public InstanceRewriteService(RewriteEngine engine, RewriteContextRepository contexts,
                                MetadataService metadata,
                                ObjectProvider<InheritedPolicyRegistrar> registrar) {
    this.engine = engine;
    this.contexts = contexts;
    this.metadata = metadata;
    this.registrar = registrar;
  }

  public RewriteResponse rewrite(String instance, String sql, String user, List<String> groups) {
    if (sql == null || sql.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    RewriteContextRepository.Context context = contexts.load(instance, Subject.of(user, groups));
    List<StatementRewrite> statements =
        engine.rewrite(context.config(), sql, context.dialect(), featuresOf(instance));
    // 复制表语句改写发现继承列后,在返回前自动注册目标表结构与列策略;
    // 注册器未装配(不可能,同包 @Component)或上游未配置时注册器自身 fail-closed
    InheritedPolicyRegistrar inheritedRegistrar = registrar.getIfAvailable();
    if (inheritedRegistrar != null
        && statements.stream().anyMatch(s -> !s.inheritedColumns().isEmpty())) {
      inheritedRegistrar.register(instance, user, statements);
    }
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Instance-scoped syntax-extension overrides; StarRocks (served through
   * the mysql dialect) defaults to INSERT OVERWRITE support because StarRocks
   * 3.x has the statement while stock MySQL does not. */
  private DialectFeatures featuresOf(String instance) {
    try {
      var row = metadata.get(instance);
      boolean insertOverwrite = row.insertOverwrite() != null
          ? row.insertOverwrite()
          : "starrocks".equals(row.effectiveEngine());
      return new DialectFeatures(row.topN(), insertOverwrite);
    } catch (SqlMaskException e) {
      // unknown instance surfaces from the context load with the right code;
      // here the feature probe simply falls back to dialect defaults
      return DialectFeatures.DEFAULTS;
    }
  }
}
