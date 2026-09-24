import { describe, it, expect } from "vitest";
import { buildRiskEventQuery } from "@/api/risk";
import { severityColor, severityLabel, statusLabel, truncateSql, scoreColor } from "@/views/risk/risk-ui";

describe("buildRiskEventQuery", () => {
  it("默认窗口 1440 分钟并透传非空过滤", () => {
    const q = buildRiskEventQuery({ severity: "critical", user: "guest_202" }, 0, 50);
    expect(q).toContain("severity=critical");
    expect(q).toContain("user=guest_202");
    expect(q).toContain("minutes=1440");
    expect(q).toContain("page=0");
    expect(q).toContain("size=50");
  });

  it("空白过滤不进入查询串", () => {
    const q = buildRiskEventQuery({ keyword: "  ", user: "" }, 1, 20);
    expect(q).not.toContain("keyword=");
    expect(q).not.toContain("user=");
    expect(q).toContain("page=1");
  });

  it("自定义窗口覆盖默认值", () => {
    const q = buildRiskEventQuery({ minutes: 60 }, 0, 200);
    expect(q).toContain("minutes=60");
  });

  it("越界分页参数报错", () => {
    expect(() => buildRiskEventQuery({}, -1, 50)).toThrow("page");
    expect(() => buildRiskEventQuery({}, 0, 0)).toThrow("size");
    expect(() => buildRiskEventQuery({}, 0, 201)).toThrow("size");
  });
});

describe("risk-ui 展示工具", () => {
  it("风险级别配色与文案", () => {
    expect(severityColor("critical")).toBe("#dc3545");
    expect(severityColor("high")).toBe("#e8590c");
    expect(severityLabel("medium")).toBe("中危");
    expect(severityLabel("info")).toBe("信息");
  });

  it("告警状态文案", () => {
    expect(statusLabel("OPEN")).toBe("未处理");
    expect(statusLabel("ACKNOWLEDGED")).toBe("已确认");
    expect(statusLabel("RESOLVED")).toBe("已解决");
  });

  it("风险分配色随分数分档", () => {
    expect(scoreColor(100)).toBe("#dc3545");
    expect(scoreColor(45)).toBe("#e8590c");
    expect(scoreColor(25)).toBe("#f0b849");
    expect(scoreColor(5)).toBe("#35a7d3");
    expect(scoreColor(0)).toBe("#868e96");
  });

  it("SQL 截断压平空白", () => {
    expect(truncateSql("SELECT  a,\n   b  FROM t", 100)).toBe("SELECT a, b FROM t");
    const long = "SELECT " + "x".repeat(200);
    expect(truncateSql(long, 50).length).toBe(51);
    expect(truncateSql(long, 50).endsWith("…")).toBe(true);
    expect(truncateSql(undefined)).toBe("");
  });
});
