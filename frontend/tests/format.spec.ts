import { describe, expect, it } from "vitest";
import { cellText, formatDuration, formatTime } from "@/utils/format";

describe("formatTime", () => {
  it("formats ISO strings as local zh-CN timestamps", () => {
    const out = formatTime("2026-09-24T10:20:30Z");
    expect(out).toMatch(/2026/);
    expect(out).toMatch(/09|10/); // 月份或按本地时区偏移的日期
    expect(out).not.toContain("T");
    expect(out).not.toContain("Z");
  });

  it("returns dash for empty and raw text for invalid input", () => {
    expect(formatTime(undefined)).toBe("—");
    expect(formatTime(null)).toBe("—");
    expect(formatTime("")).toBe("—");
    expect(formatTime("not-a-date")).toBe("not-a-date");
  });

  it("accepts Date objects", () => {
    expect(formatTime(new Date("2026-09-24T10:20:30Z"))).toMatch(/2026/);
  });
});

describe("formatDuration", () => {
  it("shows ms below one second and one-decimal seconds above", () => {
    expect(formatDuration(123)).toBe("123 ms");
    expect(formatDuration(1500)).toBe("1.5 s");
    expect(formatDuration(undefined)).toBe("—");
  });
});

describe("cellText", () => {
  it("renders null as dash, objects as JSON, primitives via String", () => {
    expect(cellText(null)).toBe("—");
    expect(cellText(undefined)).toBe("—");
    expect(cellText({ a: 1 })).toBe('{"a":1}');
    expect(cellText(42)).toBe("42");
    expect(cellText(false)).toBe("false");
  });
});
