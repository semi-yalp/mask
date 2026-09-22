import { call } from "@/api/http";
import type { InstanceInfo, TableDef } from "@/types/domain";

export function listInstances(): Promise<InstanceInfo[]> {
  return call("GET", "/api/instances");
}

export function getInstance(name: string): Promise<InstanceInfo> {
  return call("GET", "/api/instances/" + encodeURIComponent(name));
}

export function createInstance(name: string, dialect: string, tables: TableDef[] = []): Promise<InstanceInfo> {
  return call("POST", "/api/instances", { name, dialect, tables });
}

export function deleteInstance(name: string): Promise<void> {
  return call("DELETE", "/api/instances/" + encodeURIComponent(name));
}

export function putTables(name: string, tables: TableDef[]): Promise<void> {
  return call("PUT", "/api/instances/" + encodeURIComponent(name) + "/tables", { tables });
}

export interface MetadataImportBody {
  metadataBaseUrl: string;
  metadataInstance: string;
  metadataApiKey: string | null;
}

export function importMetadata(name: string, body: MetadataImportBody): Promise<{ tables: unknown[] }> {
  return call("POST", "/api/instances/" + encodeURIComponent(name) + "/import-metadata", body);
}
