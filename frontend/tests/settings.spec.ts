import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSettingsStore } from "@/stores/settings";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("settings store", () => {
  it("setKeys 持久化到 localStorage 且新实例能读回", () => {
    useSettingsStore().setKeys("a1", "d1");
    setActivePinia(createPinia());
    const fresh = useSettingsStore();
    expect(fresh.adminKey).toBe("a1");
    expect(fresh.dataKey).toBe("d1");
    expect(fresh.gateConfigured).toBe(true);
  });

  it("两个 Key 都为空时门禁视为未配置(开放)", () => {
    const s = useSettingsStore();
    expect(s.gateConfigured).toBe(false);
  });

  it("仅管理 Key 时也视为已配置", () => {
    useSettingsStore().setKeys("only-admin", "");
    expect(useSettingsStore().gateConfigured).toBe(true);
  });

  it("keyFor 按角色返回并去除首尾空白", () => {
    useSettingsStore().setKeys("  adm  ", "");
    const s = useSettingsStore();
    expect(s.keyFor("admin")).toBe("adm");
    expect(s.keyFor("data")).toBe("");
  });

  it("损坏的 localStorage 内容不抛错,按未配置处理", () => {
    localStorage.setItem("mask-policy-console-keys", "{broken");
    setActivePinia(createPinia());
    const s = useSettingsStore();
    expect(s.adminKey).toBe("");
    expect(s.gateConfigured).toBe(false);
  });
});
