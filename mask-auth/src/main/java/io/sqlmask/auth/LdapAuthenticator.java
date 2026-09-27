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
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.SSLUtil;

import java.net.URI;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.TrustManagerFactory;

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
 *
 * <p>ldaps:// connections verify both the certificate chain (the configured
 * truststore, else the JVM default) and the server hostname: UnboundID does
 * not check hostnames unless a {@link HostNameSSLSocketVerifier} is installed,
 * and without one a man-in-the-middle holding any publicly-trusted certificate
 * could harvest directory bind credentials.
 */
public final class LdapAuthenticator {

  /**
   * Shared verifier: hostname (SAN, else CN) must match the host dialled
   * (IP-literal hosts need an IP SAN). {@code allowWildcards=true} keeps the
   * standard RFC 6125 wildcard semantics ({@code *.example.com} covers
   * {@code ldap.example.com}). Stateless and thread-safe.
   */
  private static final HostNameSSLSocketVerifier LDAPS_HOSTNAME_VERIFIER =
      new HostNameSSLSocketVerifier(true);

  private final AuthConfig config;
  private final SSLUtil injectedLdapsSslUtil;

  public LdapAuthenticator(AuthConfig config) {
    this(config, null);
  }

  /** Tests inject a trust-all SSLUtil here; production passes null (config/JVM trust). */
  LdapAuthenticator(AuthConfig config, SSLUtil ldapsSslUtilForTests) {
    if (!config.ldapEnabled()) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "MASK_AUTH_LDAP_URL and MASK_AUTH_LDAP_BASE_DN are required");
    }
    this.config = config;
    this.injectedLdapsSslUtil = ldapsSslUtilForTests;
  }

  public AuthPrincipal authenticate(String username, char[] password) {
    if (username == null || username.isBlank()) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
    }
    if (password == null || password.length == 0) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "invalid username or password");
    }
    URI uri = parseLdapUrl(config.ldapUrl());
    boolean tls = "ldaps".equals(uri.getScheme().toLowerCase(java.util.Locale.ROOT));
    int port = portOf(uri);
    LDAPConnectionOptions options = new LDAPConnectionOptions();
    options.setConnectTimeoutMillis((int) config.connectTimeout().toMillis());
    options.setResponseTimeoutMillis((int) config.responseTimeout().toMillis());

    try (LDAPConnection connection = tls
        ? ldapsConnection(uri, port, options)
        : new LDAPConnection(options, uri.getHost(), port)) {
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

  /** ldaps ⇒ 636, ldap ⇒ 389 when the URL carries no explicit port. */
  static int portOf(URI uri) {
    boolean tls = "ldaps".equals(uri.getScheme().toLowerCase(java.util.Locale.ROOT));
    return uri.getPort() == -1 ? (tls ? 636 : 389) : uri.getPort();
  }

  /**
   * One {@link SSLUtil} is the single trust source for the ldaps connection:
   * it provides the socket factory (chain validation), and the hostname
   * verifier is installed on the options BEFORE the connection is constructed
   * (a verifier set after the connect has already happened would never run).
   */
  private LDAPConnection ldapsConnection(URI uri, int port, LDAPConnectionOptions options)
      throws LDAPException {
    SSLUtil sslUtil = ldapsSslUtil();
    options.setSSLSocketVerifier(LDAPS_HOSTNAME_VERIFIER);
    try {
      return new LDAPConnection(sslUtil.createSSLSocketFactory(), options, uri.getHost(), port);
    } catch (java.security.GeneralSecurityException e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "cannot build ldaps socket factory (check MASK_AUTH_LDAP_TRUSTSTORE_PATH): "
              + e.getMessage());
    }
  }

  private SSLUtil ldapsSslUtil() {
    if (injectedLdapsSslUtil != null) {
      return injectedLdapsSslUtil;
    }
    try {
      if (config.ldapTruststorePath() == null || config.ldapTruststorePath().isBlank()) {
        return new SSLUtil(); // JVM default trust store
      }
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      char[] pass = config.ldapTruststorePassword() == null
          ? null : config.ldapTruststorePassword().toCharArray();
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(
          java.nio.file.Path.of(config.ldapTruststorePath()))) {
        trustStore.load(in, pass);
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      return new SSLUtil(tmf.getTrustManagers());
    } catch (Exception e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "cannot build ldaps SSL context (check MASK_AUTH_LDAP_TRUSTSTORE_PATH): "
              + e.getMessage());
    }
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
