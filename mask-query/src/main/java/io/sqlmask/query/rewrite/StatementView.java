package io.sqlmask.query.rewrite;

/** One rewritten statement of a query request. */
public record StatementView(int ordinal, String originalSql, String rewrittenSql,
    boolean masked, boolean rowFiltered, String kind) {
}
