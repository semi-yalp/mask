import { call } from "@/api/http";
import type { Udf } from "@/types/domain";

const base = (instance: string) => "/api/instances/" + encodeURIComponent(instance) + "/udfs";

export function listUdfs(instance: string): Promise<Udf[]> {
  return call("GET", base(instance));
}

export function createUdf(instance: string, udf: Udf): Promise<void> {
  return call("POST", base(instance), udf);
}

export function updateUdf(instance: string, udfName: string, udf: Udf): Promise<void> {
  return call("PUT", base(instance) + "/" + encodeURIComponent(udfName), udf);
}

export function deleteUdf(instance: string, udfName: string): Promise<void> {
  return call("DELETE", base(instance) + "/" + encodeURIComponent(udfName));
}
