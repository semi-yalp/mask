package io.sqlmask.policy;

/**
 * Policy subsystem error. Every policy failure is a configuration failure;
 * mask-core adapts this to SqlMaskException(CONFIG_ERROR) at the PEP boundary
 * with the message passed through verbatim.
 */
public class PolicyException extends RuntimeException {

  public PolicyException(String message) {
    super(message);
  }

  public PolicyException(String message, Throwable cause) {
    super(message, cause);
  }
}
