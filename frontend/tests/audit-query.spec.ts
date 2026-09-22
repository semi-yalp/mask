import { describe, it, expect } from "vitest";
import { buildAuditQuery } from "@/api/audit";

describe("buildAuditQuery", () => {
  const now = new Date("2026-09-22T12:00:00Z");

  it("默认时间窗:to=now,from=now-24h", () => {
    const q = buildAuditQuery({}, 0, 50, now);
    expect(q).toContain(`to=2026-09-22T12%3A00%3A00.000Z`);
    expect(q).toContain(`from=2026-09-21T12%3A00%3A00.000Z`);
    expect(q).toContain("page=0");
    expect(q).toContain("size=50");
  });

  it("透传非空过滤条件并编码", () => {
    const q = buildAuditQuery(
      { eventType: "ADMIN_CHANGE", outcome: "SUCCESS", instance: "crm", user: "alice" },
      2, 100, now
    );
    expect(q).toContain("eventType=ADMIN_CHANGE");
    expect(q).toContain("outcome=SUCCESS");
    expect(q).toContain("instance=crm");
    expect(q).toContain("user=alice");
    expect(q).toContain("page=2");
    expect(q).toContain("size=100");
  });

  it("显式 from/to 覆盖默认窗口", () => {
    const q = buildAuditQuery(
      { from: "2026-09-20T00:00:00Z", to: "2026-09-20T12:00:00Z" },
      0, 50, now
    );
    expect(q).toContain("from=2026-09-20T00%3A00%3A00Z");
    expect(q).toContain("to=2026-09-20T12%3A00%3A00Z");
  });

  it("时间范围超过 7 天时报错", () => {
    expect(() =>
      buildAuditQuery({ from: "2026-09-01T00:00:00Z", to: "2026-09-22T00:00:00Z" }, 0, 50, now)
    ).toThrow("7 天");
  });

  it("size 越界(0 / 201)与负 page 报错", () => {
    expect(() => buildAuditQuery({}, 0, 0, now)).toThrow("size");
    expect(() => buildAuditQuery({}, 0, 201, now)).toThrow("size");
    expect(() => buildAuditQuery({}, -1, 50, now)).toThrow("page");
  });
});
