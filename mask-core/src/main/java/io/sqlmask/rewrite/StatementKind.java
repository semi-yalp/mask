package io.sqlmask.rewrite;

/** Coarse statement class of one rewritten statement; the query data plane
 * rejects everything but {@link #SELECT}. */
public enum StatementKind { SELECT, INSERT_SELECT, CTAS }
