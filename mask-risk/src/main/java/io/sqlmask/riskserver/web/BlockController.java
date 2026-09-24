package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.block.BlockService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-click blocking: turns an alert's subject into ROW_FILTER "1 = 0"
 * policies in the policy service - the response/处置 leg of the risk loop.
 */
@RestController
@RequestMapping("/api/risk/block")
public class BlockController {

  private final BlockService blocks;

  public BlockController(BlockService blocks) {
    this.blocks = blocks;
  }

  @GetMapping
  public Map<String, Object> list() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (BlockService.BlockState state : blocks.snapshot()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("user", state.user());
      row.put("instance", state.instance());
      row.put("policyNames", state.policyNames());
      row.put("policyCount", state.policyNames().size());
      row.put("blockedAt", state.blockedAt());
      row.put("note", state.note());
      rows.add(row);
    }
    return Map.of("configured", blocks.configured(), "blocks", rows);
  }

  /** Blocks the user on the instance (default from config when omitted). */
  @PostMapping
  public Map<String, Object> block(@RequestBody BlockRequest request) {
    BlockService.BlockResult result = blocks.block(
        request.user(), request.instance(), request.note());
    return wire(result, true);
  }

  /** Removes this service's risk-block-* policies for the user. */
  @DeleteMapping("/{user}")
  public Map<String, Object> unblock(@PathVariable("user") String user,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String instance) {
    BlockService.BlockResult result = blocks.unblock(user, instance);
    return wire(result, false);
  }

  private static Map<String, Object> wire(BlockService.BlockResult result, boolean block) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("user", result.user());
    out.put("instance", result.instance());
    out.put("policies", result.policies());
    out.put("policyCount", result.policies().size());
    out.put("alreadyBlocked", result.alreadyBlocked());
    out.put("verification", result.verification());
    out.put("action", block ? "BLOCK" : "UNBLOCK");
    return out;
  }

  public record BlockRequest(String user, String instance, String note) {
  }
}
