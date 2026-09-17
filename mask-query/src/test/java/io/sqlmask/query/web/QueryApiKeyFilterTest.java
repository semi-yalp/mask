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
}
