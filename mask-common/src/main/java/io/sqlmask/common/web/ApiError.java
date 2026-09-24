package io.sqlmask.common.web;

import java.util.List;

/**
 * The one error body shape every service returns:
 * {@code {"code": "...", "message": "...", "details": []}}. Replaces the
 * three divergent shapes ({@code (code,message)} records in mask-core and
 * mask-policy-server, a bare Map in mask-query) that used to coexist with
 * the hand-written bodies inside the API-key filters.
 */
public record ApiError(String code, String message, List<String> details) {

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, List.of());
  }

  public static ApiError internal(String message) {
    return new ApiError("INTERNAL_ERROR", message == null ? "internal error" : message, List.of());
  }
}
