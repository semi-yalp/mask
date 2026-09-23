import { call } from "@/api/http";

/** 风险事件(后端 StatsCalculator.eventWire 结构) */
export interface RiskEventVo {
  id: string;
  timestamp: string;
  service?: string;
  eventType: string;
  outcome: string;
  user?: string;
  sourceIp?: string;
  dialect?: string;
  masked?: boolean;
  rowFiltered?: boolean;
  instance?: string;
  errorCode?: string;
  errorMessage?: string;
  rowCount?: number | null;
  sql?: string;
  rewrittenSql?: string;
  tables?: string[];
  sensitiveColumns?: string[];
  hits: { ruleId: string; ruleName: string; severity: string; evidence: string }[];
  riskScore: number;
  topSeverity: string;
}

export interface AlertVo {
  id: string;
  ruleId: string;
  ruleName: string;
  category: string;
  severity: string;
  user: string;
  sourceIp?: string;
  title: string;
  description: string;
  sqlSnippet: string;
  status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  ackNote?: string;
  eventCount: number;
  eventIds: string[];
  createdAt: number;
  updatedAt: number;
  lastHitAt: number;
  events?: RiskEventVo[];
}

export interface RiskRuleVo {
  id: string;
  name: string;
  description: string;
  kind: "BUILT_IN" | "CUSTOM";
  category: string;
  severity: string;
  enabled: boolean;
  params: Record<string, string | number>;
  spec?: {
    conditions: { field: string; op: string; value: string }[];
    window?: { seconds: number; count: number; groupBy: string };
  };
  createdAt: string;
  updatedAt: string;
  hitCount: number;
}

export interface SensitiveColumnVo {
  columnKey: string;
  sensitivity: string;
  category: string;
  enabled: boolean;
  source: string;
  accesses24h: number;
  users24h: number;
}

export interface OverviewVo {
  generatedAt: number;
  windowHours: number;
  bucketMinutes: number;
  events: {
    total: number;
    flagged: number;
    hitRate: number;
    avgScore: number;
    bySeverity: Record<string, number>;
  };
  alerts: {
    open: number;
    acknowledged: number;
    resolvedInWindow: number;
    bySeverity: Record<string, number>;
  };
  timeseries: { bucket: number; events: number; flagged: number; alerts: number }[];
  topUsers: { user: string; events: number; flagged: number; riskScore: number; openAlerts: number }[];
  topRules: { ruleId: string; ruleName: string; category: string; severity: string; hits: number }[];
  topSensitiveColumns: { columnKey: string; sensitivity: string; category: string; accesses: number; distinctUsers: number }[];
  recentAlerts: AlertVo[];
}

export interface ScenarioVo {
  id: string;
  name: string;
  description: string;
  severity: string;
}

export interface EventFilters {
  severity?: string;
  ruleId?: string;
  eventType?: string;
  outcome?: string;
  user?: string;
  keyword?: string;
  minutes?: number;
}

/** 构造 /api/risk/events 查询串:仅透传非空过滤项,page ≥0,size 1..200。 */
export function buildRiskEventQuery(f: EventFilters, page: number, size: number): string {
  if (!Number.isInteger(page) || page < 0) throw new Error("page 必须 ≥ 0");
  if (!Number.isInteger(size) || size < 1 || size > 200) throw new Error("size 必须在 1..200");
  const q = new URLSearchParams();
  for (const key of ["severity", "ruleId", "eventType", "outcome", "user", "keyword"] as const) {
    const v = f[key]?.trim();
    if (v) q.set(key, v);
  }
  q.set("minutes", String(f.minutes ?? 1440));
  q.set("page", String(page));
  q.set("size", String(size));
  return q.toString();
}

export function fetchOverview(windowHours = 24, bucketMinutes = 30): Promise<OverviewVo> {
  return call("GET", `/api/risk/stats/overview?windowHours=${windowHours}&bucketMinutes=${bucketMinutes}`);
}

export function fetchRiskEvents(f: EventFilters, page: number, size: number): Promise<{ total: number; events: RiskEventVo[] }> {
  return call("GET", "/api/risk/events?" + buildRiskEventQuery(f, page, size));
}

export function fetchAlerts(params: {
  status?: string; severity?: string; ruleId?: string; keyword?: string; page?: number; size?: number;
}): Promise<{ total: number; alerts: AlertVo[] }> {
  const q = new URLSearchParams();
  if (params.status) q.set("status", params.status);
  if (params.severity) q.set("severity", params.severity);
  if (params.ruleId) q.set("ruleId", params.ruleId);
  if (params.keyword) q.set("keyword", params.keyword);
  q.set("page", String(params.page ?? 0));
  q.set("size", String(params.size ?? 50));
  return call("GET", "/api/risk/alerts?" + q.toString());
}

export function fetchAlertDetail(id: string): Promise<AlertVo> {
  return call("GET", `/api/risk/alerts/${id}`);
}

export function updateAlertStatus(id: string, status: string, note?: string): Promise<AlertVo> {
  return call("PUT", `/api/risk/alerts/${id}/status`, { status, note });
}

export function fetchRules(): Promise<RiskRuleVo[]> {
  return call("GET", "/api/risk/rules");
}

export function fetchRuleMeta(): Promise<{ fields: string[]; ops: string[]; severities: string[] }> {
  return call("GET", "/api/risk/rules/meta");
}

export interface RuleDraft {
  name: string;
  description?: string;
  category?: string;
  severity: string;
  enabled?: boolean;
  conditions: { field: string; op: string; value: string }[];
  window?: { seconds: number; count: number; groupBy: string } | null;
}

export function createRule(draft: RuleDraft): Promise<RiskRuleVo> {
  return call("POST", "/api/risk/rules", draft);
}

export function updateRule(id: string, body: object): Promise<RiskRuleVo> {
  return call("PUT", `/api/risk/rules/${id}`, body);
}

export function toggleRule(id: string, enabled: boolean): Promise<RiskRuleVo> {
  return call("PUT", `/api/risk/rules/${id}/enabled?enabled=${enabled}`);
}

export function deleteRule(id: string): Promise<{ deleted: string }> {
  return call("DELETE", `/api/risk/rules/${id}`);
}

export function testRuleDraft(draft: RuleDraft & { id?: string }, limit = 1000): Promise<{
  scanned: number; matched: number; samples: { eventId: string; timestamp: string; user: string; sql: string; evidence: string }[];
}> {
  return call("POST", "/api/risk/rules/test", { ...draft, limit });
}

export function fetchSensitiveColumns(keyword?: string): Promise<{ total: number; columns: SensitiveColumnVo[] }> {
  const q = keyword ? `?keyword=${encodeURIComponent(keyword)}` : "";
  return call("GET", "/api/risk/sensitive-columns" + q);
}

export function createSensitiveColumn(body: { columnKey: string; sensitivity: string; category: string; enabled: boolean }): Promise<SensitiveColumnVo> {
  return call("POST", "/api/risk/sensitive-columns", body);
}

export function updateSensitiveColumn(key: string, body: { sensitivity?: string; category?: string; enabled?: boolean }): Promise<SensitiveColumnVo> {
  return call("PUT", `/api/risk/sensitive-columns/${key}`, body);
}

export function deleteSensitiveColumn(key: string): Promise<{ deleted: string }> {
  return call("DELETE", `/api/risk/sensitive-columns/${key}`);
}

export function fetchScenarios(): Promise<ScenarioVo[]> {
  return call("GET", "/api/risk/demo/scenarios");
}

export function runScenario(id: string): Promise<{
  scenario: string; accepted: number; flagged: number; hits: number; alertsCreated: number; alertsUpdated: number;
}> {
  return call("POST", `/api/risk/demo/simulate/${id}`);
}

export function reseedDemo(): Promise<{ seededEvents: number; alerts: number }> {
  return call("POST", "/api/risk/demo/seed");
}

// ---- 通知渠道 ----

export interface NotificationVo {
  id: string;
  createdAt: number;
  alertId: string;
  ruleId: string;
  severity: string;
  user: string;
  title: string;
  delivery: "SENT" | "RECORDED" | "FAILED";
  detail: string;
}

export function fetchNotifications(limit = 100): Promise<{
  webhookConfigured: boolean; total: number; notifications: NotificationVo[];
}> {
  return call("GET", `/api/risk/notifications?limit=${limit}`);
}

// ---- 一键阻断(联动 policy-server) ----

export interface BlockVo {
  user: string;
  instance: string;
  policyNames?: string[];
  policyCount?: number;
  blockedAt?: number;
  note?: string;
  policies?: string[];
  alreadyBlocked?: boolean;
  verification?: string;
  action?: "BLOCK" | "UNBLOCK";
}

export function fetchBlocks(): Promise<{ configured: boolean; blocks: BlockVo[] }> {
  return call("GET", "/api/risk/block");
}

export function blockUser(user: string, instance?: string, note?: string): Promise<BlockVo> {
  return call("POST", "/api/risk/block", { user, instance, note });
}

export function unblockUser(user: string, instance?: string): Promise<BlockVo> {
  return call("DELETE", `/api/risk/block/${encodeURIComponent(user)}${instance ? `?instance=${encodeURIComponent(instance)}` : ""}`);
}

// ---- UEBA 行为基线 ----

export interface UebaProfileVo {
  user: string;
  events: number;
  ratePerHour: number;
  histogram: number[];
  maxHourCount: number;
  rowCountSamples: number;
  medianRowCount: number | null;
  currentWindowCount: number;
  bursting: boolean;
}

export function fetchUebaProfiles(limit = 10): Promise<{ generatedAt: number; profiles: UebaProfileVo[] }> {
  return call("GET", `/api/risk/ueba/profiles?limit=${limit}`);
}
