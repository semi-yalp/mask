/** 后端 REST 领域类型(字段名与 mask-policy-server / mask-core 的 JSON 一致)。 */

export interface ColumnDef {
  name: string;
  type: string;
}

export interface TableDef {
  catalog: string;
  schema: string;
  name: string;
  rowFilter?: string;
  columns: ColumnDef[];
}

export interface InstanceInfo {
  name: string;
  dialect: string;
  tables: TableDef[];
}

export interface PolicyResource {
  catalog: string;
  schema: string;
  table: string;
  columns: string[];
}

export interface PolicySubjects {
  users: string[];
  groups: string[];
}

export interface Policy {
  name: string;
  policyType: "datamask" | "row_filter" | string;
  isEnabled: boolean;
  priority: number | null;
  resource: PolicyResource;
  subjects: PolicySubjects;
  udf: string | null;
  arguments: Array<number | string>;
  filterExpr: string | null;
}

export interface UdfSignature {
  params: string[];
  returns: string;
}

export interface Udf {
  name: string;
  signatures: UdfSignature[];
}

export interface EffectiveColumnBinding {
  catalog: string;
  schema: string;
  table: string;
  column: string;
  policy: string;
}

export interface EffectiveConfig {
  instance: string;
  dialect: string;
  configVersion: string;
  policySummary: { enabled: number; disabled: number };
  config: {
    metadata?: { tables?: TableDef[] };
    columns?: EffectiveColumnBinding[];
    policies?: Record<string, { udf?: string; arguments?: Array<number | string> }>;
  };
}

export interface StatementRewrite {
  ordinal: number;
  originalSql: string;
  rewrittenSql: string;
  masked: boolean;
  rowFiltered: boolean;
  kind?: string;
}

export interface RewriteResponse {
  statements: StatementRewrite[];
  rewrittenSql: string;
}

export interface AuditEvent {
  timestamp?: string;
  eventType?: string;
  service?: string;
  outcome?: string;
  durationMs?: number;
  sourceIp?: string;
  actorUser?: string;
  actorGroups?: string[];
  authKind?: string;
  errorCode?: string;
  errorMessage?: string;
  dialect?: string;
  statementCount?: number;
  masked?: boolean;
  rowFiltered?: boolean;
  originalSql?: string;
  rewrittenSql?: string;
  resourceType?: string;
  action?: string;
  instance?: string;
  resourceName?: string;
  detail?: Record<string, unknown>;
}

export interface AuditQueryResponse {
  total: number;
  page: number;
  size: number;
  events: AuditEvent[];
}
