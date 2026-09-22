package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialsTest {

  private final Credentials credentials = new Credentials();

  @Test
  void nonPrefixedReferenceIsRejected() {
    // 与 mask-metadata 的 EnvCredentialResolver 同一口径：passwordRef 必须
    // 指向 SQLMASK_* 变量，其余环境变量（含云凭证）不可被实例配置引用
    for (String bad : new String[] {"PGPASSWORD", "AWS_SECRET_ACCESS_KEY", "sqlmask_pw", null}) {
      QueryException e = assertThrows(QueryException.class, () -> credentials.resolve(bad));
      assertEquals(QueryException.CREDENTIAL_UNAVAILABLE, e.code());
    }
  }

  @Test
  void unsetPrefixedVariableFailsClosedNamingTheReference() {
    // 测试进程不会定义该名字；成功路径需要真实环境变量，归入手工验收
    QueryException e = assertThrows(QueryException.class,
        () -> credentials.resolve("SQLMASK_TEST_UNSET_CREDENTIAL_2026"));
    assertEquals(QueryException.CREDENTIAL_UNAVAILABLE, e.code());
    assertEquals(true, e.getMessage().contains("SQLMASK_TEST_UNSET_CREDENTIAL_2026"));
  }
}
