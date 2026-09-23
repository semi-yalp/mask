<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">审计 · Audits</span>
      <span class="muted">审计事件经 Elasticsearch 检索,时间范围不超过 7 天</span>
    </div>

    <div class="page">
      <div class="crumb"><a href="#/audit">审计</a><el-icon class="sep" v-if="activeTab"><ArrowRight /></el-icon><span v-if="activeTab">{{ tabLabel(activeTab) }}</span></div>

      <el-card shadow="never" class="card-block" :body-style="{ padding: '0' }">
        <el-tabs :model-value="activeTab" class="audit-tabs" @update:model-value="switchTab">
          <el-tab-pane label="全部事件" name="" />
          <el-tab-pane label="访问审计(REWRITE)" name="REWRITE" />
          <el-tab-pane label="查询执行(QUERY)" name="QUERY" />
          <el-tab-pane label="管理审计(ADMIN_CHANGE)" name="ADMIN_CHANGE" />
          <el-tab-pane label="生效拉取(EFFECTIVE_PULL)" name="EFFECTIVE_PULL" />
        </el-tabs>

        <div class="filter-bar">
          <el-select v-model="preset" placeholder="时间范围" style="width: 130px" @change="applyPreset">
            <el-option value="1h" label="近 1 小时" />
            <el-option value="24h" label="近 24 小时" />
            <el-option value="7d" label="近 7 天" />
            <el-option value="custom" label="自定义" />
          </el-select>
          <el-date-picker v-if="preset === 'custom'" v-model="range" type="datetimerange"
            start-placeholder="开始" end-placeholder="结束" value-format="x" style="width: 340px" />
          <el-select v-model="filters.outcome" placeholder="结果" clearable style="width: 120px">
            <el-option value="SUCCESS" label="SUCCESS" />
            <el-option value="FAILURE" label="FAILURE" />
          </el-select>
          <el-input v-model="filters.instance" placeholder="instance" clearable style="width: 120px" />
          <el-input v-model="filters.resourceType" placeholder="resourceType" clearable style="width: 130px" />
          <el-input v-model="filters.action" placeholder="action" clearable style="width: 120px" />
          <el-input v-model="filters.user" placeholder="user" clearable style="width: 120px" />
          <el-button type="primary" :loading="loading" :prefix-icon="Search" @click="search(0)">查询</el-button>
        </div>
      </el-card>

      <el-card v-if="auditUnavailable" shadow="never" class="card-block">
        <el-empty description="审计未启用" :image-size="80">
          <template #description>
            <p class="unav-text">审计查询依赖 Elasticsearch,且服务端 <code>audit.enabled=true</code>。</p>
            <p class="unav-text muted">启用后重启 mask-core,审计事件写入 ES 后即可在此检索。</p>
          </template>
        </el-empty>
      </el-card>

      <template v-else>
        <ErrorAlert :error="error" />
        <el-card shadow="never" v-loading="loading">
          <el-table :data="events" size="small" stripe>
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand-body">
                  <div v-if="row.originalSql"><b>原始 SQL</b><CodeBlock :code="row.originalSql" :copyable="false" /></div>
                  <div v-if="row.rewrittenSql"><b>改写 SQL</b><CodeBlock :code="row.rewrittenSql" :copyable="false" /></div>
                  <div v-if="row.errorMessage"><b>错误</b>{{ row.errorCode }} {{ row.errorMessage }}</div>
                  <div v-if="row.detail"><b>detail</b><CodeBlock :code="JSON.stringify(row.detail, null, 2)" :copyable="false" /></div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="事件时间" width="175">
              <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="typeTag(row.eventType)" effect="plain">{{ row.eventType || "—" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.outcome === 'SUCCESS' ? 'success' : 'danger'">{{ row.outcome || "—" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="instance" label="实例" width="110" show-overflow-tooltip />
            <el-table-column prop="resourceType" label="资源" width="110" show-overflow-tooltip />
            <el-table-column prop="action" label="动作" width="110" show-overflow-tooltip />
            <el-table-column prop="actorUser" label="主体" width="110" show-overflow-tooltip />
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ row.durationMs != null ? row.durationMs + " ms" : "—" }}</template>
            </el-table-column>
            <el-table-column label="SQL" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="muted mono">{{ row.originalSql || row.rewrittenSql || "—" }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination layout="prev, pager, next, sizes, total" :total="total" :current-page="page + 1"
              :page-size="size" :page-sizes="[20, 50, 100, 200]" @current-change="(p: number) => search(p - 1)"
              @size-change="(s: number) => { size = s; search(0); }" />
          </div>
        </el-card>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, Search } from "@element-plus/icons-vue";
import { searchAudit } from "@/api/audit";
import type { AuditEvent } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";
import ErrorAlert from "@/components/ErrorAlert.vue";

const route = useRoute();
const router = useRouter();

const DEFAULT_SIZE = 50;
const HOUR_MS = 3_600_000;

const filters = ref({ outcome: "", instance: "", resourceType: "", action: "", user: "" });
const preset = ref("24h");
const range = ref<[string, string] | null>(null);
const loading = ref(false);
const error = ref("");
const auditUnavailable = ref(false);
const events = ref<AuditEvent[]>([]);
const total = ref(0);
const page = ref(0);
const size = ref(DEFAULT_SIZE);

const activeTab = ref(String(route.query.eventType || ""));

onMounted(() => { applyPreset(); search(0); });

watch(() => route.query.eventType, (v) => {
  const tab = String(v || "");
  if (tab !== activeTab.value) {
    activeTab.value = tab;
    search(0);
  }
});

function tabLabel(t: string): string {
  return ({ REWRITE: "访问审计", QUERY: "查询执行", ADMIN_CHANGE: "管理审计", EFFECTIVE_PULL: "生效拉取" } as Record<string, string>)[t] || t;
}

function typeTag(t?: string): string {
  return ({ REWRITE: "primary", QUERY: "success", ADMIN_CHANGE: "warning", EFFECTIVE_PULL: "info" } as Record<string, string>)[t || ""] || "info";
}

function switchTab(name: string | number) {
  router.push({ name: "audit", query: name ? { eventType: String(name) } : {} }).catch(() => undefined);
}

function applyPreset() {
  if (preset.value === "custom") return;
  const hours = preset.value === "1h" ? 1 : preset.value === "24h" ? 24 : 7 * 24;
  const to = Date.now();
  range.value = [String(to - hours * HOUR_MS), String(to)];
}

async function search(p: number) {
  error.value = "";
  auditUnavailable.value = false;
  loading.value = true;
  try {
    const res = await searchAudit({
      eventType: activeTab.value || undefined,
      outcome: filters.value.outcome || undefined,
      instance: filters.value.instance.trim() || undefined,
      resourceType: filters.value.resourceType.trim() || undefined,
      action: filters.value.action.trim() || undefined,
      user: filters.value.user.trim() || undefined,
      from: range.value?.[0] ? new Date(Number(range.value[0])).toISOString() : undefined,
      to: range.value?.[1] ? new Date(Number(range.value[1])).toISOString() : undefined
    }, p, size.value);
    events.value = res.events || [];
    total.value = res.total;
    page.value = res.page;
  } catch (e) {
    const msg = (e as Error).message;
    if (msg.includes("AUDIT_SEARCH_UNAVAILABLE")) {
      auditUnavailable.value = true;
      events.value = [];
      total.value = 0;
    } else {
      error.value = msg;
    }
  } finally { loading.value = false; }
}

function formatTime(iso?: string): string {
  return iso ? iso.replace("T", " ").replace("Z", " UTC") : "—";
}
</script>

<style scoped lang="scss">
.crumb .sep { font-size: 11px; color: var(--sm-muted); }
.audit-tabs {
  :deep(.el-tabs__header) { margin: 0; padding: 0 14px; }
  :deep(.el-tabs__nav-wrap::after) { height: 1px; }
}
.filter-bar {
  display: flex; gap: 8px; flex-wrap: wrap; align-items: center;
  padding: 12px 14px 14px;
}
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.expand-body {
  padding: 4px 12px 8px; font-size: 12.5px;
  b { display: inline-block; margin: 6px 0 4px; }
}
.unav-text { margin: 4px 0; code { font-family: Consolas, monospace; } }
</style>
