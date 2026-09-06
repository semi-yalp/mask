package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

/** One rowFilter policy item: who it applies to and the filter expression. */
public record RowFilterItem(SubjectSelector selector, String filterExpr) {

  public RowFilterItem {
    if (selector == null) {
      throw new PolicyException("rowFilterItem requires a subject selector (users/groups)");
    }
    if (filterExpr == null || filterExpr.isBlank()) {
      throw new PolicyException("rowFilterItem requires a non-blank filterExpr");
    }
  }
}
