package io.sqlmask.query.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class QueryApiKeyFilterTest {

  private final QueryApiKeyFilter open = new QueryApiKeyFilter(null);
  private final QueryApiKeyFilter guarded = new QueryApiKeyFilter("k1");

  @Test
  void unconfiguredKeyFailsClosed() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    open.doFilter(new MockHttpServletRequest("POST", "/api/v1/query"), res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void configuredKeyAcceptsOnlyMatch() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/query");
    req.addHeader("X-Api-Key", "k1");
    MockFilterChain chain = new MockFilterChain();
    guarded.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void configuredKeyRejectsWrongAndMissingKey() throws Exception {
    // 错 key 与缺 key 都必须 401（常量时间比较路径的正确性钉）
    for (String provided : new String[] {"wrong", null}) {
      MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/query");
      if (provided != null) {
        req.addHeader("X-Api-Key", provided);
      }
      MockHttpServletResponse res = new MockHttpServletResponse();
      guarded.doFilter(req, res, new MockFilterChain());
      assertThat(res.getStatus()).isEqualTo(401);
    }
  }
}
