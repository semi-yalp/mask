package io.sqlmask.sql;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.validate.SqlValidator;

/**
 * A validated (and converted) statement.
 *
 * <p>{@code originalSql} is the dialect rendering of the parse tree captured
 * <em>before</em> validation, because Calcite's validator mutates the parse
 * tree in place (rewriting ORDER BY/LIMIT into the SELECT node and inserting
 * implicit casts). The snapshot guarantees the inner query of a wrapper is
 * the statement the user wrote.
 *
 * @param original    parse tree exactly as produced by the parser (may be
 *                    mutated by validation afterwards; do not render it)
 * @param originalSql rendered original SQL captured before validation
 * @param validated   validated analysis tree (may differ from {@code
 *                    original} once CTEs are expanded for lineage)
 * @param root        converted relational root of {@code validated}
 * @param validator   the validator that produced {@code validated}
 */
public record ValidatedSql(
    SqlNode original,
    String originalSql,
    SqlNode validated,
    RelRoot root,
    SqlValidator validator) {

  /** Validated output row type of the root query. */
  public RelDataType rowType() {
    return root.validatedRowType;
  }

  /** Converted relational expression of the root query. */
  public RelNode rel() {
    return root.rel;
  }
}
