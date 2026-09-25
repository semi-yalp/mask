import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import router from "@/router";
import { useAuthStore } from "@/stores/auth";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  // LoginView 挂载会探测认证模式:统一 stub,避免未 stub 的 fetch 挂起
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { mode: "ldap", login: true, ldap: true })));
  localStorage.clear();
  setActivePinia(createPinia());
  const auth = useAuthStore();
  auth.mode = null;
  auth.token = "";
  auth.user = null;
  auth.expiresAt = 0;
});

function login() {
  const auth = useAuthStore();
  auth.token = "jwt-value";
  auth.user = { username: "carol", displayName: "Carol", role: "USER", groups: [] };
  auth.expiresAt = Date.now() + 60_000;
  auth.mode = "ldap";
}

describe("登录路由守卫", () => {
  it("LDAP 模式且未登录 → 重定向 /login 并携带 redirect", async () => {
    const auth = useAuthStore();
    auth.mode = "ldap";
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe("/audit");
  }, 15000);

  it("已登录访问 /login → 回总览", async () => {
    login();
    await router.push("/login");
    expect(router.currentRoute.value.name).toBe("dashboard");
  }, 15000);

  it("LDAP 模式且已登录 → 正常进入控制台", async () => {
    login();
    await router.push("/playground");
    expect(router.currentRoute.value.name).toBe("playground");
  }, 15000);

  it("模式探测失败（后端不可用）→ 按 API Key 模式放行，不锁死在登录页", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("backend down"); }));
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("audit");
  }, 15000);

  it("API Key 模式（mode=false）→ 无需登录", async () => {
    const auth = useAuthStore();
    auth.mode = "none";
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("audit");
  }, 15000);
});
