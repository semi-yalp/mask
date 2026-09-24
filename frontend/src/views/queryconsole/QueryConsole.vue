<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">数据面查询 · Query Console</span>
      <span class="muted">受控执行:改写不可绕过,只返回脱敏后结果集(mask-query, 8083)</span>
    </div>

    <div class="page query-console">
      <el-row :gutter="14">
        <el-col :span="10">
          <el-card shadow="never" class="card-block">
            <template #header><span>查询请求</span></template>
            <el-form label-width="130px" label-position="left" @submit.prevent>
              <el-form-item label="目标实例">
                <el-select v-model="instance" filterable placeholder="选择实例" style="width: 100%" :loading="loadingInstances">
                  <el-option v-for="i in instances" :key="i.name" :value="i.name"
                    :label="`${i.name}(${i.engine || i.dialect})`" :disabled="!i.connection">
                    <span>{{ i.name }}({{ i.engine || i.dialect }})</span>
                    <el-tag v-if="!i.connection" size="small" type="info" class="opt-tag">无连接,不可执行</el-tag>
                  </el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="查询主体">
                <div class="subject-row">
                  <el-input v-model="user" placeholder="user(可选)" />
                  <el-input v-model="groupsText" placeholder="groups,逗号分隔" />
                </div>
              </el-form-item>
              <el-form-item label="maxRows">
                <el-input-number v-model="maxRows" :min="1" :max="10000" controls-position="right" style="width: 150px" />
                <span class="muted form-hint">硬上限 10000,超出由服务端钳制</span>
              </el-form-item>
              <el-form-item label="回显改写 SQL">
                <el-switch v-model="includeRewrittenSql" />
              </el-form-item>
            </el-form>

            <div class="yaml-label">SQL(单条 SELECT)</div>
            <SqlEditor v-model="sql" />
            <div class="run-row">
              <el-button type="success" class="run-btn" :loading="running" :disabled="!instance" @click="run">执行查询</el-button>
              <span v-if="!instance" class="muted">先选择实例</span>
              <span v-else class="muted">服务端:拉取连接 → 按实例改写 → 执行 → 只回脱敏结果</span>
            </div>

            <template v-if="history.length">
              <div class="yaml-label">最近执行(本地)</div>
              <div v-for="(h, idx) in history" :key="idx" class="history-item" @click="restore(h)">
                <span class="mono history-sql">{{ h.sql.slice(0, 72) }}{{ h.sql.length > 72 ? "…" : "" }}</span>
                <span class="muted">{{ h.instance }} · {{ formatTime(h.at) }} · {{ h.rowCount ?? "失败" }} 行</span>
              </div>
            </template>
          </el-card>
        </el-col>

        <el-col :span="14">
          <ErrorAlert :error="error" />
          <el-card v-if="result" shadow="never" class="card-block">
            <template #header>
              <div class="head-row">
                <span>结果集</span>
                <span class="spacer" />
                <el-tag size="small" :type="result.masked ? 'success' : 'info'">{{ result.masked ? "已脱敏" : "未命中脱敏策略" }}</el-tag>
                <el-tag v-if="result.rowFiltered" size="small" type="warning">行过滤</el-tag>
                <el-tag v-if="result.truncated" size="small" type="danger">已截断(行数上限)</el-tag>
              </div>
            </template>
            <div class="meta-row muted">
              {{ result.instance }} · {{ result.engine }} · {{ result.rowCount }} 行 · {{ result.columns.length }} 列 · 耗时 {{ formatDuration(result.elapsedMs) }}
            </div>
            <el-table :data="rowObjects" stripe size="small" border class="result-table" :max-height="480">
              <el-table-column v-for="(c, i) in result.columns" :key="c.name + i" :prop="c.name" :label="`${c.name} (${c.type})`" min-width="130" show-overflow-tooltip>
                <template #default="{ row }">{{ cellText(row[c.name]) }}</template>
              </el-table-column>
            </el-table>
            <template v-if="includeRewrittenSql && result.rewrittenSql">
              <div class="yaml-label">实际执行的改写产物</div>
              <CodeBlock :code="result.rewrittenSql" :copyable="true" />
            </template>
          </el-card>
          <el-card v-else-if="!error" shadow="never">
            <el-empty description="提交查询后在此查看脱敏后的结果集" :image-size="72" />
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { buildQueryBody, runQuery, type QueryResult } from "@/api/queryconsole";
import { listMetaInstances, type MetaInstanceSummary } from "@/api/meta";
import { cellText, formatDuration, formatTime } from "@/utils/format";
import CodeBlock from "@/components/CodeBlock.vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import SqlEditor from "@/components/SqlEditor.vue";

const HISTORY_KEY = "mask-query-console-history";
const HISTORY_LIMIT = 10;

interface HistoryItem { instance: string; sql: string; user: string; groupsText: string; maxRows: number; at: number; rowCount: number | null }

const instances = ref<(MetaInstanceSummary & { connection?: unknown })[]>([]);
const loadingInstances = ref(false);
const instance = ref("");
const user = ref("");
const groupsText = ref("");
const maxRows = ref(1000);
const includeRewrittenSql = ref(false);
const sql = ref("");
const running = ref(false);
const error = ref("");
const result = ref<QueryResult | null>(null);
const history = ref<HistoryItem[]>(readHistory());

const rowObjects = computed(() => {
  const r = result.value;
  if (!r) return [];
  return r.rows.map((row) => {
    const obj: Record<string, unknown> = {};
    r.columns.forEach((c, i) => { obj[c.name] = row[i]; });
    return obj;
  });
});

function readHistory(): HistoryItem[] {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]"); } catch { return []; }
}
function pushHistory(item: HistoryItem) {
  history.value = [item, ...history.value.filter((h) => !(h.instance === item.instance && h.sql === item.sql))].slice(0, HISTORY_LIMIT);
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value));
}

onMounted(async () => {
  loadingInstances.value = true;
  try {
    const list = await listMetaInstances();
    instances.value = list as (MetaInstanceSummary & { connection?: unknown })[];
  } catch (e) {
    error.value = "实例列表加载失败(元数据服务):" + (e as Error).message;
  } finally { loadingInstances.value = false; }
});

function restore(h: HistoryItem) {
  instance.value = h.instance;
  sql.value = h.sql;
  user.value = h.user;
  groupsText.value = h.groupsText;
  maxRows.value = h.maxRows;
}

async function run() {
  error.value = "";
  result.value = null;
  if (!instance.value) { error.value = "请先选择目标实例"; return; }
  if (!sql.value.trim()) { error.value = "SQL 不能为空"; return; }
  running.value = true;
  const at = Date.now();
  try {
    const body = buildQueryBody({
      instance: instance.value, sql: sql.value, user: user.value,
      groups: groupsText.value.split(",").map((s) => s.trim()).filter(Boolean),
      maxRows: maxRows.value, includeRewrittenSql: includeRewrittenSql.value
    });
    result.value = await runQuery(body);
    pushHistory({ instance: body.instance, sql: body.sql, user: body.user || "", groupsText: body.groups?.join(",") || "", maxRows: maxRows.value, at, rowCount: result.value.rowCount });
  } catch (e) {
    error.value = (e as Error).message;
    pushHistory({ instance: instance.value, sql: sql.value, user: user.value, groupsText: groupsText.value, maxRows: maxRows.value, at, rowCount: null });
  } finally { running.value = false; }
}
</script>

<style scoped lang="scss">
.run-btn {
  background: var(--sm-success); border-color: var(--sm-success); color: #fff; font-weight: 600;
  &:hover, &:focus { background: var(--sm-success-dark); border-color: var(--sm-success-dark); color: #fff; }
}
.head-row { display: flex; align-items: center; gap: 8px; .spacer { flex: 1; } }
.meta-row { font-size: 12.5px; margin-bottom: 8px; }
.subject-row { display: flex; gap: 8px; width: 100%; }
.form-hint { margin-left: 10px; font-size: 12px; }
.yaml-label { font-size: 12.5px; font-weight: 600; margin: 10px 0 6px; }
.run-row { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.result-table { width: 100%; }
.history-item {
  display: flex; flex-direction: column; gap: 2px; padding: 6px 10px; border-radius: 6px; cursor: pointer;
  &:hover { background: #f0f7fa; }
  .history-sql { font-size: 12px; color: var(--sm-primary-dark); }
  .muted { font-size: 11.5px; }
}
.opt-tag { margin-left: 8px; }
</style>
