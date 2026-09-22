import { call } from "@/api/http";
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
