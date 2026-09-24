<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">风险事件流 · Risk Events</span>
      <span class="muted">全部改写/执行事件及其规则命中明细(事件即证据)</span>
      <div class="topnav-actions">
        <el-select v-model="minutes" size="small" style="width: 110px" @change="resetAndLoad">
          <el-option :value="60" label="近 1 小时" />
          <el-option :value="360" label="近 6 小时" />
          <el-option :value="1440" label="近 24 小时" />
          <el-option :value="2880" label="近 48 小时" />
        </el-select>
        <el-button size="small" type="primary" plain @click="load">
          <el-icon><Refresh /></el-icon>&nbsp;刷新
        </el-button>
      </div>
    </div>

    <div class="page">
      <ErrorAlert :error="error" />

      <el-card shadow="never" class="card-block">
        <div class="filters">
          <el-select v-model="severityFilter" placeholder="级别" clearable size="small" style="width: 110px" @change="resetAndLoad">
            <el-option v-for="s in ['critical', 'high', 'medium', 'low', 'info']" :key="s" :value="s" :label="severityLabel(s)" />
          </el-select>
          <el-select v-model="typeFilter" placeholder="事件类型" clearable size="small" style="width: 140px" @change="resetAndLoad">
            <el-option value="REWRITE" label="REWRITE(改写)" />
            <el-option value="QUERY" label="QUERY(执行)" />
            <el-option value="ADMIN_CHANGE" label="ADMIN_CHANGE(管理)" />
          </el-select>
          <el-select v-model="outcomeFilter" placeholder="结果" clearable size="small" style="width: 110px" @change="resetAndLoad">
            <el-option value="SUCCESS" label="成功" />
            <el-option value="FAILURE" label="失败" />
          </el-select>
          <el-input v-model="userFilter" placeholder="用户" clearable size="small" style="width: 140px" @keyup.enter="resetAndLoad" @clear="resetAndLoad" />
          <el-input v-model="keyword" placeholder="搜索 SQL / IP / 规则" clearable size="small" style="width: 220px"
            @keyup.enter="resetAndLoad" @clear="resetAndLoad" />
          <span class="muted" style="margin-left:auto">共 {{ total }} 条</span>
        </div>

        <el-table :data="events" v-loading="loading" size="default" row-class-name="ev-table-row">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
          </el-table-column>
          <el-table-column label="用户" width="130">
            <template #default="{ row }"><span class="mono">{{ row.user || "-" }}</span></template>
          </el-table-column>
          <el-table-column label="IP" width="115">
            <template #default="{ row }"><span class="mono">{{ row.sourceIp || "-" }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="95">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ shortType(row.eventType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="76">
            <template #default="{ row }">
              <el-tag size="small" :type="row.outcome === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
                {{ row.outcome === "SUCCESS" ? "成功" : "失败" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="脱敏" width="60" align="center">
            <template #default="{ row }">
              <el-icon v-if="row.masked" color="#28a745"><CircleCheck /></el-icon>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="风险分" width="130">
            <template #default="{ row }">
              <div class="score-cell">
                <b :style="{ color: scoreColor(row.riskScore) }" class="mono">{{ row.riskScore }}</b>
                <div class="score-bar">
                  <div class="score-fill" :style="{ width: row.riskScore + '%', background: scoreColor(row.riskScore) }" />
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="命中规则" min-width="220">
            <template #default="{ row }">
              <span v-if="!row.hits?.length" class="muted">—</span>
              <el-tooltip v-for="h in row.hits.slice(0, 3)" :key="h.ruleId" :content="h.evidence" placement="top">
                <el-tag size="small" :color="severityColor(h.severity)" style="color:#fff;border:none;margin-right:4px">
                  {{ h.ruleName }}
                </el-tag>
              </el-tooltip>
              <el-tag v-if="row.hits.length > 3" size="small" type="info" effect="plain">+{{ row.hits.length - 3 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="SQL" min-width="280">
            <template #default="{ row }">
              <span class="mono sql-cell" @click="openDetail(row)">{{ truncateSql(row.sql) || "(失败,无 SQL)" }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
            layout="prev, pager, next" @current-change="load" />
        </div>
      </el-card>

      <el-drawer v-model="drawer" :title="detail ? `事件 ${detail.id}` : '事件详情'" size="620px">
        <template v-if="detail">
          <div class="d-section">
            <div class="d-kv"><span class="d-k">时间</span>{{ formatTime(detail.timestamp) }}
              <span class="d-k" style="margin-left:16px">服务</span>{{ detail.service || "-" }}</div>
            <div class="d-kv"><span class="d-k">用户</span><span class="mono">{{ detail.user || "-" }}</span>
              <span class="d-k" style="margin-left:16px">IP</span><span class="mono">{{ detail.sourceIp || "-" }}</span></div>
            <div class="d-kv"><span class="d-k">实例</span><span class="mono">{{ detail.instance || "-" }}</span>
              <span class="d-k" style="margin-left:16px">方言</span>{{ detail.dialect || "-" }}</div>
            <div class="d-kv"><span class="d-k">结果</span>
              <el-tag size="small" :type="detail.outcome === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
                {{ detail.outcome }}
              </el-tag>
              <template v-if="detail.errorCode">
                <span class="d-k" style="margin-left:16px">错误</span>
                <span class="mono">{{ detail.errorCode }}</span>
                <span class="muted">{{ detail.errorMessage }}</span>
              </template>
            </div>
            <div class="d-kv"><span class="d-k">脱敏</span>{{ detail.masked ? "是" : "否" }}
              <span class="d-k" style="margin-left:16px">行过滤</span>{{ detail.rowFiltered ? "是" : "否" }}
              <template v-if="detail.rowCount != null">
                <span class="d-k" style="margin-left:16px">返回行数</span><b>{{ detail.rowCount }}</b>
              </template>
            </div>
            <div class="d-kv" v-if="detail.tables?.length">
              <span class="d-k">涉及表</span>
              <el-tag v-for="t in detail.tables.slice(0, 6)" :key="t" size="small" effect="plain" class="mono" style="margin-right:4px">{{ t }}</el-tag>
            </div>
            <div class="d-kv" v-if="detail.sensitiveColumns?.length">
              <span class="d-k">敏感列</span>
              <el-tag v-for="c in detail.sensitiveColumns" :key="c" size="small" type="warning" effect="plain" class="mono" style="margin-right:4px">{{ c }}</el-tag>
            </div>
            <div class="d-kv"><span class="d-k">风险分</span>
              <b :style="{ color: scoreColor(detail.riskScore) }">{{ detail.riskScore }}</b>
              <el-tag size="small" :color="severityColor(detail.topSeverity)" style="color:#fff;border:none;margin-left:8px">
                {{ severityLabel(detail.topSeverity) }}
              </el-tag>
            </div>
          </div>

          <div class="d-section" v-if="detail.hits?.length">
            <div class="d-title">规则命中({{ detail.hits.length }})</div>
            <div v-for="h in detail.hits" :key="h.ruleId" class="hit-row">
              <el-tag size="small" :color="severityColor(h.severity)" style="color:#fff;border:none">{{ severityLabel(h.severity) }}</el-tag>
              <b style="margin:0 8px">{{ h.ruleName }}</b>
              <span class="muted">{{ h.evidence }}</span>
            </div>
          </div>

          <div class="d-section">
            <div class="d-title">原始 SQL</div>
            <pre class="sql-block">{{ detail.sql || "-" }}</pre>
          </div>
          <div class="d-section" v-if="detail.rewrittenSql">
            <div class="d-title">改写后 SQL</div>
            <pre class="sql-block">{{ detail.rewrittenSql }}</pre>
          </div>
        </template>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Refresh, CircleCheck } from "@element-plus/icons-vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import { fetchRiskEvents, type RiskEventVo } from "@/api/risk";
import { severityColor, severityLabel, scoreColor, formatTime, truncateSql } from "@/views/risk/risk-ui";

const events = ref<RiskEventVo[]>([]);
const detail = ref<RiskEventVo | null>(null);
const drawer = ref(false);
const loading = ref(false);
const error = ref("");
const total = ref(0);
const page = ref(0);
const pageSize = 50;
const minutes = ref(1440);
const severityFilter = ref("");
const typeFilter = ref("");
const outcomeFilter = ref("");
const userFilter = ref("");
const keyword = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await fetchRiskEvents({
      severity: severityFilter.value,
      eventType: typeFilter.value,
      outcome: outcomeFilter.value,
      user: userFilter.value,
      keyword: keyword.value,
      minutes: minutes.value
    }, page.value, pageSize);
    events.value = r.events;
    total.value = r.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function resetAndLoad() {
  page.value = 0;
  load();
}

function openDetail(row: RiskEventVo) {
  detail.value = row;
  drawer.value = true;
}

function shortType(t: string): string {
  return t.length > 12 ? t.slice(0, 10) + "…" : t;
}

onMounted(load);
</script>

<style scoped lang="scss">
.topnav-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12.5px; }

.score-cell { display: flex; align-items: center; gap: 8px; }
.score-cell .mono { width: 28px; }
.score-bar { flex: 1; height: 7px; background: #edf3f6; border-radius: 4px; overflow: hidden; }
.score-fill { height: 100%; border-radius: 4px; }
.sql-cell { cursor: pointer; color: var(--sm-primary-dark); &:hover { text-decoration: underline; } }

.d-section { margin-bottom: 18px; }
.d-kv { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; flex-wrap: wrap; }
.d-k { color: var(--sm-muted); }
.d-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.sql-block {
  background: var(--sm-code-bg); color: var(--sm-code-text);
  padding: 12px 14px; border-radius: 6px; font-size: 12px; line-height: 1.6;
  font-family: "JetBrains Mono", Consolas, monospace; white-space: pre-wrap; word-break: break-all;
  margin: 0;
}
.hit-row { padding: 8px 0; border-bottom: 1px solid #eef3f6; font-size: 13px; &:last-child { border-bottom: none; } }
</style>
