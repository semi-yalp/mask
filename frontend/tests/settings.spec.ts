import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSettingsStore } from "@/stores/settings";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});

describe("settings store", () => {
  it("setKeys 持久化三把 Key 到 localStorage 且新实例能读回", () => {
    useSettingsStore().setKeys("a1", "d1", "q1");
    setActivePinia(createPinia());
    const fresh = useSettingsStore();
    expect(fresh.adminKey).toBe("a1");
    expect(fresh.dataKey).toBe("d1");
    expect(fresh.queryKey).toBe("q1");
    expect(fresh.gateConfigured).toBe(true);
  });

  it("三把 Key 都为空时门禁视为未配置(开放)", () => {
    const s = useSettingsStore();
    expect(s.gateConfigured).toBe(false);
  });

  it("仅查询 Key 时也视为已配置", () => {
    useSettingsStore().setKeys("", "", "only-query");
    expect(useSettingsStore().gateConfigured).toBe(true);
  });

  it("keyFor 按角色返回并去除首尾空白", () => {
    useSettingsStore().setKeys("  adm  ", "", "  q  ");
    const s = useSettingsStore();
    expect(s.keyFor("admin")).toBe("adm");
    expect(s.keyFor("data")).toBe("");
    expect(s.keyFor("query")).toBe("q");
  });

  it("旧版两键 localStorage 内容可读回(查询键缺省为空)", () => {
    localStorage.setItem("mask-policy-console-keys", JSON.stringify({ admin: "a", data: "d" }));
    setActivePinia(createPinia());
    const s = useSettingsStore();
    expect(s.adminKey).toBe("a");
    expect(s.dataKey).toBe("d");
    expect(s.queryKey).toBe("");
  });

  it("损坏的 localStorage 内容不抛错,按未配置处理", () => {
    localStorage.setItem("mask-policy-console-keys", "{broken");
    setActivePinia(createPinia());
    const s = useSettingsStore();
    expect(s.adminKey).toBe("");
    expect(s.gateConfigured).toBe(false);
  });
});
