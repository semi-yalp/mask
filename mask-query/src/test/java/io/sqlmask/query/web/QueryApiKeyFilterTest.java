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
  void blankHeaderIsRejectedWhenConfigured() throws Exception {
    // header present but empty must behave like a missing header
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/query");
    req.addHeader("X-Api-Key", "");
    MockHttpServletResponse res = new MockHttpServletResponse();
    guarded.doFilter(req, res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void nonAsciiKeysCompareByUtf8Bytes() throws Exception {
    QueryApiKeyFilter unicode = new QueryApiKeyFilter("键-κλé🔑");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/query");
    req.addHeader("X-Api-Key", "键-κλé🔑");
    MockFilterChain chain = new MockFilterChain();
    unicode.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();

    MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/api/v1/query");
    wrong.addHeader("X-Api-Key", "键-κλé");
    MockHttpServletResponse res = new MockHttpServletResponse();
    unicode.doFilter(wrong, res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(401);
  }
}
