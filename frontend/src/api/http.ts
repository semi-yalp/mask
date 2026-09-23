import { useSettingsStore } from "@/stores/settings";
import { useAuthStore, SESSION_EXPIRED_EVENT } from "@/stores/auth";

export type ApiRole = "admin" | "data";

/**
 * 统一 fetch 封装。认证头优先级：
 * 1. 已登录（LDAP Bearer 令牌未过期）→ Authorization: Bearer；
 * 2. 否则按角色注入 X-Api-Key（旧部署的 API Key 模式保持不变）。
 * 非 2xx 规范化为 Error("[CODE] message") 或 Error("HTTP <status>")；
 * 带令牌收到 401 视为会话过期：清会话并引导回登录页。
 */
export async function call<T>(method: string, url: string, body?: unknown, role: ApiRole = "admin"): Promise<T> {
  const headers: Record<string, string> = {};
  const auth = useAuthStore();
  const bearer = auth.isLoggedIn ? auth.token : "";
  if (bearer) {
    headers["Authorization"] = `Bearer ${bearer}`;
  } else {
    const key = useSettingsStore().keyFor(role);
    if (key) headers["X-Api-Key"] = key;
  }
  if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body)
  });

  let payload: { code?: string; message?: string } | null = null;
  try { payload = await res.json(); } catch { /* 非 JSON 响应 */ }

  if (!res.ok) {
    if (res.status === 401 && bearer) {
      // 令牌被后端拒绝（过期或被轮换）：清会话并广播，由控制台布局接手引导
      auth.sessionExpired();
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
      throw new Error("[UNAUTHORIZED] 登录已过期，请重新登录");
    }
    const prefix = payload && payload.code ? `[${payload.code}] ` : "";
    const message = payload && payload.message ? payload.message : `HTTP ${res.status}`;
    throw new Error(prefix + message);
  }
  return payload as T;
}
