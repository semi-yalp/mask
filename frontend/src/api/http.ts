import { useSettingsStore } from "@/stores/settings";

export type ApiRole = "admin" | "data";

/**
 * 统一 fetch 封装:按角色注入 X-Api-Key,非 2xx 规范化为
 * Error("[CODE] message") 或 Error("HTTP <status>")。
 */
export async function call<T>(method: string, url: string, body?: unknown, role: ApiRole = "admin"): Promise<T> {
  const headers: Record<string, string> = {};
  const key = useSettingsStore().keyFor(role);
  if (key) headers["X-Api-Key"] = key;
  if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body)
  });

  let payload: { code?: string; message?: string } | null = null;
  try { payload = await res.json(); } catch { /* 非 JSON 响应 */ }

  if (!res.ok) {
    const prefix = payload && payload.code ? `[${payload.code}] ` : "";
    const message = payload && payload.message ? payload.message : `HTTP ${res.status}`;
    throw new Error(prefix + message);
  }
  return payload as T;
}
