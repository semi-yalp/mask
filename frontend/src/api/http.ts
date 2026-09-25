import { useAuthStore, SESSION_EXPIRED_EVENT } from "@/stores/auth";

/**
 * 统一 fetch 封装（arch-v2:API Key 门禁已退役,认证只有 Bearer 一种）。
 * 已登录（令牌未过期）→ Authorization: Bearer;未登录 → 无认证头(none 模式全开放)。
 * 非 2xx 规范化为 Error("[CODE] message") 或 Error("HTTP <status>")；
 * 带令牌收到 401 视为会话过期:清会话并引导回登录页。
 */
export async function call<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers = authHeaders();
  if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body)
  });
  return (await normalizeResponse(res, true)) as T;
}

/**
 * 原始文本/文件请求（策略导入导出）：与 call 同一认证头与错误归一化，
 * body 作为文本发送（Content-Type: text/yaml）；asBlob=true 返回 Blob 供下载，
 * asJson=true 把 JSON 响应解析为对象。
 * 注意:成功路径不预读 body(readSuccessBody=false),否则 text()/blob() 会因
 * "body already read" 失败——错误路径才解析 JSON 提取 code/message。
 */
export async function callRaw<T = string | Blob>(
  method: string,
  url: string,
  opts: { body?: string; asBlob?: boolean; asJson?: boolean } = {}
): Promise<T> {
  const headers = authHeaders();
  if (opts.body !== undefined) headers["Content-Type"] = "text/yaml";

  const res = await fetch(url, { method, headers, body: opts.body });
  await normalizeResponse(res, false);
  if (opts.asBlob) return (await res.blob()) as T;
  if (opts.asJson) return (await res.json()) as T;
  return (await res.text()) as T;
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const auth = useAuthStore();
  if (auth.isLoggedIn && auth.token) {
    headers["Authorization"] = `Bearer ${auth.token}`;
  }
  return headers;
}

/**
 * 非 2xx 抛规范化的 Error。readSuccessBody=true 时 2xx 返回 JSON 载荷
 * （非 JSON 响应为 null）；false 时 2xx 不触碰 body（交由调用方读取）。
 */
async function normalizeResponse(
  res: Response,
  readSuccessBody: boolean
): Promise<{ code?: string; message?: string } | null> {
  if (!res.ok) {
    let payload: { code?: string; message?: string } | null = null;
    try { payload = await res.json(); } catch { /* 非 JSON 错误体 */ }
    const auth = useAuthStore();
    if (res.status === 401 && auth.isLoggedIn) {
      // 令牌被后端拒绝（过期或被轮换）：清会话并广播，由守卫/布局接手引导
      auth.sessionExpired();
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
      throw new Error("[UNAUTHORIZED] 登录已过期，请重新登录");
    }
    const prefix = payload && payload.code ? `[${payload.code}] ` : "";
    const message = payload && payload.message ? payload.message : `HTTP ${res.status}`;
    throw new Error(prefix + message);
  }
  if (!readSuccessBody) {
    return null;
  }
  try { return await res.json(); } catch { return null; }
}
