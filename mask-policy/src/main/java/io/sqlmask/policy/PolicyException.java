package io.sqlmask.policy;

/**
 * Policy subsystem error. Every policy failure is a configuration failure;
 * mask-core adapts this to SqlMaskException(CONFIG_ERROR) at the PEP boundary
 * with the message passed through verbatim.
 *
 * <p>v3 (2026-09-21): rebuilt in place; contract byte-identical.
 */
public class PolicyException extends RuntimeException {

  public PolicyException(String message) {
    super(message);
  }

  public PolicyException(String message, Throwable cause) {
    super(message, cause);
  }
}