package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.List;
import java.util.Map;

/** One dataMask policy item: who it applies to and the masking UDF call. */
public record DataMaskItem(SubjectSelector selector, String udf, List<Object> arguments) {

  public DataMaskItem {
    if (selector == null) {
      throw new PolicyException("dataMaskItem requires a subject selector (users/groups)");
    }
    if (udf == null || udf.isBlank()) {
      throw new PolicyException("dataMaskItem requires a udf identifier");
    }
    // validate before copying: List.copyOf rejects null elements with an NPE,
    // and the null argument deserves the scalar message instead
    java.util.List<Object> checked = new java.util.ArrayList<>();
    if (arguments != null) {
      for (Object argument : arguments) {
        if (argument == null || argument instanceof Map<?, ?> || argument instanceof List<?>) {
          throw new PolicyException("dataMaskItem udf '" + udf + "': argument '" + argument
              + "' must be a scalar (string, number or boolean)");
        }
        checked.add(argument);
      }
    }
    arguments = List.copyOf(checked);
  }
}
