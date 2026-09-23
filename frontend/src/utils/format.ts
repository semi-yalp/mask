/** 展示格式化工具(时间/时长/字节数),替代各视图里的朴素字符串替换。 */

const timeFormatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
});

/** ISO 时间串或 Date → 本地时间;非法输入原样返回。 */
export function formatTime(value: string | number | Date | undefined | null): string {
  if (value === undefined || value === null || value === "") return "—";
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return timeFormatter.format(d);
}

/** 毫秒时长的可读形态(<1s 显示 ms,否则保留 1 位小数秒)。 */
export function formatDuration(ms: number | undefined | null): string {
  if (ms === undefined || ms === null) return "—";
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

/** 表格单元格渲染:对象/数组 JSON 化,null 显示 —,长文本截断由列配置处理。 */
export function cellText(v: unknown): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}
