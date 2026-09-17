package io.sqlmask.query;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.config.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// base-url 启动期校验（非空白）要求哑值，否则默认上下文因空配置拒绝启动
@SpringBootTest(properties = {
    "upstream.metadata-base-url=http://localhost:1",
    "upstream.rewrite-base-url=http://localhost:1"})
class QueryServerApplicationTest {

  @Autowired QueryProperties props;

  @Test
  void contextLoadsWithDefaults() {
    assertThat(props.timeoutSeconds()).isEqualTo(30);
    assertThat(props.maxRows()).isEqualTo(1000);
    assertThat(props.maxRowsHard()).isEqualTo(10000);
    assertThat(props.fetchSize()).isEqualTo(500);
    assertThat(props.maxConcurrentPerInstance()).isEqualTo(10);
  }

  @Test
  void blankBaseUrlsFailStartupValidation() {
    var app = new QueryServerApplication();
    var blanks = new UpstreamProperties(" ", "k", null, "k");
    assertThatThrownBy(() -> app.metadataServiceClient(blanks))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upstream.metadata-base-url");
    assertThatThrownBy(() -> app.rewriteServiceClient(blanks))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upstream.rewrite-base-url");
  }
}
