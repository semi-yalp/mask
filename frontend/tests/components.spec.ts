import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";
import PoliciesTab from "@/views/instances/PoliciesTab.vue";
import UdfsTab from "@/views/instances/UdfsTab.vue";
import TablesTab from "@/views/instances/TablesTab.vue";
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

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

describe("PoliciesTab 新建策略抽屉", () => {
  it("点击「新建策略」打开抽屉,填写并提交 createPolicy", async () => {
    const wrapper = mountWithUi(PoliciesTab, { instance: "crm" });
    await flushPromises();

    const createBtn = wrapper.findAll("button").find((b) => b.text().includes("新建策略"))!;
    expect(createBtn).toBeTruthy();
    await createBtn.trigger("click");
    await flushPromises();

    // 抽屉打开:表单字段出现
    const drawer = wrapper.find(".el-drawer__body");
    expect(drawer.exists()).toBe(true);

    const inputs = wrapper.findAll(".el-drawer__body input");
    expect(inputs.length).toBeGreaterThan(5);

    // 填写(name/priority/类型select/switch/catalog/schema/table/columns/users/groups/udf/args/filterExpr)
    await inputs[0]!.setValue("mask_phone_policy");
    await inputs[4]!.setValue("crm");
    await inputs[5]!.setValue("public");
    await inputs[6]!.setValue("customer");
    await inputs[7]!.setValue("phone");
    await inputs[10]!.setValue("mask_phone");
    await inputs[11]!.setValue("3, 4");

    const saveBtn = wrapper.findAll(".el-drawer__body button").find((b) => b.text().includes("保存策略"))!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(policiesApi.createPolicy).toHaveBeenCalledTimes(1);
    const [, policyBody] = vi.mocked(policiesApi.createPolicy).mock.calls[0]!;
    expect(policyBody.name).toBe("mask_phone_policy");
    expect(policyBody.policyType).toBe("datamask");
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

    const createBtn = wrapper.findAll("button").find((b) => b.text().includes("注册 UDF"))!;
    await createBtn.trigger("click");
    await flushPromises();
    expect(wrapper.find(".el-drawer__body").exists()).toBe(true);

    const nameInput = wrapper.findAll(".el-drawer__body input").find((i) => (i.attributes("placeholder") || "").includes("mask_phone"))!;
    await nameInput.setValue("mask_email");

    const saveBtn = wrapper.findAll(".el-drawer__body button").find((b) => b.text().includes("保存 UDF"))!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(udfsApi.createUdf).toHaveBeenCalledWith("crm", expect.objectContaining({
      name: "mask_email",
      signatures: [expect.objectContaining({ returns: "varchar" })]
    }));
  });
});

describe("TablesTab 表结构编辑", () => {
  it("添加列/添加表触发脏状态,保存调用 putTables", async () => {
    const wrapper = mountWithUi(TablesTab, { inst });
    await flushPromises();

    const addColBtn = wrapper.findAll("button").find((b) => b.text().includes("添加列"))!;
    await addColBtn.trigger("click");
    const addTableBtn = wrapper.findAll("button").find((b) => b.text().includes("添加表"))!;
    await addTableBtn.trigger("click");
    expect(wrapper.text()).toContain("有未保存修改");

    const saveBtn = wrapper.findAll("button").find((b) => b.text().includes("保存全部表结构"))!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(instancesApi.putTables).toHaveBeenCalledTimes(1);
    const [, tablesArg] = vi.mocked(instancesApi.putTables).mock.calls[0]!;
    expect(tablesArg).toHaveLength(2);
    expect(tablesArg[0]!.name).toBe("customer");
    expect(tablesArg[0]!.columns).toHaveLength(2);
    expect(tablesArg[1]!.name).toBe("");
  });
});
