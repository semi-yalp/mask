package io.sqlmask.query.submit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.QueryModels.ColumnView;
import io.sqlmask.query.service.QueryModels.QueryResult;
import io.sqlmask.query.service.ValueJson;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic HTTP submitter for engines fronted by an HTTP SQL API: POSTs
 * {@code {"query": sql}} to the configured URL and reads the conventional
 * payload {@code {"columns":[{"name":..,"type":..}],"rows":[[..,..]],"truncated":bool}}
 * back (extra fields are ignored). Configured per deployment via
 * {@code query.http-submitter.url}; instances then opt in with
 * {@code submitter: http}.
 */
@Component
public class HttpQuerySubmitter implements QuerySubmitter {

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final HttpClient http;
  private final QueryProperties props;

  public HttpQuerySubmitter(QueryProperties props) {
    this.props = props;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @Override
  public String type() {
    return "http";
  }

  /** The URL this submitter posts to; null means it is not configured and
   * any instance selecting it fails with a structured error. */
  private URI endpoint() {
    String url = props.httpSubmitterUrl();
    if (url == null || url.isBlank()) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "submitter 'http' requires query.http-submitter.url to be configured");
    }
    return URI.create(url);
  }

  @Override
  public QueryResult submit(SubmitRequest request) {
    URI endpoint = endpoint();
    String payload;
    try {
      payload = JSON.writeValueAsString(new QueryBody(request.sql()));
    } catch (IOException e) {
      throw new QueryException(QueryException.CONFIG_ERROR, "cannot serialize the query", e);
    }
    HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(props.timeoutSeconds()))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new QueryException(QueryException.QUERY_ERROR,
          "http submitter unreachable at '" + endpoint + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryException(QueryException.QUERY_ERROR,
          "interrupted while calling the http submitter", e);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new QueryException(QueryException.QUERY_ERROR,
          "http submitter returned HTTP " + response.statusCode());
    }
    return parse(request, response.body());
  }

  private QueryResult parse(SubmitRequest request, String body) {
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (IOException e) {
      throw new QueryException(QueryException.QUERY_ERROR,
          "http submitter returned an unreadable payload", e);
    }
    List<ColumnView> columns = new ArrayList<>();
    for (JsonNode column : node.path("columns")) {
      columns.add(new ColumnView(column.path("name").asText(),
          column.path("type").asText("")));
    }
    List<List<Object>> rows = new ArrayList<>();
    for (JsonNode row : node.path("rows")) {
      List<Object> values = new ArrayList<>(row.size());
      row.forEach(value -> values.add(ValueJson.toSerializable(value.isTextual()
          ? value.textValue()
          : JSON.convertValue(value, Object.class))));
      rows.add(values);
    }
    boolean truncated = node.path("truncated").asBoolean(false);
    long elapsedMs = (System.nanoTime() - request.startNanos()) / 1_000_000;
    return new QueryResult(request.instance(), request.engine(), columns, rows,
        rows.size(), truncated, request.masked(), request.rowFiltered(), elapsedMs,
        request.includeRewrittenSql() ? request.sql() : null, request.rewrittenBypassed());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record QueryBody(String query) {
  }
}
