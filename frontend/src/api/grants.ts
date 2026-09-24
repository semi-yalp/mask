import { call } from "@/api/http";

/** 元数据层统一授权：主体 × 资源 × 权限 → 各引擎 GRANT DDL 预览/应用。 */

export type PrincipalType = "USER" | "GROUP";
export type ResourceType = "CATALOG" | "SCHEMA" | "TABLE" | "COLUMN";
export type Privilege = "SELECT" | "INSERT" | "UPDATE" | "DELETE" | "ALL";

export interface GrantEntry {
  id: number;
  instance: string;
  principalType: PrincipalType;
  principal: string;
  resourceType: ResourceType;
  resourceId: string;
  privilege: Privilege;
  grantedBy: string | null;
  createdAt: string | null;
}

export interface CompiledStatement {
  principal: string;
  sql: string;
}

export interface MatrixRow {
  principal: string;
  principalType: string;
  grants: Array<{ resourceType: string; resourceId: string; privilege: string }>;
}

export function createGrant(
  instance: string,
  body: {
    principalType: PrincipalType;
    principal: string;
    resourceType: ResourceType;
    resourceId: string;
    privilege: Privilege;
  }
): Promise<GrantEntry> {
  return call("POST", `/api/instances/${encodeURIComponent(instance)}/grants`, body);
}

export function listGrants(
  instance: string,
  principalType: PrincipalType,
  principal: string
): Promise<GrantEntry[]> {
  return call(
    "GET",
    `/api/instances/${encodeURIComponent(instance)}/grants?principalType=${principalType}`
      + `&principal=${encodeURIComponent(principal)}`
  );
}

export function listAllGrants(instance: string): Promise<GrantEntry[]> {
  return call("GET", `/api/instances/${encodeURIComponent(instance)}/grants/all`);
}

export function deleteGrant(instance: string, id: number): Promise<{ deleted: boolean }> {
  return call("DELETE", `/api/instances/${encodeURIComponent(instance)}/grants/${id}`);
}

export function previewGrants(
  instance: string,
  principalType: PrincipalType,
  principal: string
): Promise<{ instance: string; statements: CompiledStatement[] }> {
  return call(
    "GET",
    `/api/instances/${encodeURIComponent(instance)}/grants/preview?principalType=${principalType}`
      + `&principal=${encodeURIComponent(principal)}`
  );
}

export function applyGrants(
  instance: string,
  principalType: PrincipalType,
  principal: string
): Promise<{ instance: string; executed: string[]; failed: string[] }> {
  return call(
    "POST",
    `/api/instances/${encodeURIComponent(instance)}/grants/apply?principalType=${principalType}`
      + `&principal=${encodeURIComponent(principal)}`
  );
}

export function grantsMatrix(instance: string): Promise<MatrixRow[]> {
  return call("GET", `/api/grants/matrix?instance=${encodeURIComponent(instance)}`);
}
