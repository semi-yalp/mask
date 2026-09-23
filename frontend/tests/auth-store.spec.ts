import { describe, it, expect, beforeEach, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAuthStore } from "@/stores/auth";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("auth store", () => {
  it("login 成功后落地令牌、用户与过期时间", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, {
      token: "jwt-value",
      user: { username: "amy", displayName: "Amy Admin", role: "ADMIN", groups: ["mask-admins"] },
      expiresInSeconds: 28800
    })));
    const auth = useAuthStore();
    const user = await auth.login("amy", "secret");
    expect(user.role).toBe("ADMIN");
    expect(auth.isLoggedIn).toBe(true);
    expect(auth.token).toBe("jwt-value");
    expect(auth.isAdmin).toBe(true);
    expect(JSON.parse(localStorage.getItem("mask-console-auth") || "{}").token).toBe("jwt-value");
  });

  it("401 登录失败抛错且不落地任何状态", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      jsonResponse(401, { code: "INVALID_CREDENTIALS", message: "invalid username or password" })));
    const auth = useAuthStore();
    await expect(auth.login("amy", "wrong")).rejects.toThrow("[INVALID_CREDENTIALS] 用户名或密码错误");
    expect(auth.isLoggedIn).toBe(false);
    expect(auth.token).toBe("");
  });

  it("过期后 isLoggedIn 为假", async () => {
    localStorage.setItem("mask-console-auth", JSON.stringify({
      token: "t",
      user: { username: "amy", displayName: "Amy", role: "USER", groups: [] },
      expiresAt: Date.now() - 1000
    }));
    const auth = useAuthStore();
    expect(auth.token).toBe("t");
    expect(auth.isLoggedIn).toBe(false);
  });

  it("logout / sessionExpired 清空并移除持久化", async () => {
    localStorage.setItem("mask-console-auth", JSON.stringify({
      token: "t",
      user: { username: "amy", displayName: "Amy", role: "AUDITOR", groups: [] },
      expiresAt: Date.now() + 60_000
    }));
    const auth = useAuthStore();
    expect(auth.isLoggedIn).toBe(true);
    expect(auth.isAuditorPlus).toBe(true);
    auth.logout();
    expect(auth.isLoggedIn).toBe(false);
    expect(localStorage.getItem("mask-console-auth")).toBeNull();
  });

  it("loadMode 探测 LDAP 开关，探测失败按 API Key 模式放行", async () => {
    const auth = useAuthStore();
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { ldap: true })));
    expect(await auth.loadMode(true)).toBe(true);
    expect(auth.ldapMode).toBe(true);

    const auth2 = useAuthStore();
    auth2.ldapMode = null;
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("backend down"); }));
    expect(await auth2.loadMode(true)).toBe(false);
    expect(auth2.ldapMode).toBe(false);
  });
});
