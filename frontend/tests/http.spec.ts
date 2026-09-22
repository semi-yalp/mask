import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { call } from "@/api/http";
import { useSettingsStore } from "@/stores/settings";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
  useSettingsStore().setKeys("", "");
});

describe("call()", () => {
  it("返回 2xx JSON 载荷", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { hello: 1 })));
    await expect(call("GET", "/api/x")).resolves.toEqual({ hello: 1 });
  });

  it("非 2xx 且带 code/message 时抛 [CODE] message", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(400, { code: "CONFIG_ERROR", message: "bad input" })));
    await expect(call("GET", "/api/x")).rejects.toThrow("[CONFIG_ERROR] bad input");
  });

  it("非 JSON 错误体时抛 HTTP <status>", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("oops", { status: 502 })));
    await expect(call("GET", "/api/x")).rejects.toThrow("HTTP 502");
  });

  it("admin 角色注入管理 Key,data 角色注入数据 Key", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, {}));
    vi.stubGlobal("fetch", fetchMock);
    useSettingsStore().setKeys("adm-key", "dat-key");
    await call("GET", "/api/a", undefined, "admin");
    await call("GET", "/api/b", undefined, "data");
    expect(fetchMock.mock.calls[0][1].headers["X-Api-Key"]).toBe("adm-key");
    expect(fetchMock.mock.calls[1][1].headers["X-Api-Key"]).toBe("dat-key");
  });

  it("Key 为空时不携带 X-Api-Key", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, {}));
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/c");
    expect(fetchMock.mock.calls[0][1].headers["X-Api-Key"]).toBeUndefined();
  });

  it("带 body 时设置 Content-Type 并序列化", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, {}));
    vi.stubGlobal("fetch", fetchMock);
    await call("POST", "/api/d", { a: 1 });
    const init = fetchMock.mock.calls[0][1];
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe(JSON.stringify({ a: 1 }));
  });
});
