package io.sqlmask.query.submit;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.service.CancelRegistry;

/**
 * Everything an execution backend needs: the resolved connection view, the
 * (possibly bypassed) SQL to run, the guard-rail settings and the cancel
 * handle. Submitters assemble the wire {@code QueryResult} themselves —
 * masked/rowFiltered/bypassed flags travel with the request so the result
 * never depends on mutable service state.
 */
public record SubmitRequest(String instance, String engine, ConnectionView connection,
    String sql, int effectiveMaxRows, QueryProperties props,
    CancelRegistry.Registration registration, boolean masked, boolean rowFiltered,
    boolean includeRewrittenSql, long startNanos, Boolean rewrittenBypassed) {
}
