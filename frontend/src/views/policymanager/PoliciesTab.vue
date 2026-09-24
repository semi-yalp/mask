<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="按策略名 / 资源过滤" clearable style="width: 260px" :prefix-icon="Search" />
      <span class="muted">DATAMASK = 列脱敏;ROW_FILTER = 行过滤;priority 小者优先</span>
      <span class="spacer" />
      <el-button type="success" class="add-btn" @click="openForm(null)">Add New Policy</el-button>
    </div>

    <el-table :data="filtered" size="default" stripe class="policy-table" v-loading="loading">
      <el-table-column label="策略名" min-width="200">
        <template #default="{ row }">
          <a class="policy-link" @click="openForm(row)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.isEnabled ? 'success' : 'info'">{{ row.isEnabled ? "启用" : "停用" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.policyType || "datamask" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="80" align="center">
        <template #default="{ row }">{{ row.priority ?? "*" }}</template>
      </el-table-column>
      <el-table-column label="资源" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ resourceText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="主体" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ subjectText(row) }}</template>
      </el-table-column>
      <el-table-column label="脱敏 / 行过滤" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.policyType === 'row_filter'" class="mono">{{ row.filterExpr || "—" }}</span>
          <span v-else class="mono">{{ udfText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openForm(row)">编辑</el-button>
          <el-button size="small" text type="danger" @click="removePolicy(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <EmptyHint v-if="!loading && !filtered.length">
      {{ loadError ? "加载失败:" + loadError : (keyword ? "无匹配策略" : "暂无策略,点 Add New Policy 创建") }}
    </EmptyHint>

    <el-drawer v-model="drawer" :title="editing ? '编辑策略:' + editing.name : '新建策略'" size="560px" destroy-on-close>
      <el-form label-width="92px" label-position="left">
        <div class="section">策略详情</div>
        <el-form-item label="策略名">
          <el-input v-model="form.name" placeholder="如 mask_phone_policy" />
          <span v-if="nameError" class="field-error">{{ nameError }}</span>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.policyType" style="width: 100%">
            <el-option value="datamask" label="datamask(列脱敏)" />
            <el-option value="row_filter" label="row_filter(行过滤)" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.isEnabled" /></el-form-item>
        <el-form-item label="priority">
          <el-input v-model="form.priorityText" :class="{ 'is-error': priorityError }" placeholder="可选,小者优先(整数)" />
          <span v-if="priorityError" class="field-error">{{ priorityError }}</span>
        </el-form-item>

        <div class="section">策略资源</div>
        <el-form-item label="资源路径">
          <div class="grid2">
            <el-input v-model="form.catalog" placeholder="catalog" />
            <el-input v-model="form.schema" placeholder="schema" />
            <el-input v-model="form.table" placeholder="table" />
            <el-input v-model="form.columnsText" placeholder="columns,逗号分隔(脱敏)" />
          </div>
          <span v-if="resourceError" class="field-error">{{ resourceError }}</span>
        </el-form-item>

        <div class="section">主体(* = 任意主体)</div>
        <el-form-item label="Users">
          <el-input v-model="form.usersText" placeholder="users,逗号分隔(* = 所有人)" />
        </el-form-item>
        <el-form-item label="Groups">
          <el-input v-model="form.groupsText" placeholder="groups,逗号分隔" />
        </el-form-item>

        <div class="section">脱敏配置</div>
        <template v-if="form.policyType !== 'row_filter'">
          <el-form-item label="UDF">
            <el-input v-model="form.udf" placeholder="udf 名,如 mask_phone" />
          </el-form-item>
          <el-form-item label="参数">
            <el-input v-model="form.argsText" placeholder="arguments,如 3, 4" />
            <span v-if="udfError" class="field-error">{{ udfError }}</span>
          </el-form-item>
        </template>
        <el-form-item v-else label="过滤表达式">
          <el-input v-model="form.filterExpr" placeholder="如 status = 'active'" />
          <span v-if="filterError" class="field-error">{{ filterError }}</span>
        </el-form-item>
      </el-form>
      <div class="drawer-foot">
        <el-button type="primary" :loading="saving" @click="submit">保存策略</el-button>
        <span v-if="formError" class="form-error">{{ formError }}</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Search } from "@element-plus/icons-vue";
import { createPolicy, deletePolicy, listPolicies, updatePolicy } from "@/api/policies";
import type { Policy } from "@/types/domain";
import EmptyHint from "@/components/EmptyHint.vue";

const props = defineProps<{ instance: string }>();

const policies = ref<Policy[]>([]);
const loadError = ref("");
const loading = ref(false);
const keyword = ref("");
const drawer = ref(false);
const editing = ref<Policy | null>(null);
const saving = ref(false);
const formError = ref("");

const form = ref(emptyForm());

const nameError = computed(() => {
  const n = form.value.name.trim();
  if (!n) return "策略名必填";
  return /^[A-Za-z][A-Za-z0-9_]*$/.test(n) ? "" : "字母开头,仅字母/数字/下划线";
});
const priorityError = computed(() => {
  const t = form.value.priorityText.trim();
  if (!t) return "";
  return /^-?\d+$/.test(t) ? "" : "priority 必须是整数(留空表示缺省)";
});
const resourceError = computed(() => {
  const f = form.value;
  if (!f.catalog.trim() || !f.schema.trim() || !f.table.trim()) return "catalog / schema / table 必填";
  if (f.policyType !== "row_filter" && !csv(f.columnsText).length) return "datamask 策略至少选择一个列";
  return "";
});
const udfError = computed(() =>
  form.value.policyType !== "row_filter" && !form.value.udf.trim() ? "datamask 策略必须指定 UDF" : "");
const filterError = computed(() =>
  form.value.policyType === "row_filter" && !form.value.filterExpr.trim() ? "行过滤表达式必填" : "");
const formValid = computed(() =>
  !(nameError.value || priorityError.value || resourceError.value || udfError.value || filterError.value));

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return policies.value;
  return policies.value.filter((p) =>
    (p.name || "").toLowerCase().includes(k) || resourceText(p).toLowerCase().includes(k));
});

function emptyForm() {
  return {
    name: "", policyType: "datamask", isEnabled: true, priorityText: "",
    catalog: "", schema: "", table: "", columnsText: "",
    usersText: "", groupsText: "", udf: "", argsText: "", filterExpr: ""
  };
}

async function load() {
  loadError.value = "";
  loading.value = true;
  try {
    policies.value = await listPolicies(props.instance);
  } catch (e) { loadError.value = (e as Error).message; }
  finally { loading.value = false; }
}
onMounted(load);

function csv(s: string): string[] {
  return s.split(",").map((x) => x.trim()).filter(Boolean);
}

function parseArg(a: string): number | string {
  return /^-?\d+(\.\d+)?$/.test(a) ? (/\./.test(a) ? parseFloat(a) : parseInt(a, 10)) : a;
}

function openForm(p: Policy | null) {
  editing.value = p;
  formError.value = "";
  const r = p?.resource || { catalog: "", schema: "", table: "", columns: [] };
  form.value = {
    name: p?.name || "",
    policyType: p?.policyType || "datamask",
    isEnabled: p ? p.isEnabled !== false : true,
    priorityText: p && p.priority !== null && p.priority !== undefined ? String(p.priority) : "",
    catalog: r.catalog || "", schema: r.schema || "", table: r.table || "",
    columnsText: (r.columns || []).join(", "),
    usersText: (p?.subjects?.users || []).join(", "),
    groupsText: (p?.subjects?.groups || []).join(", "),
    udf: p?.udf || "",
    argsText: p?.arguments ? p.arguments.join(", ") : "",
    filterExpr: p?.filterExpr || ""
  };
  drawer.value = true;
}

/** 供策略管理器顶栏「Add New Policy」调用。 */
function openCreate() { openForm(null); }
defineExpose({ openCreate });

async function submit() {
  formError.value = "";
  if (!formValid.value) { formError.value = "表单存在校验错误,请按提示修正"; return; }
  saving.value = true;
  const f = form.value;
  const body: Policy = {
    name: f.name.trim(),
    policyType: f.policyType,
    isEnabled: f.isEnabled,
    priority: f.priorityText.trim() === "" ? null : parseInt(f.priorityText, 10),
    resource: { catalog: f.catalog.trim(), schema: f.schema.trim(), table: f.table.trim(), columns: csv(f.columnsText) },
    subjects: { users: csv(f.usersText), groups: csv(f.groupsText) },
    udf: f.policyType === "row_filter" ? null : (f.udf.trim() || null),
    arguments: f.policyType === "row_filter" ? [] : csv(f.argsText).map(parseArg),
    filterExpr: f.policyType === "row_filter" ? (f.filterExpr.trim() || null) : null
  };
  try {
    if (editing.value) await updatePolicy(props.instance, editing.value.name, body);
    else await createPolicy(props.instance, body);
    drawer.value = false;
    ElMessage.success("策略已保存");
    await load();
  } catch (e) { formError.value = (e as Error).message; }
  finally { saving.value = false; }
}

async function removePolicy(p: Policy) {
  try { await ElMessageBox.confirm(`删除策略 ${p.name}?`, "删除确认", { type: "warning" }); } catch { return; }
  try {
    await deletePolicy(props.instance, p.name);
    ElMessage.success("策略已删除");
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
}

function resourceText(p: Policy): string {
  const r = p.resource || {};
  const base = [r.catalog, r.schema, r.table].filter((x) => String(x || "").trim() !== "").join(".") || "—";
  return r.columns && r.columns.length ? `${base} · ${r.columns.join(", ")}` : base;
}
function udfText(p: Policy): string {
  const args = p.arguments && p.arguments.length ? `(${p.arguments.join(", ")})` : "";
  return (p.udf || "—") + args;
}
function subjectText(p: Policy): string {
  const s = p.subjects || {};
  return (s.users || s.groups)
    ? `users=[${(s.users || []).join(", ") || "—"}] groups=[${(s.groups || []).join(", ") || "—"}]`
    : "任意(*)";
}
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 12px; margin: 10px 0 12px; .spacer { flex: 1; } }
.add-btn {
  background: var(--sm-success); border-color: var(--sm-success); color: #fff; font-weight: 600;
  &:hover, &:focus { background: var(--sm-success-dark); border-color: var(--sm-success-dark); color: #fff; }
}
.policy-link { color: var(--sm-primary-dark); font-weight: 600; cursor: pointer; &:hover { text-decoration: underline; } }
.section {
  font-size: 12px; font-weight: 700; color: var(--sm-primary-dark); letter-spacing: 0.8px;
  text-transform: uppercase; margin: 6px 0 12px; padding-bottom: 6px; border-bottom: 1px solid var(--sm-border);
}
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100%; }
.drawer-foot { display: flex; align-items: center; gap: 10px; }
.form-error { color: var(--el-color-danger, #dc3545); font-size: 12.5px; word-break: break-all; }
.field-error { display: block; width: 100%; font-size: 12px; color: var(--el-color-danger, #dc3545); margin-top: 4px; }
.is-error :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger, #dc3545) inset; }
</style>
