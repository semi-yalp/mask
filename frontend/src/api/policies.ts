import { call, callRaw } from "@/api/http";
import type { Policy } from "@/types/domain";

const base = (instance: string) => "/api/instances/" + encodeURIComponent(instance) + "/policies";

export function listPolicies(instance: string): Promise<Policy[]> {
  return call("GET", base(instance));
}

export function createPolicy(instance: string, policy: Policy): Promise<void> {
  return call("POST", base(instance), policy);
}

export function updatePolicy(instance: string, policyName: string, policy: Policy): Promise<void> {
  return call("PUT", base(instance) + "/" + encodeURIComponent(policyName), policy);
}

export function deletePolicy(instance: string, policyName: string): Promise<void> {
  return call("DELETE", base(instance) + "/" + encodeURIComponent(policyName));
}

export interface ImportResult {
  created: number;
  updated: number;
}

/** 下载实例全部策略为 policies.yaml 文件。 */
export function exportPoliciesYaml(instance: string): Promise<Blob> {
  return callRaw("GET", base(instance) + "/export", { asBlob: true });
}

/** 导入 policies.yaml 文本：按名合并（同名更新、新名创建），返回计数。 */
export function importPoliciesYaml(instance: string, yaml: string): Promise<ImportResult> {
  return callRaw<ImportResult>("POST", base(instance) + "/import", { body: yaml, asJson: true });
}
