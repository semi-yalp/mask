package io.sqlmask.metaserver.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

class MetadataApiExceptionHandlerTest {

  private final MetadataApiExceptionHandler handler = new MetadataApiExceptionHandler();

  @Test
  void mapsInstanceNotFoundTo404() {
    assertEquals(HttpStatus.NOT_FOUND, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, "nope")).getStatusCode());
  }

  @Test
  void mapsInstanceExistsTo409() {
    assertEquals(HttpStatus.CONFLICT, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_INSTANCE_EXISTS, "dup")).getStatusCode());
  }

  @Test
  void mapsCredentialUnavailableTo400() {
    assertEquals(HttpStatus.BAD_REQUEST, handler.handle(new SqlMaskException(
        SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE, "env")).getStatusCode());
  }

  @Test
  void mapsIntrospectErrorTo502() {
    assertEquals(HttpStatus.BAD_GATEWAY, handler.handle(new SqlMaskException(
        SqlMaskException.Code.INTROSPECT_ERROR, "db down")).getStatusCode());
  }

  @Test
  void mapsConfigErrorTo400() {
    assertEquals(HttpStatus.BAD_REQUEST, handler.handle(new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "bad input")).getStatusCode());
  }

  @Test
  void errorBodyCarriesCodeMessageDetails() {
    MetadataApiExceptionHandler.ApiError body = handler.handle(new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "bad input")).getBody();
    assertEquals("CONFIG_ERROR", body.code());
    assertEquals("bad input", body.message());
    assertEquals(java.util.List.of(), body.details());
  }

  @Test
  void unreadableBodyMapsTo400WithBadRequestCode() {
    ResponseEntity<MetadataApiExceptionHandler.ApiError> response = handler.handleUnreadable(
        new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null));
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("BAD_REQUEST", response.getBody().code());
    assertTrue(response.getBody().message().startsWith("request body is not valid JSON"));
  }

  @Test
  void unexpectedExceptionMapsTo500WithInternalErrorCode() {
    ResponseEntity<MetadataApiExceptionHandler.ApiError> response =
        handler.handleUnexpected(new IllegalStateException("boom"));
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("INTERNAL_ERROR", response.getBody().code());
    assertEquals("boom", response.getBody().message());
  }

  @Test
  void unexpectedExceptionWithoutMessageFallsBackToExceptionName() {
    assertEquals("NullPointerException",
        handler.handleUnexpected(new NullPointerException()).getBody().message());
  }
}
