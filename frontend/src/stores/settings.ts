import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { ApiRole } from "@/api/http";

const STORAGE_KEY = "mask-policy-console-keys";

function readStored(): { admin?: string; data?: string; query?: string } {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
  } catch {
    return {};
  }
}

/**
 * 三把 API Key(admin/data/query)持久化到 localStorage。
 * Key 名与旧版页面共用(老用户无感):admin = 策略服务管理面,
 * data = 生效配置数据面,query = 统一查询数据面(mask-query)。
 */
export const useSettingsStore = defineStore("settings", () => {
  const stored = readStored();
  const adminKey = ref(stored.admin || "");
  const dataKey = ref(stored.data || "");
  const queryKey = ref(stored.query || "");

  const gateConfigured = computed(() =>
    Boolean(adminKey.value.trim() || dataKey.value.trim() || queryKey.value.trim()));

  function setKeys(admin: string, data: string, query = "") {
    adminKey.value = admin;
    dataKey.value = data;
    queryKey.value = query;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      admin: adminKey.value, data: dataKey.value, query: queryKey.value
    }));
  }

  function keyFor(role: ApiRole): string {
    const v = role === "admin" ? adminKey.value : role === "data" ? dataKey.value : queryKey.value;
    return v.trim();
  }

  return { adminKey, dataKey, queryKey, gateConfigured, setKeys, keyFor };
});
