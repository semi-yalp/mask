import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import router from "@/router";
import { useAuthStore } from "@/stores/auth";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  const auth = useAuthStore();
  auth.ldapMode = null;
  auth.token = "";
  auth.user = null;
  auth.expiresAt = 0;
});

function login() {
  const auth = useAuthStore();
  auth.token = "jwt-value";
  auth.user = { username: "carol", displayName: "Carol", role: "USER", groups: [] };
  auth.expiresAt = Date.now() + 60_000;
  auth.ldapMode = true;
}

describe("登录路由守卫", () => {
  it("LDAP 模式且未登录 → 重定向 /login 并携带 redirect", async () => {
    const auth = useAuthStore();
    auth.ldapMode = true;
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe("/audit");
  });

  it("已登录访问 /login → 回总览", async () => {
    login();
    await router.push("/login");
    expect(router.currentRoute.value.name).toBe("dashboard");
  });

  it("LDAP 模式且已登录 → 正常进入控制台", async () => {
    login();
    await router.push("/playground");
    expect(router.currentRoute.value.name).toBe("playground");
  });

  it("模式探测失败（后端不可用）→ 按 API Key 模式放行，不锁死在登录页", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("backend down"); }));
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("audit");
  });

  it("API Key 模式（mode=false）→ 无需登录", async () => {
    const auth = useAuthStore();
    auth.ldapMode = false;
    await router.push("/audit");
    expect(router.currentRoute.value.name).toBe("audit");
  });
});
