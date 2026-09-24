package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Egress guard for admin-initiated database connections: the metadata pull
 * (mask-core) and collection (mask-metadata) endpoints accept a caller- or
 * store-supplied host, which without a check doubles as an internal network
 * probe. Link-local addresses (169.254.0.0/16, fe80::/10) are the cloud
 * metadata endpoints; connecting to them can exfiltrate cloud credentials, so
 * they are denied unless the guard is explicitly switched off. Loopback stays
 * allowed — local development collects from 127.0.0.1.
 */
public final class NetworkGuard {

  public enum Policy { LINK_LOCAL, OFF }

  private NetworkGuard() {}

  /**
   * Parses {@code link-local} (the default) or {@code off}; anything else is a
   * config error rather than a silent fallback to the stricter policy.
   */
  public static Policy parsePolicy(String value) {
    if (value == null || value.isBlank()) {
      return Policy.LINK_LOCAL;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "link-local" -> Policy.LINK_LOCAL;
      case "off" -> Policy.OFF;
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown network guard policy '" + value + "' (supported: link-local, off)");
    };
  }

  /**
   * Resolves {@code host} and rejects it under the policy. An unresolvable
   * host fails closed: a collection against a name that does not resolve has
   * no legitimate outcome anyway.
   */
  public static void checkHost(String host, Policy policy) {
    if (policy == Policy.OFF) {
      return;
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot resolve database host '" + host + "'; connection refused");
    }
    for (InetAddress address : addresses) {
      if (address.isLinkLocalAddress()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "database host '" + host + "' resolves to a link-local address ("
                + address.getHostAddress() + "); connecting to it is denied "
                + "(set the network guard policy to 'off' to override)");
      }
    }
  }
}
