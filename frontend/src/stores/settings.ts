import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { ApiRole } from "@/api/http";

const STORAGE_KEY = "mask-policy-console-keys";

function readStored(): { admin?: string; data?: string } {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
  } catch {
    return {};
  }
}

/** 双 API Key(admin/data),持久化到 localStorage(与旧版页面共用同一 key,老用户无感)。 */
export const useSettingsStore = defineStore("settings", () => {
  const stored = readStored();
  const adminKey = ref(stored.admin || "");
  const dataKey = ref(stored.data || "");

  const gateConfigured = computed(() => Boolean(adminKey.value.trim() || dataKey.value.trim()));

  function setKeys(admin: string, data: string) {
    adminKey.value = admin;
    dataKey.value = data;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ admin: adminKey.value, data: dataKey.value }));
  }

  function keyFor(role: ApiRole): string {
    return (role === "admin" ? adminKey.value : dataKey.value).trim();
  }

  return { adminKey, dataKey, gateConfigured, setKeys, keyFor };
});
