<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">检测规则 · Detection Rules</span>
      <span class="muted">内置特征/行为规则可调参启停,自定义规则用条件构造器可视化编排</span>
      <div class="topnav-actions">
        <el-button size="small" type="primary" @click="openBuilder()">
          <el-icon><Plus /></el-icon>&nbsp;新建自定义规则
        </el-button>
        <el-button size="small" plain @click="load">
          <el-icon><Refresh /></el-icon>&nbsp;刷新
        </el-button>
      </div>
    </div>

    <div class="page">
      <ErrorAlert :error="error" />

      <el-row :gutter="14" class="card-block">
        <el-col :span="8">
          <el-card shadow="never" class="stat">
            <div class="stat-num">{{ builtinRules.length }}</div>
            <div class="stat-label">内置规则(启用 {{ enabledBuiltin }} 条)</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="stat">
            <div class="stat-num">{{ customRules.length }}</div>
            <div class="stat-label">自定义规则(启用 {{ enabledCustom }} 条)</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="stat">
            <div class="stat-num">{{ totalHits }}</div>
            <div class="stat-label">累计命中(滚动窗口内)</div>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never">
        <el-tabs v-model="tab">
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane :label="`内置 (${builtinRules.length})`" name="builtin" />
          <el-tab-pane :label="`自定义 (${customRules.length})`" name="custom" />
        </el-tabs>

        <el-table :data="filteredRules" v-loading="loading" size="default">
          <el-table-column label="规则" min-width="240">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="openDetail(row)">
                <b>{{ row.name }}</b>
              </el-link>
              <div class="muted mono rule-id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="86">
            <template #default="{ row }">
              <el-tag size="small" :type="row.kind === 'BUILT_IN' ? 'primary' : 'success'" effect="plain">
                {{ row.kind === "BUILT_IN" ? "内置" : "自定义" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类别" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ categoryLabel(row.category) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="80">
            <template #default="{ row }">
              <el-tag size="small" :color="severityColor(row.severity)" style="color:#fff;border:none">
                {{ severityLabel(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="76" align="center">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="(v: string | number | boolean) => toggle(row, !!v)" />
            </template>
          </el-table-column>
          <el-table-column label="命中" width="80" align="center">
            <template #default="{ row }"><b>{{ row.hitCount }}</b> 次</template>
          </el-table-column>
          <el-table-column label="配置摘要" min-width="220">
            <template #default="{ row }">
              <span v-if="row.kind === 'CUSTOM'" class="mono spec-cell">{{ specSummary(row) }}</span>
              <span v-else class="mono spec-cell">{{ paramsSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
              <el-button size="small" text type="primary" @click="testExisting(row)">测试</el-button>
              <el-button v-if="row.kind === 'CUSTOM'" size="small" text type="primary" @click="openBuilder(row)">编辑</el-button>
              <el-button v-if="row.kind === 'CUSTOM'" size="small" text type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 规则详情(内置可调参) -->
      <el-drawer v-model="detailDrawer" :title="detail?.name || '规则详情'" size="520px">
        <template v-if="detail">
          <div class="d-kv">
            <el-tag :color="severityColor(detail.severity)" style="color:#fff;border:none">{{ severityLabel(detail.severity) }}</el-tag>
            <el-tag size="small" effect="plain">{{ detail.kind === "BUILT_IN" ? "内置" : "自定义" }}</el-tag>
            <el-tag size="small" effect="plain">{{ categoryLabel(detail.category) }}</el-tag>
          </div>
          <p class="d-desc">{{ detail.description }}</p>

          <div v-if="detail.kind === 'CUSTOM'" class="d-section">
            <div class="d-title">条件(AND)</div>
            <div v-for="(c, i) in detail.spec?.conditions" :key="i" class="cond-row mono">
              {{ fieldLabel(c.field) }} {{ opLabel(c.op) }} {{ c.value }}
            </div>
            <div v-if="detail.spec?.window" class="cond-row">
              窗口阈值:{{ detail.spec.window.count }} 次 / {{ detail.spec.window.seconds }}s,按{{ detail.spec.window.groupBy === "ip" ? "IP" : "用户" }}分组
            </div>
          </div>

          <div class="d-section">
            <div class="d-title">参数</div>
            <div v-if="paramEntries(detail).length">
              <div v-for="[k, v] in paramEntries(detail)" :key="k" class="param-row">
                <span class="param-k mono">{{ k }}</span>
                <el-input-number v-if="editingParams" v-model="paramDraft[k]" size="small" style="width: 130px" />
                <b v-else class="mono">{{ v }}</b>
              </div>
            </div>
            <div v-else class="muted">该规则无可调参数</div>
          </div>

          <div class="d-section" v-if="detail.kind === 'BUILT_IN'">
            <div class="d-title">级别</div>
            <el-select v-if="editingParams" v-model="severityDraft" size="small" style="width: 160px">
              <el-option v-for="s in ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']" :key="s" :value="s" :label="s" />
            </el-select>
            <b v-else>{{ detail.severity.toUpperCase() }}</b>
          </div>

          <div class="d-actions">
            <template v-if="!editingParams">
              <el-button size="small" type="primary" plain @click="startEditParams">调参</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="editingParams = false">取消</el-button>
              <el-button size="small" type="primary" @click="saveParams">保存</el-button>
            </template>
          </div>
        </template>
      </el-drawer>

      <!-- 自定义规则构造器 -->
      <el-dialog v-model="builder" :title="editingRule ? '编辑自定义规则' : '新建自定义规则'" width="720px" top="6vh">
        <el-form label-width="92px" label-position="left">
          <el-form-item label="名称" required>
            <el-input v-model="draft.name" placeholder="例如:外部 IP 访问客户表" maxlength="60" />
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="draft.description" placeholder="规则的业务含义(可选)" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="级别">
                <el-select v-model="draft.severity" style="width: 100%">
                  <el-option v-for="s in ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']" :key="s" :value="s" :label="s" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="类别">
                <el-select v-model="draft.category" style="width: 100%">
                  <el-option v-for="c in ['CUSTOM', 'SQLI', 'BEHAVIOR', 'COMPLIANCE', 'DATA_EXPOSURE']" :key="c" :value="c" :label="categoryLabel(c)" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="启用">
                <el-switch v-model="draft.enabled" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="触发条件" required>
            <div class="cond-builder">
              <div v-for="(c, i) in draft.conditions" :key="i" class="cond-edit-row">
                <el-select v-model="c.field" size="small" style="width: 130px" placeholder="字段">
                  <el-option v-for="f in fields" :key="f" :value="f" :label="fieldLabel(f)" />
                </el-select>
                <el-select v-model="c.op" size="small" style="width: 118px" placeholder="运算">
                  <el-option v-for="o in ops" :key="o" :value="o" :label="opLabel(o)" />
                </el-select>
                <el-input v-model="c.value" size="small" style="flex: 1" placeholder="值(in/正则 用原样字符串)" />
                <el-button size="small" text type="danger" :disabled="draft.conditions.length <= 1" @click="draft.conditions.splice(i, 1)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
              <el-button size="small" text type="primary" @click="draft.conditions.push({ field: 'sql', op: 'contains', value: '' })">
                <el-icon><Plus /></el-icon>&nbsp;添加条件
              </el-button>
              <span class="muted hint">多条件之间为 AND;字段覆盖用户/IP/SQL/结果/行数/时段/表/列等。</span>
            </div>
          </el-form-item>

          <el-form-item label="窗口阈值">
            <el-switch v-model="windowEnabled" active-text="启用(滑动窗口内 N 次才触发)" />
            <div v-if="windowEnabled" class="window-row">
              <el-input-number v-model="draft.window!.count" :min="1" size="small" controls-position="right" />
              <span class="muted">次 /</span>
              <el-input-number v-model="draft.window!.seconds" :min="1" size="small" controls-position="right" />
              <span class="muted">秒内,按</span>
              <el-select v-model="draft.window!.groupBy" size="small" style="width: 90px">
                <el-option value="user" label="用户" />
                <el-option value="ip" label="IP" />
              </el-select>
              <span class="muted">分组</span>
            </div>
          </el-form-item>
        </el-form>

        <div v-if="testResult" class="test-panel">
          <b>试运行结果:</b> 扫描最近 {{ testResult.scanned }} 条事件,命中 {{ testResult.matched }} 条
          <template v-if="testResult.samples.length">
            <div v-for="s in testResult.samples" :key="s.eventId" class="test-sample">
              <el-tag size="small" type="warning" effect="plain">{{ formatTime(s.timestamp) }}</el-tag>
              <span class="mono">{{ s.user }}</span>
              <span class="muted mono sql">{{ truncateSql(s.sql, 60) }}</span>
            </div>
          </template>
        </div>

        <template #footer>
          <el-button size="default" @click="runTest" :loading="testing">试运行(dry-run)</el-button>
          <el-button size="default" @click="builder = false">取消</el-button>
          <el-button size="default" type="primary" @click="save" :loading="saving">{{ editingRule ? "保存" : "创建" }}</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh, Delete } from "@element-plus/icons-vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import {
  fetchRules, fetchRuleMeta, createRule, updateRule, toggleRule, deleteRule, testRuleDraft,
  type RiskRuleVo, type RuleDraft
} from "@/api/risk";
import { severityColor, severityLabel, formatTime, truncateSql } from "@/views/risk/risk-ui";

const rules = ref<RiskRuleVo[]>([]);
const fields = ref<string[]>([]);
const ops = ref<string[]>([]);
const loading = ref(false);
const error = ref("");
const tab = ref("all");

const detail = ref<RiskRuleVo | null>(null);
const detailDrawer = ref(false);
const editingParams = ref(false);
const paramDraft = reactive<Record<string, number>>({});
const severityDraft = ref("MEDIUM");

const builder = ref(false);
const editingRule = ref<RiskRuleVo | null>(null);
const windowEnabled = ref(false);
const draft = reactive<RuleDraft>({
  name: "", description: "", category: "CUSTOM", severity: "MEDIUM", enabled: true,
  conditions: [{ field: "sql", op: "contains", value: "" }],
  window: null
});
const testResult = ref<{ scanned: number; matched: number; samples: { eventId: string; timestamp: string; user: string; sql: string; evidence: string }[] } | null>(null);
const testing = ref(false);
const saving = ref(false);

const builtinRules = computed(() => rules.value.filter(r => r.kind === "BUILT_IN"));
const customRules = computed(() => rules.value.filter(r => r.kind === "CUSTOM"));
const enabledBuiltin = computed(() => builtinRules.value.filter(r => r.enabled).length);
const enabledCustom = computed(() => customRules.value.filter(r => r.enabled).length);
const totalHits = computed(() => rules.value.reduce((s, r) => s + (r.hitCount || 0), 0));
const filteredRules = computed(() =>
  tab.value === "all" ? rules.value : tab.value === "builtin" ? builtinRules.value : customRules.value);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    rules.value = await fetchRules();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function openDetail(rule: RiskRuleVo) {
  detail.value = rule;
  editingParams.value = false;
  detailDrawer.value = true;
}

function paramEntries(rule: RiskRuleVo): [string, string | number][] {
  return Object.entries(rule.params || {});
}

function startEditParams() {
  if (!detail.value) return;
  Object.keys(paramDraft).forEach(k => delete paramDraft[k]);
  for (const [k, v] of paramEntries(detail.value)) {
    paramDraft[k] = typeof v === "number" ? v : Number(v);
  }
  severityDraft.value = detail.value.severity.toUpperCase();
  editingParams.value = true;
}

async function saveParams() {
  if (!detail.value) return;
  try {
    await updateRule(detail.value.id, {
      severity: severityDraft.value,
      params: { ...paramDraft },
      enabled: detail.value.enabled
    });
    ElMessage.success("规则已更新");
    editingParams.value = false;
    detailDrawer.value = false;
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function toggle(rule: RiskRuleVo, enabled: boolean) {
  try {
    await toggleRule(rule.id, enabled);
    rule.enabled = enabled;
    ElMessage.success(`${rule.name} 已${enabled ? "启用" : "停用"}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function remove(rule: RiskRuleVo) {
  try {
    await ElMessageBox.confirm(`确定删除自定义规则「${rule.name}」?`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteRule(rule.id);
    ElMessage.success("已删除");
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function openBuilder(rule?: RiskRuleVo) {
  editingRule.value = rule ?? null;
  testResult.value = null;
  if (rule) {
    draft.name = rule.name;
    draft.description = rule.description;
    draft.category = rule.category;
    draft.severity = rule.severity.toUpperCase();
    draft.enabled = rule.enabled;
    draft.conditions = (rule.spec?.conditions ?? [{ field: "sql", op: "contains", value: "" }]).map(c => ({ ...c }));
    draft.window = rule.spec?.window ? { ...rule.spec.window } : null;
    windowEnabled.value = !!rule.spec?.window;
  } else {
    draft.name = "";
    draft.description = "";
    draft.category = "CUSTOM";
    draft.severity = "MEDIUM";
    draft.enabled = true;
    draft.conditions = [{ field: "sql", op: "contains", value: "" }];
    draft.window = { seconds: 60, count: 5, groupBy: "user" };
    windowEnabled.value = false;
  }
  builder.value = true;
}

async function runTest() {
  testing.value = true;
  testResult.value = null;
  try {
    testResult.value = await testRuleDraft(buildDraft(), 1000);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    testing.value = false;
  }
}

function buildDraft(): RuleDraft & { id?: string } {
  return {
    id: editingRule.value?.id,
    name: draft.name || "未命名规则",
    description: draft.description,
    category: draft.category,
    severity: draft.severity,
    enabled: draft.enabled,
    conditions: draft.conditions.filter(c => c.field && c.op),
    window: windowEnabled.value ? draft.window : null
  };
}

async function save() {
  if (!draft.name.trim()) {
    ElMessage.warning("请填写规则名称");
    return;
  }
  if (!draft.conditions.length || draft.conditions.some(c => !c.field || !c.op)) {
    ElMessage.warning("请至少配置一个完整条件");
    return;
  }
  saving.value = true;
  try {
    if (editingRule.value) {
      await updateRule(editingRule.value.id, buildDraft());
      ElMessage.success("规则已保存");
    } else {
      await createRule(buildDraft());
      ElMessage.success("规则已创建");
    }
    builder.value = false;
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function testExisting(rule: RiskRuleVo) {
  try {
    const r = await testRuleDraft({ id: rule.id, name: rule.name, severity: rule.severity, conditions: [] });
    ElMessage({
      message: `「${rule.name}」最近 ${r.scanned} 条事件中命中 ${r.matched} 条${r.samples.length ? "(例如用户 " + r.samples[0].user + ")" : ""}`,
      type: r.matched > 0 ? "warning" : "info"
    });
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function categoryLabel(c: string): string {
  switch (c) {
    case "SQLI": return "SQL 注入";
    case "BEHAVIOR": return "行为异常";
    case "COMPLIANCE": return "合规";
    case "DATA_EXPOSURE": return "数据暴露";
    default: return "自定义";
  }
}

const FIELD_LABELS: Record<string, string> = {
  user: "用户", ip: "来源 IP", eventType: "事件类型", outcome: "结果", dialect: "方言",
  masked: "已脱敏", rowFiltered: "已行过滤", sql: "SQL 文本", errorCode: "错误码",
  rowCount: "返回行数", hour: "小时(0-23)", instance: "实例", table: "涉及表", column: "敏感列"
};

function fieldLabel(f: string): string {
  return FIELD_LABELS[f] ?? f;
}

const OP_LABELS: Record<string, string> = {
  eq: "等于", neq: "不等于", contains: "包含", not_contains: "不包含", regex: "匹配正则",
  in: "属于列表", not_in: "不属于列表", gt: "大于", lt: "小于", startswith: "前缀", endswith: "后缀"
};

function opLabel(o: string): string {
  return OP_LABELS[o] ?? o;
}

function paramsSummary(rule: RiskRuleVo): string {
  const entries = paramEntries(rule);
  if (!entries.length) return "—";
  return entries.map(([k, v]) => `${k}=${v}`).join(", ");
}

function specSummary(rule: RiskRuleVo): string {
  const conds = (rule.spec?.conditions ?? []).map(c => `${fieldLabel(c.field)} ${opLabel(c.op)} ${c.value}`).join(" 且 ");
  const w = rule.spec?.window ? ` [窗口 ${rule.spec.window.count}次/${rule.spec.window.seconds}s]` : "";
  return conds + w;
}

onMounted(async () => {
  await load();
  try {
    const meta = await fetchRuleMeta();
    fields.value = meta.fields;
    ops.value = meta.ops;
  } catch {
    fields.value = Object.keys(FIELD_LABELS);
    ops.value = Object.keys(OP_LABELS);
  }
});
</script>

<style scoped lang="scss">
.topnav-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.stat-num { font-size: 24px; font-weight: 700; }
.stat-label { color: var(--sm-muted); font-size: 12px; margin-top: 4px; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; }
.rule-id { font-size: 11px; }
.spec-cell { font-size: 12px; color: var(--sm-muted); }

.d-kv { display: flex; gap: 8px; align-items: center; }
.d-section { margin: 16px 0; }
.d-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.d-desc { font-size: 13px; line-height: 1.7; }
.d-actions { display: flex; gap: 8px; }
.param-row { display: flex; align-items: center; gap: 12px; padding: 6px 0; }
.param-k { min-width: 150px; color: var(--sm-muted); font-size: 12.5px; }
.cond-row { padding: 6px 10px; background: #f6fafb; border-radius: 4px; margin-bottom: 6px; font-size: 12.5px; }

.cond-builder { width: 100%; }
.cond-edit-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.hint { display: block; margin-top: 4px; font-size: 12px; }
.window-row { display: flex; align-items: center; gap: 8px; margin-top: 8px; }

.test-panel {
  border: 1px solid #dbe7ec; background: #f6fafb; border-radius: 6px;
  padding: 10px 12px; font-size: 13px;
}
.test-sample { display: flex; gap: 8px; align-items: center; margin-top: 6px; font-size: 12px; }
.test-sample .sql { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 420px; }
</style>
