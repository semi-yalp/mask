import { call } from "@/api/http";
import type { RewriteResponse } from "@/types/domain";

export interface RewriteBody {
  metadataYaml?: string;
  policyYaml?: string;
  instance?: string;
  user?: string;
  groups?: string[];
  sql: string;
}

export function rewrite(body: RewriteBody): Promise<RewriteResponse> {
  return call("POST", "/api/rewrite", body);
}
