/**
 * 认证 API。刻意不经过 http.ts 的 call() 封装：
 * 登录/模式探测发生在"还没有令牌"的阶段，且 http.ts 会反向依赖 auth store，
 * 这里用裸 fetch 保持零环依赖。
 */

export type ConsoleRole = "ADMIN" | "AUDITOR" | "USER";

export interface AuthUser {
  username: string;
  displayName: string;
  role: ConsoleRole;
  groups: string[];
}

export interface LoginResponse {
  token: string;
  user: AuthUser;
  expiresInSeconds: number;
}

async function parse(response: Response): Promise<{ ok: boolean; status: number; body: any }> {
  let body: any = null;
  try {
    body = await response.json();
  } catch {
    /* 非 JSON 响应 */
  }
  return { ok: response.ok, status: response.status, body };
}

export async function loginRequest(username: string, password: string) {
  return parse(await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  }));
}

export async function modeRequest() {
  return parse(await fetch("/api/auth/mode"));
}
