import { createInstance } from "@/api/instances";
import { createPolicy } from "@/api/policies";
import { createUdf } from "@/api/udfs";
import { listInstances } from "@/api/instances";

/**
 * 「载入示例实例」:一个最小的 PG 脱敏闭环(1 表 + 1 UDF + 1 策略),
 * 从 AccessManager 内联代码抽出,便于复用与测试。
 */
export async function ensureSampleInstance(name = "crm"): Promise<"created" | "exists"> {
  const existing = await listInstances();
  if (existing.some((i) => i.name === name)) return "exists";
  await createInstance(name, "postgresql", [{
    catalog: "crm", schema: "public", name: "customer",
    columns: [
      { name: "id", type: "bigint" },
      { name: "phone", type: "varchar" },
      { name: "email", type: "varchar" },
      { name: "status", type: "varchar" }
    ]
  }]);
  await createUdf(name, {
    name: "mask_phone",
    signatures: [{ params: ["varchar", "integer", "integer"], returns: "varchar" }]
  });
  await createPolicy(name, {
    name: "mask_phone_policy", policyType: "datamask", isEnabled: true, priority: 1,
    resource: { catalog: "crm", schema: "public", table: "customer", columns: ["phone"] },
    subjects: { users: ["*"], groups: [] }, udf: "mask_phone", arguments: [3, 4], filterExpr: null
  });
  return "created";
}
