/** 风险页面共享展示工具:级别配色、时间格式化、SQL 截断。 */

export const SEVERITY_ORDER = ["critical", "high", "medium", "low", "info"] as const;

export function severityColor(sev: string): string {
  switch (sev) {
    case "critical": return "#dc3545";
    case "high": return "#e8590c";
    case "medium": return "#f0b849";
    case "low": return "#35a7d3";
    default: return "#868e96";
  }
}

export function severityLabel(sev: string): string {
  switch (sev) {
    case "critical": return "危急";
    case "high": return "高危";
    case "medium": return "中危";
    case "low": return "低危";
    default: return "信息";
  }
}

export function severityElType(sev: string): "danger" | "warning" | "info" | "primary" {
  switch (sev) {
    case "critical": return "danger";
    case "high": return "danger";
    case "medium": return "warning";
    case "low": return "primary";
    default: return "info";
  }
}

export function scoreColor(score: number): string {
  if (score >= 60) return severityColor("critical");
  if (score >= 40) return severityColor("high");
  if (score >= 20) return severityColor("medium");
  if (score > 0) return severityColor("low");
  return severityColor("info");
}

export function formatTime(ts: number | string | undefined | null): string {
  if (!ts) return "-";
  const d = typeof ts === "number" ? new Date(ts) : new Date(ts);
  if (Number.isNaN(d.getTime())) return "-";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function truncateSql(sql: string | undefined | null, max = 90): string {
  if (!sql) return "";
  const one = sql.replace(/\s+/g, " ").trim();
  return one.length <= max ? one : one.slice(0, max) + "…";
}

export function statusLabel(status: string): string {
  switch (status) {
    case "OPEN": return "未处理";
    case "ACKNOWLEDGED": return "已确认";
    case "RESOLVED": return "已解决";
    default: return status;
  }
}

export function statusElType(status: string): "danger" | "warning" | "success" | "info" {
  switch (status) {
    case "OPEN": return "danger";
    case "ACKNOWLEDGED": return "warning";
    case "RESOLVED": return "success";
    default: return "info";
  }
}
