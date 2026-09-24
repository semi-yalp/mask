package io.sqlmask.query.rewrite;

import java.util.List;

/**
 * The query gateway's view of the rewrite step. Two implementations exist:
 * the HTTP client (standalone rewrite service deployments) and the in-process
 * adapter over the monolith's {@code RewriteContextRepository} — the gateway
 * cannot tell them apart, which is what keeps it light.
 */
public interface QueryRewriter {

  RewrittenQuery rewrite(String instance, String sql, String user, List<String> groups);
}
