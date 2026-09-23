import { call } from "@/api/http";

/**
 * 统一查询数据面(mask-query, 8083)。请求经网关直通 `/api/v1/**`;
 * 鉴权使用独立的查询 Key(SQLMASK_QUERY_API_KEY,设置页第三把 Key)。
 */

export interface QueryRequestBody {
  instance: string;
  sql: string;
  user?: string;
  groups?: string[];
  maxRows?: number;
  includeRewrittenSql?: boolean;
}

export interface QueryResultColumn {
  name: string;
  type: string;
}

export interface QueryResult {
  instance: string;
  engine: string;
  columns: QueryResultColumn[];
  rows: unknown[][];
  rowCount: number;
  truncated: boolean;
  masked: boolean;
  rowFiltered: boolean;
  elapsedMs: number;
  rewrittenSql?: string;
}

/** mask-query 硬上限(超出由服务端钳制,前端仅在提示中说明)。 */
export const MAX_ROWS_HARD_LIMIT = 10000;

export function runQuery(body: QueryRequestBody): Promise<QueryResult> {
  return call("POST", "/api/v1/query", body, "query");
}

/** 构造请求体:trim 与空值裁剪(与 vitest 用例对齐)。 */
export function buildQueryBody(input: QueryRequestBody): QueryRequestBody {
  const groups = (input.groups || []).map((g) => g.trim()).filter(Boolean);
  const maxRows = input.maxRows === undefined || input.maxRows === null ? undefined : Math.max(1, Math.floor(input.maxRows));
  return {
    instance: input.instance.trim(),
    sql: input.sql,
    user: input.user?.trim() || undefined,
    groups: groups.length ? groups : undefined,
    maxRows,
    includeRewrittenSql: input.includeRewrittenSql || undefined
  };
}
