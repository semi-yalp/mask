import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { call, callRaw } from "@/api/http";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
});

type CapturedInit = { headers?: Record<string, string>; body?: string };
const okMock = () =>
  vi.fn(async (_url: string | URL | Request, _init?: CapturedInit) => jsonResponse(200, {}));

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

  it("未登录时不携带任何认证头(none 模式全开放)", async () => {
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/c");
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Authorization"]).toBeUndefined();
    expect(init?.headers?.["X-Api-Key"]).toBeUndefined();
  });

  it("带 body 时设置 Content-Type 并序列化", async () => {
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("POST", "/api/d", { a: 1 });
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Content-Type"]).toBe("application/json");
    expect(init?.body).toBe(JSON.stringify({ a: 1 }));
  });

  it("callRaw 以 text/yaml 发送并返回文本", async () => {
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    const text = await callRaw("POST", "/api/y", { body: "policies: {}" });
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Content-Type"]).toBe("text/yaml");
    expect(init?.body).toBe("policies: {}");
    expect(text).toBe("{}");
  });
});
