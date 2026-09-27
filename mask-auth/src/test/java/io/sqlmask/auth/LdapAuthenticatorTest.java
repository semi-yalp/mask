package io.sqlmask.auth;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.SelfSignedCertificateGenerator;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdapAuthenticatorTest {

  private InMemoryDirectoryServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.shutDown(true);
    }
  }

  private void startServer(String... entries) throws Exception {
    InMemoryDirectoryServerConfig config =
        new InMemoryDirectoryServerConfig("dc=example,dc=org");
    config.setSchema(null); // accept plain memberOf / arbitrary objectClasses
    server = new InMemoryDirectoryServer(config);
    server.startListening();
    for (String entry : entries) {
      server.add(entry.split("\n"));
    }
  }

  /** Same as {@link #startServer}, but behind a TLS listener (ldaps). */
  private void startTlsServer(SSLUtil serverSsl, String... entries) throws Exception {
    InMemoryDirectoryServerConfig config =
        new InMemoryDirectoryServerConfig("dc=example,dc=org");
    config.setSchema(null);
    // the listener's client factory also trust-all: the server's own
    // internal connections (adding the seed entries) must complete the
    // handshake against the self-signed certificate
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig(
        "ldaps", null, 0, serverSsl.createSSLServerSocketFactory(),
        new SSLUtil(new TrustAllTrustManager()).createSSLSocketFactory()));
    server = new InMemoryDirectoryServer(config);
    server.startListening();
    for (String entry : entries) {
      server.add(entry.split("\n"));
    }
  }
  private AuthConfig config(Map<String, String> overrides) {
    Map<String, String> env = new java.util.HashMap<>();
    env.put("MASK_AUTH_SECRET", "0123456789abcdef0123456789abcdef");
    env.put("MASK_AUTH_LDAP_URL", "ldap://127.0.0.1:" + server.getListenPort());
    env.put("MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org");
    env.put("MASK_AUTH_ADMIN_GROUPS", "mask-admins");
    env.put("MASK_AUTH_AUDITOR_GROUPS", "mask-auditors");
    env.putAll(overrides);
    return AuthConfig.fromEnv(env);
  }

  @Test
  void memberOfModeMapsRoles() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy Admin\nsn: Admin\nuserPassword: amy-secret\n"
            + "memberOf: cn=mask-admins,ou=groups,dc=example,dc=org",
        "dn: uid=bob,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: bob\n"
            + "cn: Bob Auditor\nsn: Auditor\nuserPassword: bob-secret\n"
            + "memberOf: cn=mask-auditors,ou=groups,dc=example,dc=org",
        "dn: uid=carol,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: carol\n"
            + "cn: Carol User\nsn: User\nuserPassword: carol-secret");

    LdapAuthenticator authenticator = new LdapAuthenticator(config(Map.of()));

    AuthPrincipal amy = authenticator.authenticate("amy", "amy-secret".toCharArray());
    assertEquals(Role.ADMIN, amy.role());
    assertEquals("Amy Admin", amy.displayName());
    assertEquals(List.of("mask-admins"), amy.groups());

    assertEquals(Role.AUDITOR, authenticator.authenticate("bob", "bob-secret".toCharArray()).role());
    assertEquals(Role.USER, authenticator.authenticate("carol", "carol-secret".toCharArray()).role());
  }

  @Test
  void groupSearchModeResolvesMembership() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: ou=groups,dc=example,dc=org\nobjectClass: organizationalUnit\nou: groups",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy\nsn: Admin\nuserPassword: amy-secret",
        "dn: uid=carol,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: carol\n"
            + "cn: Carol\nsn: User\nuserPassword: carol-secret",
        "dn: cn=mask-admins,ou=groups,dc=example,dc=org\nobjectClass: groupOfNames\n"
            + "cn: mask-admins\nmember: uid=amy,ou=people,dc=example,dc=org");

    LdapAuthenticator authenticator = new LdapAuthenticator(
        config(Map.of("MASK_AUTH_LDAP_GROUP_SEARCH_BASE", "ou=groups,dc=example,dc=org")));

    AuthPrincipal amy = authenticator.authenticate("amy", "amy-secret".toCharArray());
    assertEquals(Role.ADMIN, amy.role());
    assertEquals(List.of("mask-admins"), amy.groups());

    AuthPrincipal carol = authenticator.authenticate("carol", "carol-secret".toCharArray());
    assertEquals(Role.USER, carol.role());
    assertEquals(List.of(), carol.groups());
  }

  @Test
  void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=carol,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: carol\n"
            + "cn: Carol\nsn: User\nuserPassword: carol-secret");

    LdapAuthenticator authenticator = new LdapAuthenticator(config(Map.of()));

    AuthException wrong = assertThrows(AuthException.class,
        () -> authenticator.authenticate("carol", "nope".toCharArray()));
    AuthException unknown = assertThrows(AuthException.class,
        () -> authenticator.authenticate("ghost", "whatever".toCharArray()));
    assertEquals(AuthException.Code.INVALID_CREDENTIALS, wrong.code());
    assertEquals(wrong.code(), unknown.code());
    assertEquals(wrong.getMessage(), unknown.getMessage());
  }

  @Test
  void emptyPasswordIsRejectedWithoutTouchingLdap() throws Exception {
    startServer("dn: dc=example,dc=org\nobjectClass: domain\ndc: example");
    LdapAuthenticator authenticator = new LdapAuthenticator(config(Map.of()));
    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("carol", new char[0]));
    assertEquals(AuthException.Code.INVALID_CREDENTIALS, e.code());
  }

  @Test
  void managerBindIsUsedForTheSearch() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=svc,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: svc\n"
            + "cn: Service Account\nsn: Account\nuserPassword: svc-secret",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy\nsn: Admin\nuserPassword: amy-secret\n"
            + "memberOf: cn=mask-admins,ou=groups,dc=example,dc=org");

    LdapAuthenticator authenticator = new LdapAuthenticator(config(Map.of(
        "MASK_AUTH_LDAP_BIND_DN", "uid=svc,ou=people,dc=example,dc=org",
        "MASK_AUTH_LDAP_BIND_PASSWORD", "svc-secret")));

    assertEquals(Role.ADMIN, authenticator.authenticate("amy", "amy-secret".toCharArray()).role());
  }

  @Test
  void unreachableLdapFailsClosed() {
    AuthConfig config = AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_SECRET", "0123456789abcdef0123456789abcdef",
        "MASK_AUTH_LDAP_URL", "ldap://127.0.0.1:1",
        "MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org",
        "MASK_AUTH_LDAP_CONNECT_TIMEOUT", "PT1S"));
    LdapAuthenticator authenticator = new LdapAuthenticator(config);
    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("amy", "x".toCharArray()));
    assertEquals(AuthException.Code.LDAP_UNAVAILABLE, e.code());
  }

  @Test
  void loginNamesAreFilterEscaped() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=carol,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: carol\n"
            + "cn: Carol\nsn: User\nuserPassword: carol-secret");
    LdapAuthenticator authenticator = new LdapAuthenticator(config(Map.of()));

    // a crafted login name must not expand the filter into a wildcard match
    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("*)(objectClass=*", "x".toCharArray()));
    assertEquals(AuthException.Code.INVALID_CREDENTIALS, e.code());
  }

  @Test
  void ldapsUrlDialsTlsNotPlaintext() throws Exception {
    startServer(
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy\nsn: Admin\nuserPassword: amy-secret");

    // 明文监听器 + ldaps URL:TLS ClientHello 必然失败 ⇒ LDAP_UNAVAILABLE。
    // (bug 在场时走明文连接、amy 认证成功,断言失败)
    LdapAuthenticator authenticator = new LdapAuthenticator(
        config(java.util.Map.of("MASK_AUTH_LDAP_URL",
            "ldaps://127.0.0.1:" + server.getListenPort())),
        new SSLUtil(new TrustAllTrustManager()));

    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("amy", "amy-secret".toCharArray()));
    assertEquals(AuthException.Code.LDAP_UNAVAILABLE, e.code());
  }

  @Test
  void ldapsRejectsCertificateForOtherHostname() throws Exception {
    // MITM 形态:证书链"可信"(客户端注入 trust-all)但签给别的域名——
    // 连接的主机名不在证书 SAN 里,必须拒连;只校验链不校验主机名时
    // amy 会认证成功(把绑定凭据交给了 MITM)。
    // 注意用 "localhost" 而非 127.0.0.1:HostNameSSLSocketVerifier 按
    // W3C Secure Contexts 语义豁免回环 IP,数字回环地址测不出主机名校验;
    // "localhost" 走 dNSName 匹配,而自签证书的 SAN 是本机名/本机 IP。
    ObjectPair<File, char[]> cert = SelfSignedCertificateGenerator
        .generateTemporarySelfSignedCertificate("CN=ldap.example.org", "JKS");
    SSLUtil serverSsl = new SSLUtil(
        new KeyStoreKeyManager(cert.getFirst(), cert.getSecond()),
        new TrustAllTrustManager());
    startTlsServer(serverSsl,
        "dn: dc=example,dc=org\nobjectClass: domain\ndc: example",
        "dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people",
        "dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
            + "cn: Amy\nsn: Admin\nuserPassword: amy-secret");

    LdapAuthenticator authenticator = new LdapAuthenticator(
        config(java.util.Map.of("MASK_AUTH_LDAP_URL",
            "ldaps://localhost:" + server.getListenPort("ldaps"))),
        new SSLUtil(new TrustAllTrustManager()));

    AuthException e = assertThrows(AuthException.class,
        () -> authenticator.authenticate("amy", "amy-secret".toCharArray()));
    assertEquals(AuthException.Code.LDAP_UNAVAILABLE, e.code());
  }

  @Test
  void portOfFallsBackToWellKnownPorts() {
    assertEquals(636, LdapAuthenticator.portOf(URI.create("ldaps://host")));
    assertEquals(389, LdapAuthenticator.portOf(URI.create("ldap://host")));
    assertEquals(1389, LdapAuthenticator.portOf(URI.create("ldap://host:1389")));
  }

  @Test
  void roleMappingHandlesCaseAndMultipleGroups() {
    AuthConfig config = AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_SECRET", "0123456789abcdef0123456789abcdef",
        "MASK_AUTH_ADMIN_GROUPS", "Mask-Admins, platform-admins",
        "MASK_AUTH_AUDITOR_GROUPS", "mask-auditors"));
    assertEquals(Role.ADMIN, config.mapRole(List.of("devs", "mask-admins")));
    assertEquals(Role.ADMIN, config.mapRole(List.of("platform-admins")));
    assertEquals(Role.AUDITOR, config.mapRole(List.of("mask-auditors", "devs")));
    assertEquals(Role.USER, config.mapRole(List.of("devs")));
    assertEquals(Role.USER, config.mapRole(List.of()));
    assertTrue(config.tokenEnabled());
  }
}
