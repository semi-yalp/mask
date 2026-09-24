package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.store.PolicyYamlWriter;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.metrics.AdminMetrics;
import io.sqlmask.policyserver.transfer.PolicyExportMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The policies.yaml exchange surface. Export downloads all policies of an
 * instance as a Ranger-style policies.yaml attachment (read-only, no audit
 * event — matching the other GET endpoints); import applies one such document
 * with whole-file pre-validation and per-name upsert, emitting a single
 * ADMIN_CHANGE event with the created/updated counts.
 */
@RestController
@RequestMapping("/api/instances")
public class PolicyExchangeController {

  public record ImportResultDto(int created, int updated) {
  }

  private final PolicyService service;
  private final PolicyYamlWriter writer = new PolicyYamlWriter();
  private final PolicyExportMapper exportMapper = new PolicyExportMapper();
  private final AuditAdminHelper audit;
  private final AdminMetrics adminMetrics;

  public PolicyExchangeController(PolicyService service, AuditAdminHelper audit,
      AdminMetrics adminMetrics) {
    this.service = service;
    this.audit = audit;
    this.adminMetrics = adminMetrics;
  }

  @GetMapping("/{name}/policies/export")
  public ResponseEntity<byte[]> exportPolicies(@PathVariable("name") String name) {
    List<Policy> policies = service.policies(name).stream().map(exportMapper::toPolicy).toList();
    byte[] body = writer.write(policies).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/yaml"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"policies-" + name + ".yaml\"")
        .body(body);
  }

  @PostMapping("/{name}/policies/import")
  public ImportResultDto importPolicies(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody String yaml) {
    AtomicReference<PolicyService.ImportResult> applied = new AtomicReference<>();
    PolicyService.ImportResult result = adminMetrics.record("POLICY", "IMPORT", () ->
        audit.adminChange(httpRequest, "IMPORT_POLICIES", "POLICY", name, null,
            () -> {
              PolicyService.ImportResult r = applied.get();
              return r == null ? Map.of()
                  : Map.of("policyCount", r.created() + r.updated(), "created", r.created(),
                      "updated", r.updated());
            },
            () -> {
              PolicyService.ImportResult r = service.importPolicies(name, yaml);
              applied.set(r);
              return r;
            }));
    return new ImportResultDto(result.created(), result.updated());
  }
}