import { call } from "@/api/http";

/** 分类分级（数据资产）：元数据层的列级分类/分级能力。 */

export interface ClassificationRow {
  instance: string;
  columnKey: string;
  category: string;
  level: string;
  source: string;
  note: string | null;
  updatedAt: string | null;
}

export interface AutoResult {
  columnsConsidered: number;
  created: number;
  alreadyClassified: number;
}

export interface InstanceStat {
  instance: string;
  columns: number;
  classified: number;
  high: number;
  medium: number;
  low: number;
}

export interface ClassificationOverview {
  totalClassified: number;
  high: number;
  medium: number;
  low: number;
  instances: InstanceStat[];
}

export const CATEGORIES = ["IDENTITY", "PII", "CONTACT", "FINANCE", "LOCATION", "MEDICAL", "OTHER"] as const;
export const LEVELS = ["HIGH", "MEDIUM", "LOW"] as const;

export function classificationOverview(): Promise<ClassificationOverview> {
  return call("GET", "/api/classification/overview");
}

export function listClassification(instance: string): Promise<ClassificationRow[]> {
  return call("GET", `/api/classification/instances/${encodeURIComponent(instance)}`);
}

export function upsertClassification(
  instance: string,
  body: { columnKey: string; category: string; level: string; note?: string }
): Promise<ClassificationRow> {
  return call("PUT", `/api/classification/instances/${encodeURIComponent(instance)}`, body);
}

export function deleteClassification(instance: string, columnKey: string): Promise<void> {
  return call(
    "DELETE",
    `/api/classification/instances/${encodeURIComponent(instance)}?column=${encodeURIComponent(columnKey)}`
  );
}

export function autoClassify(instance: string): Promise<AutoResult> {
  return call("POST", `/api/classification/instances/${encodeURIComponent(instance)}/auto`);
}
