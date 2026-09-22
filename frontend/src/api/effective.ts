import { call } from "@/api/http";
import type { EffectiveConfig } from "@/types/domain";

export function getEffective(instance: string, subject: { user?: string; groups?: string[] }): Promise<EffectiveConfig> {
  const q = new URLSearchParams();
  if (subject.user) q.set("user", subject.user);
  (subject.groups || []).forEach((g) => q.append("groups", g));
  const suffix = q.toString() ? "?" + q.toString() : "";
  return call("GET", "/api/effective/" + encodeURIComponent(instance) + suffix, undefined, "data");
}
