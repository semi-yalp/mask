import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { call } from "@/api/http";
import { useAuthStore } from "@/stores/auth";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

type CapturedInit = { headers?: Record<string, string>; body?: string };

const okMock = () =>
  vi.fn(async (_url: string | URL | Request, _init?: CapturedInit) => jsonResponse(200, {}));

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

function loginAs(role: "ADMIN" | "AUDITOR" | "USER") {
  const auth = useAuthStore();
  auth.token = "jwt-value";
  auth.user = { username: "carol", displayName: "Carol", role, groups: ["mask-users"] };
  auth.expiresAt = Date.now() + 60_000;
  return auth;
}

describe("call() 的 Bearer 认证", () => {
  it("已登录时注入 Authorization: Bearer", async () => {
    loginAs("USER");
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/x");
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Authorization"]).toBe("Bearer jwt-value");
    expect(init?.headers?.["X-Api-Key"]).toBeUndefined();
  });

  it("令牌过期后不再携带任何认证头(none/匿名语义)", async () => {
    const auth = loginAs("USER");
    auth.expiresAt = Date.now() - 1000;
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/x");
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Authorization"]).toBeUndefined();
  });

  it("带令牌收到 401：清会话、广播过期事件并抛登录过期", async () => {
    const auth = loginAs("ADMIN");
    const events: string[] = [];
    window.addEventListener("mask:session-expired", () => events.push("fired"));
    vi.stubGlobal("fetch", vi.fn(async () =>
      jsonResponse(401, { code: "UNAUTHORIZED", message: "invalid token" })));
    await expect(call("GET", "/api/instances")).rejects.toThrow("[UNAUTHORIZED] 登录已过期，请重新登录");
    expect(auth.isLoggedIn).toBe(false);
    expect(auth.token).toBe("");
    expect(events).toEqual(["fired"]);
  });

  it("未登录收到 401：抛后端错误体(none 模式不应出现,保护性断言)", async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(401, { code: "UNAUTHORIZED", message: "missing bearer token" }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(call("GET", "/api/instances")).rejects.toThrow("[UNAUTHORIZED] missing bearer token");
  });
});
