import { useSettingsStore } from "@/stores/settings";
import { useAuthStore, SESSION_EXPIRED_EVENT } from "@/stores/auth";

export type ApiRole = "admin" | "data" | "query";

/** 401 统一广播事件:ConsoleLayout 监听后提示配置对应 Key 并引导到设置页。 */
export const UNAUTHORIZED_EVENT = "mask-console:unauthorized";

/**
 * 统一 fetch 封装。认证头优先级：
 * 1. 已登录（LDAP Bearer 令牌未过期）→ Authorization: Bearer；
 * 2. 否则按角色注入 X-Api-Key（旧部署的 API Key 模式保持不变）。
 * 非 2xx 规范化为 Error("[CODE] message") 或 Error("HTTP <status>")；
 * 带令牌收到 401 视为会话过期：清会话并引导回登录页；
 * 无令牌收到 401 广播 UNAUTHORIZED_EVENT（提示配置对应 Key 并引导到设置页）。
 */
export async function call<T>(method: string, url: string, body?: unknown, role: ApiRole = "admin"): Promise<T> {
  const headers = authHeaders(role);
  if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body)
  });
  return (await normalizeResponse(res, role, url)) as T;
}

/**
 * 原始文本/文件请求（策略导入导出）：与 call 同一认证头与错误归一化，
 * body 作为文本发送（Content-Type: text/yaml）；asBlob=true 返回 Blob 供下载，
 * asJson=true 把 JSON 响应解析为对象。
 */
export async function callRaw<T = string | Blob>(
  method: string,
  url: string,
  opts: { body?: string; asBlob?: boolean; asJson?: boolean; role?: ApiRole } = {}
): Promise<T> {
  const role = opts.role ?? "admin";
  const headers = authHeaders(role);
  if (opts.body !== undefined) headers["Content-Type"] = "text/yaml";

  const res = await fetch(url, { method, headers, body: opts.body });
  await normalizeResponse(res, role, url);
  if (opts.asBlob) return (await res.blob()) as T;
  if (opts.asJson) return (await res.json()) as T;
  return (await res.text()) as T;
}

function authHeaders(role: ApiRole): Record<string, string> {
  const headers: Record<string, string> = {};
  const auth = useAuthStore();
  const bearer = auth.isLoggedIn ? auth.token : "";
  if (bearer) {
    headers["Authorization"] = `Bearer ${bearer}`;
  } else {
    const key = useSettingsStore().keyFor(role);
    if (key) headers["X-Api-Key"] = key;
  }
  return headers;
}

/** 非 2xx 抛规范化的 Error；2xx 返回 JSON payload（非 JSON 响应为 null）。 */
async function normalizeResponse(
  res: Response,
  role: ApiRole,
  url: string
): Promise<{ code?: string; message?: string } | null> {
  let payload: { code?: string; message?: string } | null = null;
  try { payload = await res.json(); } catch { /* 非 JSON 响应 */ }

  if (!res.ok) {
    const auth = useAuthStore();
    const bearer = auth.isLoggedIn ? auth.token : "";
    if (res.status === 401 && bearer) {
      // 令牌被后端拒绝（过期或被轮换）：清会话并广播，由控制台布局接手引导
      auth.sessionExpired();
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
      throw new Error("[UNAUTHORIZED] 登录已过期，请重新登录");
    }
    if (res.status === 401) {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { role, url } }));
    }
    const prefix = payload && payload.code ? `[${payload.code}] ` : "";
    const message = payload && payload.message ? payload.message : `HTTP ${res.status}`;
    throw new Error(prefix + message);
  }
  return payload;
}