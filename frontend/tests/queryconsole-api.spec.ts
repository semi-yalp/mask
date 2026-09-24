import { describe, expect, it } from "vitest";
import { buildQueryBody, MAX_ROWS_HARD_LIMIT } from "@/api/queryconsole";

describe("buildQueryBody", () => {
  it("trims instance and drops empty subject fields", () => {
    const body = buildQueryBody({ instance: " pg_prod ", sql: "SELECT 1", user: "  ", groups: [" ", "devs", "ops"] });
    expect(body.instance).toBe("pg_prod");
    expect(body.user).toBeUndefined();
    expect(body.groups).toEqual(["devs", "ops"]);
  });

  it("keeps user after trim and floors maxRows to integer >= 1", () => {
    const body = buildQueryBody({ instance: "a", sql: "SELECT 1", user: " alice ", maxRows: 100.9 });
    expect(body.user).toBe("alice");
    expect(body.maxRows).toBe(100);
  });

  it("passes through includeRewrittenSql only when true", () => {
    expect(buildQueryBody({ instance: "a", sql: "s", includeRewrittenSql: false }).includeRewrittenSql).toBeUndefined();
    expect(buildQueryBody({ instance: "a", sql: "s", includeRewrittenSql: true }).includeRewrittenSql).toBe(true);
  });

  it("hard limit constant matches the service contract", () => {
    expect(MAX_ROWS_HARD_LIMIT).toBe(10000);
  });
});
