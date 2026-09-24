package io.sqlmask.metaserver.classification;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Classification admin plane: per-column category/level assertions, the
 * heuristic auto pass and the global overview. Errors surface through
 * {@code MetadataApiExceptionHandler} (SqlMaskException 400/404 mapping).
 */
@RestController
@RequestMapping("/api/classification")
public class ClassificationController {

  private final ClassificationService classifications;

  public ClassificationController(ClassificationService classifications) {
    this.classifications = classifications;
  }

  @GetMapping("/overview")
  public ClassificationService.Overview overview() {
    return classifications.overview();
  }

  @GetMapping("/instances/{instance}")
  public List<Classification> list(@PathVariable("instance") String instance) {
    return classifications.list(instance);
  }

  @PutMapping("/instances/{instance}")
  public Classification upsert(@PathVariable("instance") String instance,
      @RequestBody UpsertRequest request) {
    if (request == null || request.columnKey() == null || request.category() == null
        || request.level() == null) {
      throw new io.sqlmask.error.SqlMaskException(
          io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
          "columnKey, category and level are required");
    }
    return classifications.upsert(instance, request.columnKey(), request.category(),
        request.level(), request.note());
  }

  @DeleteMapping("/instances/{instance}")
  public Map<String, Object> delete(@PathVariable("instance") String instance,
      @RequestParam("column") String column) {
    if (column == null || column.isBlank()) {
      throw new io.sqlmask.error.SqlMaskException(
          io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR, "column is required");
    }
    return Map.of("instance", instance, "columnKey", column.toLowerCase(java.util.Locale.ROOT),
        "deleted", classifications.delete(instance, column));
  }

  @PostMapping("/instances/{instance}/auto")
  public ClassificationService.AutoResult auto(@PathVariable("instance") String instance) {
    return classifications.autoClassify(instance);
  }

  /** Manual assertion body: one column's category/level plus an optional note. */
  public record UpsertRequest(String columnKey, String category, String level, String note) {
  }
}
