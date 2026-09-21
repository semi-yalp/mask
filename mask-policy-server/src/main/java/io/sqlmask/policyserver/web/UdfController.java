package io.sqlmask.policyserver.web;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Registry REST for an instance's masking UDFs (signature-stored). */
@RestController
@RequestMapping("/api/instances/{instance}/udfs")
public class UdfController {

  private final PolicyService service;

  public UdfController(PolicyService service) {
    this.service = service;
  }

  public record UdfSignatureDto(List<String> params, String returns) {
    public UdfSignatureDto {
      params = params == null ? List.of() : List.copyOf(params);
    }
  }

  public record UdfDto(String name, List<UdfSignatureDto> signatures) {
    public UdfDto {
      signatures = signatures == null ? List.of() : List.copyOf(signatures);
    }
  }

  @PostMapping
  public UdfDto create(@PathVariable String instance, @RequestBody UdfDto body) {
    return toDto(service.createUdf(instance, toEntity(body)));
  }

  @GetMapping
  public List<UdfDto> list(@PathVariable String instance) {
    return service.udfs(instance).stream().map(UdfController::toDto).toList();
  }

  @GetMapping("/{name}")
  public UdfDto get(@PathVariable String instance, @PathVariable String name) {
    return service.udf(instance, name)
        .map(UdfController::toDto)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "udf '" + name + "' not found"));
  }

  @PutMapping("/{name}")
  public UdfDto replace(@PathVariable String instance, @PathVariable String name,
      @RequestBody UdfDto body) {
    return toDto(service.replaceUdf(instance, name, toEntity(body)));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable String instance, @PathVariable String name) {
    service.deleteUdf(instance, name);
  }

  private static UdfDefinition toEntity(UdfDto dto) {
    return new UdfDefinition(dto.name(), dto.signatures().stream()
        .map(s -> new UdfDefinition.UdfSignature(s.params(), s.returns()))
        .toList());
  }

  private static UdfDto toDto(UdfDefinition udf) {
    return new UdfDto(udf.name(), udf.signatures().stream()
        .map(s -> new UdfSignatureDto(s.params(), s.returns()))
        .toList());
  }
}