package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkGuardTest {

  @Test
  void linkLocalIpv4IsDenied() {
    // 169.254.169.254：云厂商元数据端点，字面量不触发 DNS
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> NetworkGuard.checkHost("169.254.169.254", NetworkGuard.Policy.LINK_LOCAL));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void linkLocalIpv6IsDenied() {
    assertThrows(SqlMaskException.class,
        () -> NetworkGuard.checkHost("fe80::1", NetworkGuard.Policy.LINK_LOCAL));
  }

  @Test
  void loopbackAndPublicAddressesAreAllowed() {
    // 本地开发采集走 127.0.0.1，必须保持可用
    assertDoesNotThrow(() -> NetworkGuard.checkHost("127.0.0.1", NetworkGuard.Policy.LINK_LOCAL));
    assertDoesNotThrow(() -> NetworkGuard.checkHost("8.8.8.8", NetworkGuard.Policy.LINK_LOCAL));
  }

  @Test
  void offPolicyDisablesTheCheck() {
    assertDoesNotThrow(
        () -> NetworkGuard.checkHost("169.254.169.254", NetworkGuard.Policy.OFF));
  }

  @Test
  void unresolvableHostFailsClosed() {
    // RFC 8115 保留名，公网 DNS 不可解析；解析失败按 CONFIG_ERROR 拒绝
    assertThrows(SqlMaskException.class,
        () -> NetworkGuard.checkHost("does-not-resolve.invalid",
            NetworkGuard.Policy.LINK_LOCAL));
  }

  @Test
  void policyParsingIsExplicit() {
    assertEquals(NetworkGuard.Policy.LINK_LOCAL, NetworkGuard.parsePolicy(null));
    assertEquals(NetworkGuard.Policy.LINK_LOCAL, NetworkGuard.parsePolicy(" "));
    assertEquals(NetworkGuard.Policy.LINK_LOCAL, NetworkGuard.parsePolicy("link-local"));
    assertEquals(NetworkGuard.Policy.OFF, NetworkGuard.parsePolicy("OFF"));
    assertThrows(SqlMaskException.class, () -> NetworkGuard.parsePolicy("allow-all"));
  }
}
