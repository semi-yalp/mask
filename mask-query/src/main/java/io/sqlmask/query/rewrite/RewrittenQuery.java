package io.sqlmask.query.rewrite;

import java.util.List;

/** The rewrite outcome of a query request. */
public record RewrittenQuery(List<StatementView> statements) {
}
