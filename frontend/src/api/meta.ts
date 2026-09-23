import { call } from "@/api/http";

/**
 * 元数据服务(mask-metadata, 8082)管理面。经网关前缀 `/api/meta/` 反代
 * (nginx/vite 将 `/api/meta/**` 重写为 8082 的 `/api/**`,解决与策略服务
 * `/api/instances` 的路径冲突),后端契约保持原样。
 */

export interface MetaConnection {
  host: string;
  port: number | null;
  database: string;
  dbUser: string;
  passwordRef: string;
  sslmode?: string | null;
  connectTimeoutSeconds?: number | null;
  schemas?: string[] | null;
  includeViews?: boolean;
}

export interface MetaInstanceSummary {
  name: string;
  dialect: string;
  engine: string;
  metadataVersion: number;
}

export interface MetaTableStructure {
  catalog: string;
  schema: string;
  name: string;
  columns: Array<{ name: string; type: string }>;
}

export interface MetaInstanceDetail {
  name: string;
  dialect: string;
  engine: string;
  metadataVersion: number;
  connection: MetaConnection | null;
  tables: MetaTableStructure[];
}

export interface MetaCreateBody {
  name: string;
  dialect: string;
  engine?: string | null;
  connection?: MetaConnection | null;
}

export interface MetaImportBody {
  name: string;
  dialect: string;
  connection?: MetaConnection | null;
  metadataYaml: string;
}

export interface MetaImportResponse {
  name: string;
  tableCount: number;
  columnCount: number;
  metadataVersion: number;
}

export interface MetaCollectResponse {
  tableCount: number;
  columnCount: number;
  warnings: string[];
  metadataVersion: number;
}

const base = "/api/meta/instances";

export function listMetaInstances(): Promise<MetaInstanceSummary[]> {
  return call("GET", base);
}

export function getMetaInstance(name: string): Promise<MetaInstanceDetail> {
  return call("GET", base + "/" + encodeURIComponent(name));
}

export function createMetaInstance(body: MetaCreateBody): Promise<MetaInstanceDetail> {
  return call("POST", base, body);
}

export function updateMetaConnection(name: string, connection: MetaConnection | null): Promise<MetaInstanceDetail> {
  return call("PUT", base + "/" + encodeURIComponent(name), { connection });
}

export function deleteMetaInstance(name: string): Promise<MetaInstanceSummary> {
  return call("DELETE", base + "/" + encodeURIComponent(name));
}

export function importMetaYaml(body: MetaImportBody): Promise<MetaImportResponse> {
  return call("POST", base + "/import", body);
}

export function collectMetaInstance(name: string): Promise<MetaCollectResponse> {
  return call("POST", base + "/" + encodeURIComponent(name) + "/collect");
}
