import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { call } from "@/api/http";
import { useAuthStore } from "@/stores/auth";
import { useSettingsStore } from "@/stores/settings";

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
  useSettingsStore().setKeys("", "");
});

function loginAs(role: "ADMIN" | "AUDITOR" | "USER") {
  const auth = useAuthStore();
  auth.token = "jwt-value";
  auth.user = { username: "carol", displayName: "Carol", role, groups: ["mask-users"] };
  auth.expiresAt = Date.now() + 60_000;
  return auth;
}

describe("call() 的 Bearer 认证", () => {
  it("已登录时注入 Authorization: Bearer 且不发 X-Api-Key", async () => {
    loginAs("USER");
    useSettingsStore().setKeys("adm-key", "dat-key");
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/x");
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Authorization"]).toBe("Bearer jwt-value");
    expect(init?.headers?.["X-Api-Key"]).toBeUndefined();
  });

  it("令牌过期后回落到 API Key 模式", async () => {
    const auth = loginAs("USER");
    auth.expiresAt = Date.now() - 1000;
    useSettingsStore().setKeys("adm-key", "dat-key");
    const fetchMock = okMock();
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/api/x", undefined, "admin");
    const init: CapturedInit | undefined = fetchMock.mock.calls[0]?.[1];
    expect(init?.headers?.["Authorization"]).toBeUndefined();
    expect(init?.headers?.["X-Api-Key"]).toBe("adm-key");
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

  it("未登录收到 401：维持旧行为（抛后端错误体）", async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(401, { code: "UNAUTHORIZED", message: "missing or invalid API key" }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(call("GET", "/api/instances")).rejects.toThrow("[UNAUTHORIZED] missing or invalid API key");
  });
});
