import { call } from "@/api/http";
import type { AuditQueryResponse } from "@/types/domain";

export interface AuditFilters {
  eventType?: string;
  outcome?: string;
  instance?: string;
  resourceType?: string;
  action?: string;
  user?: string;
  from?: string;
  to?: string;
}

const DAY_MS = 86_400_000;
const MAX_RANGE_MS = 7 * DAY_MS;

/**
 * 构造 /api/audit/events 查询串(校验规则与 AuditQueryController 对齐):
 * to 缺省 now、from 缺省 to-24h;from<to;范围 ≤7 天;page ≥0;size 1..200。
 */
export function buildAuditQuery(f: AuditFilters, page: number, size: number, now: Date = new Date()): string {
  if (!Number.isInteger(page) || page < 0) throw new Error("page 必须 ≥ 0");
  if (!Number.isInteger(size) || size < 1 || size > 200) throw new Error("size 必须在 1..200");

  const to = f.to || now.toISOString();
  const toMs = Date.parse(to);
  const from = f.from || new Date(toMs - DAY_MS).toISOString();
  const fromMs = Date.parse(from);

  if (!(fromMs < toMs)) throw new Error("from 必须早于 to");
  if (toMs - fromMs > MAX_RANGE_MS) throw new Error("时间范围不能超过 7 天");

  const q = new URLSearchParams();
  for (const key of ["eventType", "outcome", "instance", "resourceType", "action", "user"] as const) {
    const v = f[key]?.trim();
    if (v) q.set(key, v);
  }
  q.set("from", from);
  q.set("to", to);
  q.set("page", String(page));
  q.set("size", String(size));
  return q.toString();
}

export function searchAudit(f: AuditFilters, page: number, size: number): Promise<AuditQueryResponse> {
  return call("GET", "/api/audit/events?" + buildAuditQuery(f, page, size));
}
