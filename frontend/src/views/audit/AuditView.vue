<template>
  <div class="page">
    <div class="page-head">
      <h2>审计日志</h2>
      <span class="muted">审计事件经 Elasticsearch 检索,时间范围不超过 7 天</span>
    </div>

    <el-card shadow="never" class="card-block">
      <div class="filter-row">
        <el-select v-model="filters.eventType" placeholder="eventType" clearable style="width: 150px">
          <el-option v-for="t in eventTypes" :key="t" :value="t" :label="t" />
        </el-select>
        <el-select v-model="filters.outcome" placeholder="outcome" clearable style="width: 130px">
          <el-option value="SUCCESS" label="SUCCESS" />
          <el-option value="FAILURE" label="FAILURE" />
        </el-select>
        <el-input v-model="filters.instance" placeholder="instance" clearable style="width: 130px" />
        <el-input v-model="filters.resourceType" placeholder="resourceType" clearable style="width: 140px" />
        <el-input v-model="filters.action" placeholder="action" clearable style="width: 130px" />
        <el-input v-model="filters.user" placeholder="user" clearable style="width: 130px" />
        <el-date-picker v-model="range" type="datetimerange" start-placeholder="开始" end-placeholder="结束"
          value-format="x" style="width: 360px" />
        <el-button type="primary" :loading="loading" @click="search(0)">查询</el-button>
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
      <el-card shadow="never">
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
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
          </el-table-column>
          <el-table-column prop="eventType" label="类型" width="140" />
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
        </el-table>
        <div class="pager">
          <el-pagination layout="prev, pager, next, sizes, total" :total="total" :current-page="page + 1"
            :page-size="size" :page-sizes="[20, 50, 100, 200]" @current-change="(p: number) => search(p - 1)"
            @size-change="(s: number) => { size = s; search(0); }" />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { searchAudit } from "@/api/audit";
import type { AuditEvent } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";
import ErrorAlert from "@/components/ErrorAlert.vue";

const eventTypes = ["REWRITE", "ADMIN_CHANGE", "EFFECTIVE_PULL", "QUERY"];
const DEFAULT_SIZE = 50;

const filters = ref({ eventType: "", outcome: "", instance: "", resourceType: "", action: "", user: "" });
const range = ref<[string, string] | null>(null);
const loading = ref(false);
const error = ref("");
const auditUnavailable = ref(false);
const events = ref<AuditEvent[]>([]);
const total = ref(0);
const page = ref(0);
const size = ref(DEFAULT_SIZE);

onMounted(() => search(0));

async function search(p: number) {
  error.value = "";
  auditUnavailable.value = false;
  loading.value = true;
  try {
    const res = await searchAudit({
      eventType: filters.value.eventType || undefined,
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
.page-head {
  display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px;
  h2 { margin: 0; font-size: 18px; }
}
.filter-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.expand-body {
  padding: 4px 12px 8px; font-size: 12.5px;
  b { display: inline-block; margin: 6px 0 4px; }
}
.unav-text { margin: 4px 0; code { font-family: Consolas, monospace; } }
</style>
