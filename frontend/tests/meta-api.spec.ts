import { describe, expect, it, vi, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";

/** 元数据服务 api 层:网关前缀 /api/meta/ 与方法/请求体形态(与 nginx/vite 重写约定对齐)。 */

const fetchMock = vi.fn();
vi.stubGlobal("fetch", fetchMock);

function lastCall(): { method: string; url: string; body?: string } {
  const c = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
  return { method: c[1].method, url: String(c[0]), body: c[1].body };
}

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
  localStorage.setItem("mask-policy-console-keys", JSON.stringify({ admin: "admin-key" }));
});

describe("meta api gateway prefix", () => {
  it("list uses /api/meta/instances", async () => {
    const { listMetaInstances } = await import("@/api/meta");
    await listMetaInstances();
    expect(lastCall()).toMatchObject({ method: "GET", url: "/api/meta/instances" });
  });

  it("get/create/delete hit /api/meta/instances/{name}", async () => {
    const { getMetaInstance, createMetaInstance, deleteMetaInstance } = await import("@/api/meta");
    await getMetaInstance("pg prod");
    expect(lastCall().url).toBe("/api/meta/instances/pg%20prod");
    await createMetaInstance({ name: "a", dialect: "postgresql" });
    expect(lastCall()).toMatchObject({ method: "POST", url: "/api/meta/instances" });
    await deleteMetaInstance("a");
    expect(lastCall()).toMatchObject({ method: "DELETE", url: "/api/meta/instances/a" });
  });

  it("import posts to /api/meta/instances/import with yaml body", async () => {
    const { importMetaYaml } = await import("@/api/meta");
    await importMetaYaml({ name: "a", dialect: "mysql", metadataYaml: "metadata:\n  tables: []" });
    const c = lastCall();
    expect(c).toMatchObject({ method: "POST", url: "/api/meta/instances/import" });
    expect(JSON.parse(c.body!)).toEqual({ name: "a", dialect: "mysql", metadataYaml: "metadata:\n  tables: []" });
  });

  it("collect posts to the instance collect endpoint", async () => {
    const { collectMetaInstance } = await import("@/api/meta");
    await collectMetaInstance("pg_prod");
    expect(lastCall()).toMatchObject({ method: "POST", url: "/api/meta/instances/pg_prod/collect" });
  });
});
