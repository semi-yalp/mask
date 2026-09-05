package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.LineageStatus;
import io.sqlmask.lineage.OutputLineage;
import io.sqlmask.policy.PolicySelector;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete rewriting plan for one statement: the original query plus one
 * {@link OutputRewrite} per output column, in output order. A wrapper is
 * required only when at least one output column matched a policy.
 */
public record RewritePlan(List<OutputRewrite> outputs) {

  public RewritePlan {
    outputs = List.copyOf(outputs);
  }

  public boolean requiresWrapper() {
    return outputs.stream().anyMatch(OutputRewrite::isMasked);
  }

  /**
   * Builds the plan from the root output lineage. Outputs with
   * {@link LineageStatus#UNKNOWN} lineage fail; constants and columns
   * without a matching policy pass through unchanged.
   */
  public static RewritePlan of(List<OutputLineage> lineage, PolicySelector selector) {
    List<OutputRewrite> outputs = new ArrayList<>();
    for (OutputLineage output : lineage) {
      if (output.status() == LineageStatus.UNKNOWN) {
        throw new SqlMaskException(SqlMaskException.Code.LINEAGE_UNKNOWN,
            "output column " + output.ordinal() + " ('" + output.outputName()
                + "') has no safely traceable origin; cannot rewrite this statement");
      }
      if (output.status() == LineageStatus.RESOLVED) {
        selector.select(output.origins()).ifPresentOrElse(
            policy -> outputs.add(OutputRewrite.masked(output.ordinal(), output.outputName(), policy)),
            () -> outputs.add(OutputRewrite.passthrough(output.ordinal(), output.outputName())));
      } else {
        outputs.add(OutputRewrite.passthrough(output.ordinal(), output.outputName()));
      }
    }
    return new RewritePlan(outputs);
  }
}
