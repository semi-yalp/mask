import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { loginRequest, modeRequest, type AuthUser, type LoginResponse } from "@/api/auth";

const STORAGE_KEY = "mask-console-auth";

/** 令牌被后端拒绝时由 http 层广播，ConsoleLayout 监听后引导回登录页。 */
export const SESSION_EXPIRED_EVENT = "mask:session-expired";

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
 * 控制台登录态：LDAP 登录换来的 Bearer 令牌 + 用户身份，持久化到
 * localStorage（key 独立于旧版 API Key 存储，两套模式可共存）。
 * `ldapMode` 三态：null=未知（尚未探测）、true=强制登录、false=API Key 模式。
 */
export const useAuthStore = defineStore("auth", () => {
  const stored = readStored();
  const token = ref(stored.token || "");
  const user = ref<AuthUser | null>(stored.user || null);
  const expiresAt = ref(stored.expiresAt || 0);
  const ldapMode = ref<boolean | null>(null);

  const isLoggedIn = computed(() =>
    Boolean(token.value) && Date.now() < (expiresAt.value || 0));

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

  /** 登录成功后落地会话；失败时抛出带 code 的 Error 交给登录页展示。 */
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
    ldapMode.value = true;
    persist();
    return data.user;
  }

  /** 探测后端是否启用 LDAP 登录；探测失败按 API Key 模式放行（不能把人锁死在登录页）。 */
  async function loadMode(force = false): Promise<boolean> {
    if (ldapMode.value !== null && !force) {
      return ldapMode.value;
    }
    try {
      const { ok, body } = await modeRequest();
      ldapMode.value = ok ? Boolean(body?.ldap) : false;
    } catch {
      ldapMode.value = false;
    }
    return ldapMode.value;
  }

  /** 令牌被后端拒绝（过期/失效）时的统一处理：清会话，由守卫/调用方引导回登录页。 */
  function sessionExpired() {
    clear();
  }

  function logout() {
    clear();
  }

  return {
    token, user, expiresAt, ldapMode,
    isLoggedIn, role, isAdmin, isAuditorPlus,
    login, logout, sessionExpired, loadMode
  };
});
