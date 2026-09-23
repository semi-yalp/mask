import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";
import PoliciesTab from "@/views/policymanager/PoliciesTab.vue";
import UdfsTab from "@/views/policymanager/UdfsTab.vue";
import TablesTab from "@/views/policymanager/TablesTab.vue";
import * as policiesApi from "@/api/policies";
import * as udfsApi from "@/api/udfs";
import * as instancesApi from "@/api/instances";
import type { InstanceInfo } from "@/types/domain";

const mountWithUi = (component: Parameters<typeof mount>[0], props: Record<string, unknown>) =>
  mount(component, {
    props,
    global: { plugins: [ElementPlus] }
  });

vi.mock("@/api/policies", () => ({
  listPolicies: vi.fn(async () => []),
  createPolicy: vi.fn(async () => undefined),
  updatePolicy: vi.fn(async () => undefined),
  deletePolicy: vi.fn(async () => undefined)
}));
vi.mock("@/api/udfs", () => ({
  listUdfs: vi.fn(async () => []),
  createUdf: vi.fn(async () => undefined),
  updateUdf: vi.fn(async () => undefined),
  deleteUdf: vi.fn(async () => undefined)
}));
vi.mock("@/api/instances", () => ({
  putTables: vi.fn(async () => undefined)
}));

const inst: InstanceInfo = {
  name: "crm", dialect: "postgresql",
  tables: [{ catalog: "crm", schema: "public", name: "customer", columns: [{ name: "id", type: "bigint" }] }]
};

const inputByPlaceholder = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll(".el-drawer__body input").find((i) => (i.attributes("placeholder") || "").includes(text));

const buttonByText = (wrapper: ReturnType<typeof mount>, scope: string, text: string) =>
  wrapper.findAll(`${scope} button`).find((b) => b.text().includes(text))!;

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

describe("PoliciesTab 新建策略抽屉", () => {
  it("点击「Add New Policy」打开分区式抽屉,填写并提交 createPolicy", async () => {
    const wrapper = mountWithUi(PoliciesTab, { instance: "crm" });
    await flushPromises();

    const createBtn = buttonByText(wrapper, "", "Add New Policy");
    expect(createBtn).toBeTruthy();
    await createBtn.trigger("click");
    await flushPromises();

    // 抽屉打开:分区标题与表单字段出现
    expect(wrapper.find(".el-drawer__body").exists()).toBe(true);
    expect(wrapper.findAll(".el-drawer__body .section").length).toBeGreaterThanOrEqual(3);

    const set = (placeholder: string, value: string) =>
      inputByPlaceholder(wrapper, placeholder)!.setValue(value);
    set("mask_phone_policy", "mask_phone_policy");
    set("可选,小者优先", "1");
    set("catalog", "crm");
    set("schema", "public");
    set("table", "customer");
    set("columns,逗号分隔", "phone");
    set("udf 名", "mask_phone");
    set("arguments", "3, 4");

    const saveBtn = buttonByText(wrapper, ".el-drawer__body", "保存策略")!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(policiesApi.createPolicy).toHaveBeenCalledTimes(1);
    const [, policyBody] = vi.mocked(policiesApi.createPolicy).mock.calls[0]!;
    expect(policyBody.name).toBe("mask_phone_policy");
    expect(policyBody.policyType).toBe("datamask");
    expect(policyBody.priority).toBe(1);
    expect(policyBody.resource).toEqual({ catalog: "crm", schema: "public", table: "customer", columns: ["phone"] });
    expect(policyBody.subjects).toEqual({ users: [], groups: [] });
    expect(policyBody.udf).toBe("mask_phone");
    expect(policyBody.arguments).toEqual([3, 4]);
  });
});

describe("UdfsTab 注册 UDF", () => {
  it("点击「注册 UDF」打开抽屉,提交多签名 UDF", async () => {
    const wrapper = mountWithUi(UdfsTab, { instance: "crm" });
    await flushPromises();

    const createBtn = buttonByText(wrapper, "", "注册 UDF");
    await createBtn.trigger("click");
    await flushPromises();
    expect(wrapper.find(".el-drawer__body").exists()).toBe(true);

    const nameInput = wrapper.findAll(".el-drawer__body input").find((i) => (i.attributes("placeholder") || "").includes("mask_phone"))!;
    await nameInput.setValue("mask_email");

    const saveBtn = buttonByText(wrapper, ".el-drawer__body", "保存 UDF")!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(udfsApi.createUdf).toHaveBeenCalledWith("crm", expect.objectContaining({
      name: "mask_email",
      signatures: [expect.objectContaining({ returns: "varchar" })]
    }));
  });
});

describe("TablesTab 表结构编辑", () => {
  it("抽屉添加表(含加列)触发脏状态,保存调用 putTables", async () => {
    const wrapper = mountWithUi(TablesTab, { inst });
    await flushPromises();

    const addTableBtn = buttonByText(wrapper, "", "添加表")!;
    await addTableBtn.trigger("click");
    await flushPromises();
    expect(wrapper.find(".el-drawer__body").exists()).toBe(true);

    // 抽屉内加一列(默认已有 id bigint)
    await buttonByText(wrapper, ".el-drawer__body", "添加列").trigger("click");
    await inputByPlaceholder(wrapper, "表名")!.setValue("customer2");
    await buttonByText(wrapper, ".el-drawer__body", "确定").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("有未保存修改");

    const saveBtn = buttonByText(wrapper, "", "保存全部")!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(instancesApi.putTables).toHaveBeenCalledTimes(1);
    const [, tablesArg] = vi.mocked(instancesApi.putTables).mock.calls[0]!;
    expect(tablesArg).toHaveLength(2);
    expect(tablesArg[1]!.name).toBe("customer2");
    expect(tablesArg[1]!.columns).toHaveLength(2);
  });
});
