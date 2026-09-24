package io.sqlmask.server.grant;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.service.MetadataService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Unified-grant REST surface: entries are CRUD-managed per instance, compiled
 * to engine DDL for preview, applied explicitly, and queryable as a
 * principal×privilege matrix. Write paths are ADMIN territory (the central
 * bearer gate's readOnly rules cover /api/instances; grants ride the same
 * prefix family via /api/grants and instance-scoped paths).
 */
@RestController
public class GrantController {

  private final GrantService grants;

  public GrantController(GrantService grants) {
    this.grants = grants;
  }

  public record GrantRequest(String principalType, String principal, String resourceType,
      String resourceId, String privilege) {
  }

  @PostMapping("/api/instances/{instance}/grants")
  public GrantEntry create(@PathVariable("instance") String instance,
      @RequestBody GrantRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "body is required");
    }
    io.sqlmask.auth.AuthPrincipal actor = io.sqlmask.auth.AuthTokens.principal(httpRequest);
    return grants.create(instance,
        parse(GrantEntry.PrincipalType.class, request.principalType()),
        request.principal(),
        parse(GrantEntry.ResourceType.class, request.resourceType()),
        request.resourceId(),
        parse(GrantEntry.Privilege.class, request.privilege()),
        actor != null ? actor.username() : null);
  }

  @GetMapping("/api/instances/{instance}/grants")
  public List<GrantEntry> byPrincipal(@PathVariable("instance") String instance,
      @RequestParam("principalType") String principalType,
      @RequestParam("principal") String principal) {
    return grants.byPrincipal(instance,
        parse(GrantEntry.PrincipalType.class, principalType).name(), principal);
  }

  @GetMapping("/api/instances/{instance}/grants/all")
  public List<GrantEntry> byInstance(@PathVariable("instance") String instance) {
    return grants.byInstance(instance);
  }

  @DeleteMapping("/api/instances/{instance}/grants/{id}")
  public Map<String, Object> delete(@PathVariable("instance") String instance,
      @PathVariable("id") long id) {
    boolean removed = grants.delete(instance, id);
    return Map.of("id", id, "deleted", removed);
  }

  @GetMapping("/api/instances/{instance}/grants/preview")
  public Map<String, Object> preview(@PathVariable("instance") String instance,
      @RequestParam("principalType") String principalType,
      @RequestParam("principal") String principal) {
    List<GrantCompiler.CompiledStatement> statements =
        grants.preview(instance, parse(GrantEntry.PrincipalType.class, principalType).name(), principal);
    return Map.of("instance", instance, "statements", statements);
  }

  @PostMapping("/api/instances/{instance}/grants/apply")
  public Map<String, Object> apply(@PathVariable("instance") String instance,
      @RequestParam("principalType") String principalType,
      @RequestParam("principal") String principal) {
    return grants.apply(instance, parse(GrantEntry.PrincipalType.class, principalType).name(), principal);
  }

  @GetMapping("/api/grants/matrix")
  public List<Map<String, Object>> matrix(@RequestParam("instance") String instance) {
    return grants.matrix(instance);
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          type.getSimpleName() + " is required");
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown " + type.getSimpleName() + " '" + value + "'");
    }
  }
}
