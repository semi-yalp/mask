package io.sqlmask.auth;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * LDAP username/password authentication over the UnboundID SDK. One fresh
 * connection per attempt (stateless, thread-safe):
 *
 * <ol>
 *   <li>optional manager bind, then search the user filter — the login name is
 *       escaped via {@link Filter#format}, never string-concatenated into the
 *       filter (no LDAP injection);</li>
 *   <li>bind as the found user DN with the offered password — the only
 *       trustworthy password check LDAP offers. Empty passwords are rejected
 *       up front so an anonymous bind can never read as success;</li>
 *   <li>resolve groups: group search under {@code groupSearchBase} when
 *       configured, else the user entry's {@code memberOf} attribute (AD).
 *       Group identity is the first RDN value of the group DN
 *       ({@code cn=mask-admins,ou=groups,...} → {@code mask-admins});</li>
 *   <li>map groups to a {@link Role} via the configured admin/auditor sets.</li>
 * </ol>
 *
 * <p>Fail closed: unreachable LDAP is {@code LDAP_UNAVAILABLE}, never a
 * fallback. Passwords live in a {@code char[]} and are never logged or put
 * into exceptions.
 */
public final class LdapAuthenticator {

  private final AuthConfig config;

  public LdapAuthenticator(AuthConfig config) {
    if (!config.ldapEnabled()) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "MASK_AUTH_LDAP_URL and MASK_AUTH_LDAP_BASE_DN are required");
    }
    this.config = config;
  }

  public AuthPrincipal authenticate(String username, char[] password) {
    if (username == null || username.isBlank()) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
    }
    if (password == null || password.length == 0) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
    }
    URI uri = parseLdapUrl(config.ldapUrl());
    LDAPConnectionOptions options = new LDAPConnectionOptions();
    options.setConnectTimeoutMillis((int) config.connectTimeout().toMillis());
    options.setResponseTimeoutMillis((int) config.responseTimeout().toMillis());

    try (LDAPConnection connection = new LDAPConnection(options, uri.getHost(), uri.getPort())) {
      if (config.ldapBindDn() != null && !config.ldapBindDn().isBlank()) {
        connection.bind(config.ldapBindDn(), config.ldapBindPassword() == null ? "" : config.ldapBindPassword());
      }

      Filter userFilter = Filter.create(
          config.userFilter().replace("{0}", escapeFilterValue(username)));
      SearchResult search = connection.search(config.userSearchBase(), SearchScope.SUB, userFilter);
      if (search.getEntryCount() == 0) {
        // same answer as a wrong password: no user enumeration
        throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
      }
      if (search.getEntryCount() > 1) {
        throw new AuthException(AuthException.Code.CONFIG_ERROR,
            "user filter matched " + search.getEntryCount() + " entries for one login name");
      }
      SearchResultEntry userEntry = search.getSearchEntries().get(0);

      // resolve groups while still bound as the service account: ordinary
      // users may lack rights to search the group tree
      List<String> groups = resolveGroups(connection, userEntry);

      try {
        connection.bind(userEntry.getDN(), new String(password));
      } catch (LDAPException e) {
        if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
          throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
        }
        throw unavailable(e);
      }

      String displayName = firstNonBlank(
          userEntry.getAttributeValue("displayName"),
          userEntry.getAttributeValue("cn"),
          username);
      return new AuthPrincipal(username, displayName, config.mapRole(groups), groups);
    } catch (LDAPException e) {
      throw unavailable(e);
    }
  }

  private List<String> resolveGroups(LDAPConnection connection, SearchResultEntry userEntry)
      throws LDAPException {
    List<String> groups = new ArrayList<>();
    if (!config.groupSearchBase().isBlank()) {
      Filter groupFilter = Filter.create(
          config.groupFilter().replace("{dn}", escapeFilterValue(userEntry.getDN())));
      SearchResult result = connection.search(config.groupSearchBase(), SearchScope.SUB, groupFilter);
      for (SearchResultEntry entry : result.getSearchEntries()) {
        String name = rdnValue(entry.getDN());
        if (name != null) {
          groups.add(name);
        }
      }
    } else {
      String[] memberOf = userEntry.getAttributeValues("memberOf");
      if (memberOf != null) {
        for (String groupDn : memberOf) {
          String name = rdnValue(groupDn);
          if (name != null) {
            groups.add(name);
          }
        }
      }
    }
    return groups;
  }

  private static String rdnValue(String dn) {
    try {
      String[] values = new DN(dn).getRDN().getAttributeValues();
      return values.length > 0 ? values[0] : null;
    } catch (LDAPException e) {
      return null;
    }
  }

  /**
   * RFC 4515 assertion-value escaping: the login name and the resolved DN are
   * attacker-controlled strings substituted into a filter template, so every
   * metacharacter ({@code * ( ) \ NUL} and control chars) must be hex-escaped
   * — a crafted {@code *)(objectClass=*} login must not widen the search.
   */
  private static String escapeFilterValue(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\5C");
        case '*' -> sb.append("\\2A");
        case '(' -> sb.append("\\28");
        case ')' -> sb.append("\\29");
        default -> {
          if (c < 0x20 || c == 0x7F) {
            sb.append(String.format("\\%02X", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static URI parseLdapUrl(String url) {
    try {
      URI uri = URI.create(url.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
      if (!"ldap".equals(scheme) && !"ldaps".equals(scheme)) {
        throw new IllegalArgumentException("scheme must be ldap or ldaps");
      }
      if (uri.getHost() == null) {
        throw new IllegalArgumentException("host is required");
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR, "MASK_AUTH_LDAP_URL is invalid: " + url);
    }
  }

  private static AuthException unavailable(LDAPException e) {
    return new AuthException(AuthException.Code.LDAP_UNAVAILABLE,
        "LDAP server unavailable: " + e.getMessage(), e);
  }
}
