package io.sqlmask.metaserver.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
}
