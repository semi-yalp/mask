import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { loginRequest, modeRequest, type AuthUser, type LoginResponse } from "@/api/auth";

const STORAGE_KEY = "mask-console-auth";

/** 令牌被后端拒绝时由 http 层广播，ConsoleLayout 监听后引导回登录页。 */
export const SESSION_EXPIRED_EVENT = "mask:session-expired";

/** 后端认证姿态:none(无认证,全开放)/simple(本地用户)/ldap(目录服务)。 */
export type AuthMode = "none" | "simple" | "ldap";

interface PersistedSession {
  token?: string;
  user?: AuthUser;
  /** epoch ms；到点即视为未登录 */
  expiresAt?: number;
}

function readStored(): PersistedSession {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
  } catch {
    return {};
  }
}

/**
 * 控制台登录态:Bearer 令牌 + 用户身份,持久化到 localStorage。
 * `mode` 三态:null=未知(尚未探测)。none 模式不显示登录页;simple/ldap
 * 模式未登录跳登录页(由路由守卫消费)。
 */
export const useAuthStore = defineStore("auth", () => {
  const stored = readStored();
  const token = ref(stored.token || "");
  const user = ref<AuthUser | null>(stored.user || null);
  const expiresAt = ref(stored.expiresAt || 0);
  const mode = ref<AuthMode | null>(null);

  const isLoggedIn = computed(() =>
    Boolean(token.value) && Date.now() < (expiresAt.value || 0));

  /** 旧字段兼容:路由守卫与组件以此判断"是否强制登录"。 */
  const ldapMode = computed(() => (mode.value === null ? null : mode.value !== "none"));
  /** 当前认证姿态(none/simple/ldap),未知时为 null —— 设置页与侧边栏徽标使用。 */
  const authMode = computed(() => mode.value);

  const role = computed(() => user.value?.role || null);
  const isAdmin = computed(() => user.value?.role === "ADMIN");
  const isAuditorPlus = computed(() =>
    user.value?.role === "ADMIN" || user.value?.role === "AUDITOR");

  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: token.value,
      user: user.value,
      expiresAt: expiresAt.value
    }));
  }

  function clear() {
    token.value = "";
    user.value = null;
    expiresAt.value = 0;
    localStorage.removeItem(STORAGE_KEY);
  }

  /** 登录成功后落地会话;失败时抛出带 code 的 Error 交给登录页展示。 */
  async function login(username: string, password: string) {
    const { ok, status, body } = await loginRequest(username, password);
    if (!ok) {
      const code = body?.code || `HTTP ${status}`;
      const message = status === 401 ? "用户名或密码错误" : (body?.message || "登录失败");
      throw new Error(`[${code}] ${message}`);
    }
    const data = body as LoginResponse;
    token.value = data.token;
    user.value = data.user;
    expiresAt.value = Date.now() + data.expiresInSeconds * 1000;
    if (mode.value === null) mode.value = "simple";
    persist();
    return data.user;
  }

  /** 探测后端认证姿态;探测失败按无认证放行(不能把人锁死在登录页)。 */
  async function loadMode(force = false): Promise<AuthMode> {
    if (mode.value !== null && !force) {
      return mode.value;
    }
    try {
      const { ok, body } = await modeRequest();
      const raw = ok && body?.mode ? String(body.mode).toLowerCase() : (body?.ldap ? "ldap" : "none");
      mode.value = (["none", "simple", "ldap"] as const).includes(raw as AuthMode)
        ? (raw as AuthMode)
        : (body?.ldap ? "ldap" : "none");
    } catch {
      mode.value = "none";
    }
    return mode.value;
  }

  /** 令牌被后端拒绝(过期/失效)时的统一处理:清会话,由守卫/调用方引导回登录页。 */
  function sessionExpired() {
    clear();
  }

  function logout() {
    clear();
  }

  return {
    token, user, expiresAt, mode, ldapMode, authMode,
    isLoggedIn, role, isAdmin, isAuditorPlus,
    login, logout, sessionExpired, loadMode
  };
});
