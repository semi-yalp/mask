import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import PoliciesTab from "@/views/instances/PoliciesTab.vue";
import UdfsTab from "@/views/instances/UdfsTab.vue";
import TablesTab from "@/views/instances/TablesTab.vue";
import * as policiesApi from "@/api/policies";
import * as udfsApi from "@/api/udfs";
import * as instancesApi from "@/api/instances";
import type { InstanceInfo, Policy, Udf } from "@/types/domain";

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
    const wrapper = mount(PoliciesTab, { props: { instance: "crm" } });
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

    // 填写:策略名 / catalog / schema / table / columns / udf / args
    const byPh = (ph: string) => inputs.find((i) => (i.attributes("placeholder") || "").includes(ph))!;
    await byPh("mask_phone_policy").setValue("mask_phone_policy");
    await byPh("catalog").setValue("crm");
    await byPh("schema").setValue("public");
    await byPh("table").setValue("customer");
    await byPh("columns").setValue("phone");
    await byPh("mask_phone").setValue("mask_phone");
    await byPh("3, 4").setValue("3, 4");

    const saveBtn = wrapper.findAll(".el-drawer__body button").find((b) => b.text().includes("保存策略"))!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(policiesApi.createPolicy).toHaveBeenCalledWith("crm", expect.objectContaining({
      name: "mask_phone_policy",
      policyType: "datamask",
      resource: expect.objectContaining({ table: "customer", columns: ["phone"] }),
      udf: "mask_phone",
      arguments: [3, 4]
    }));
  });
});

describe("UdfsTab 注册 UDF", () => {
  it("点击「注册 UDF」打开抽屉,提交多签名 UDF", async () => {
    const wrapper = mount(UdfsTab, { props: { instance: "crm" } });
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
    const wrapper = mount(TablesTab, { props: { inst }, attrs: { onRefresh: vi.fn() } });
    await flushPromises();

    const addColBtn = wrapper.findAll("button").find((b) => b.text().includes("添加列"))!;
    await addColBtn.trigger("click");
    const addTableBtn = wrapper.findAll("button").find((b) => b.text().includes("添加表"))!;
    await addTableBtn.trigger("click");
    expect(wrapper.text()).toContain("有未保存修改");

    const saveBtn = wrapper.findAll("button").find((b) => b.text().includes("保存全部表结构"))!;
    await saveBtn.trigger("click");
    await flushPromises();

    expect(instancesApi.putTables).toHaveBeenCalledWith("crm", expect.objectContaining({
      tables: expect.anything()
    }));
  });
});
