package io.sqlmask.server;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.UdfDefinition;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * UDF registry CRUD of one engine instance — the policy service's first admin
 * surface. Errors flow through {@link ApiExceptionHandler} (SqlMaskException
 * → 400 + code/message).
 */
@RestController
@RequestMapping("/api/instances/{instance}/udfs")
public class UdfController {

  public record UdfSignatureDto(List<String> params, String returns) {
  }

  public record UdfDto(String name, List<UdfSignatureDto> signatures) {
  }

  private final PolicyService service;
  private final AuditAdminHelper audit;

  public UdfController(PolicyService service, AuditAdminHelper audit) {
    this.service = service;
    this.audit = audit;
  }

  @PostMapping
  public UdfDto create(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @RequestBody UdfDto request) {
    return audit.adminChange(httpRequest, "REGISTER", "UDF", instance,
        request == null ? null : request.name(),
        () -> udfDetail(request),
        () -> toDto(service.createUdf(instance, toModel(request))));
  }

  @GetMapping
  public List<UdfDto> list(@PathVariable("instance") String instance) {
    return service.udfs(instance).stream().map(UdfController::toDto).toList();
  }

  @GetMapping("/{name}")
  public UdfDto get(@PathVariable("instance") String instance, @PathVariable("name") String name) {
    return service.udf(instance, name).map(UdfController::toDto)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "udf '" + name + "' not found in instance '" + instance + "'"));
  }

  @PutMapping("/{name}")
  public UdfDto replace(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @PathVariable("name") String name, @RequestBody UdfDto request) {
    return audit.adminChange(httpRequest, "UPDATE", "UDF", instance, name,
        () -> udfDetail(request),
        () -> toDto(service.replaceUdf(instance, name, toModel(request))));
  }

  @DeleteMapping("/{name}")
  public void delete(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @PathVariable("name") String name) {
    audit.adminChange(httpRequest, "DELETE", "UDF", instance, name, Map::of, () -> {
      service.deleteUdf(instance, name);
      return null;
    });
  }

  private static Map<String, Object> udfDetail(UdfDto dto) {
    int signatures = dto == null || dto.signatures() == null ? 0 : dto.signatures().size();
    return Map.of("signatureCount", signatures);
  }

  private static UdfDefinition toModel(UdfDto dto) {
    requireName(dto);
    return new UdfDefinition(dto.name(), dto.signatures() == null ? List.of()
        : dto.signatures().stream()
            .map(s -> new UdfDefinition.UdfSignature(
                requireParams(dto, requireSignature(dto, s)), requireReturnType(dto, s)))
            .toList());
  }

  /**
   * Input guard: a missing name would surface as an unwrapped NPE when the
   * service compares it against the path name (HTTP 500); reject it here as
   * a 400. Mirrors the validator's null-safe naming rule.
   */
  private static void requireName(UdfDto dto) {
    if (dto.name() == null || dto.name().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf definition requires a name");
    }
  }

  /**
   * Input guard: a null element in the signatures list would surface as an
   * unwrapped NPE while reading its fields (HTTP 500); reject it here as a 400.
   */
  private static UdfSignatureDto requireSignature(UdfDto dto, UdfSignatureDto signature) {
    if (signature == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + dto.name() + "': signatures must not contain null");
    }
    return signature;
  }

  /**
   * Input guard: a null element in params would be rejected by
   * UdfDefinition.UdfSignature's defensive List.copyOf as an unwrapped NPE
   * (HTTP 500); reject it here as a 400.
   */
  private static List<String> requireParams(UdfDto dto, UdfSignatureDto signature) {
    if (signature.params() == null) {
      return List.of();
    }
    if (signature.params().stream().anyMatch(p -> p == null)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + dto.name() + "': signature parameters must not contain null");
    }
    return signature.params();
  }

  /**
   * Input guard: a missing return type would surface as an unwrapped NPE deep
   * in the validator's type parsing (HTTP 500); reject it here as a 400.
   */
  private static String requireReturnType(UdfDto dto, UdfSignatureDto signature) {
    if (signature.returns() == null || signature.returns().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + dto.name() + "': signature requires a return type");
    }
    return signature.returns();
  }

  private static UdfDto toDto(UdfDefinition udf) {
    return new UdfDto(udf.name(), udf.signatures().stream()
        .map(s -> new UdfSignatureDto(s.params(), s.returns()))
        .toList());
  }
}
