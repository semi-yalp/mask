<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="按策略名 / 资源过滤" clearable style="width: 260px" :prefix-icon="Search" />
      <span class="muted">DATAMASK = 列脱敏;ROW_FILTER = 行过滤;priority 小者优先</span>
      <span class="spacer" />
      <el-button :loading="exporting" @click="doExport">导出 YAML</el-button>
      <el-button :loading="importing" @click="pickImportFile">导入 YAML</el-button>
      <el-button type="success" class="add-btn" @click="openForm(null)">Add New Policy</el-button>
      <input ref="importInput" type="file" accept=".yaml,.yml" style="display: none" @change="onImportFile" />
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
          <el-autocomplete v-model="form.name" :fetch-suggestions="nameSuggest" placeholder="如 mask_phone_policy">
            <template #default="{ item }"><div class="suggest-item">{{ item.label || item.value }}</div></template>
          </el-autocomplete>
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
            <el-autocomplete v-model="form.table" :fetch-suggestions="tableSuggest" :trigger-on-focus="false"
              placeholder="table" @select="onPickTable">
              <template #default="{ item }"><div class="suggest-item">{{ item.label || item.value }}</div></template>
            </el-autocomplete>
            <el-autocomplete v-model="form.columnsText" :fetch-suggestions="columnSuggest" :trigger-on-focus="false"
              placeholder="columns,逗号分隔(脱敏)" @select="onPickColumn">
              <template #default="{ item }"><div class="suggest-item">{{ item.label || item.value }}</div></template>
            </el-autocomplete>
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
            <el-autocomplete v-model="form.udf" :fetch-suggestions="udfSuggest" placeholder="udf 名,如 mask_phone">
              <template #default="{ item }"><div class="suggest-item">{{ item.label || item.value }}</div></template>
            </el-autocomplete>
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
import { createPolicy, deletePolicy, exportPoliciesYaml, importPoliciesYaml, listPolicies, updatePolicy } from "@/api/policies";
import { getInstance } from "@/api/instances";
import { listUdfs } from "@/api/udfs";
import type { Policy, TableDef, Udf } from "@/types/domain";
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
const exporting = ref(false);
const importing = ref(false);
const importInput = ref<HTMLInputElement | null>(null);

const form = ref(emptyForm());

/** 联想建议项:value 为选中后填入输入框的文本,label 为下拉展示文案。 */
interface SuggestItem {
  value: string;
  label?: string;
  /** 列/表所属表(用于按当前资源的排序加权)。 */
  _table?: string;
}

const udfs = ref<Udf[]>([]);
const tables = ref<TableDef[]>([]);

/** 联想数据源(已注册 UDF、实例表结构):失败时静默降级为手工输入。 */
async function loadSuggestBase() {
  try {
    const [u, inst] = await Promise.all([listUdfs(props.instance), getInstance(props.instance)]);
    udfs.value = u;
    tables.value = inst.tables || [];
  } catch { /* 联想不可用不影响表单使用 */ }
}

const namePool = computed<SuggestItem[]>(() => policies.value.map((p) => ({ value: p.name })));
const udfPool = computed<SuggestItem[]>(() =>
  udfs.value.map((u) => {
    const sig = u.signatures && u.signatures.length ? u.signatures[0] : null;
    return {
      value: u.name,
      label: sig ? `${u.name}(${sig.params.join(", ")}) → ${sig.returns}` : u.name
    };
  }));
const tablePool = computed<SuggestItem[]>(() =>
  tables.value.map((t) => ({
    value: [t.catalog, t.schema, t.name].filter((x) => String(x || "").trim() !== "").join("."),
    _table: t.name
  })));
const columnPool = computed<SuggestItem[]>(() => {
  const counts = new Map<string, number>();
  const rows: Array<{ item: SuggestItem; key: string }> = [];
  for (const t of tables.value) {
    const tk = [t.catalog, t.schema, t.name].filter((x) => String(x || "").trim() !== "").join(".");
    for (const c of t.columns || []) {
      const key = c.name.toLowerCase();
      counts.set(key, (counts.get(key) || 0) + 1);
      rows.push({ item: { value: c.name, _table: tk }, key });
    }
  }
  return rows.map(({ item, key }) => {
    item.label = (counts.get(key) || 0) > 1 ? `${item.value} · ${item._table}` : item.value;
    return item;
  }).sort((a, b) => a.value.localeCompare(b.value));
});

/** 前缀命中优先,其次子串命中;空查询取前 8 条。 */
function matchList(items: SuggestItem[], query: string): SuggestItem[] {
  const q = query.trim().toLowerCase();
  if (!q) return items.slice(0, 8);
  const exact: SuggestItem[] = [];
  const rest: SuggestItem[] = [];
  for (const it of items) {
    const v = it.value.toLowerCase();
    if (v.startsWith(q)) exact.push(it);
    else if (v.includes(q)) rest.push(it);
  }
  return [...exact, ...rest].slice(0, 8);
}

function nameSuggest(query: string, cb: (items: SuggestItem[]) => void) {
  cb(matchList(namePool.value, query));
}

function udfSuggest(query: string, cb: (items: SuggestItem[]) => void) {
  cb(matchList(udfPool.value, query));
}

function tableSuggest(query: string, cb: (items: SuggestItem[]) => void) {
  const q = query.trim().toLowerCase();
  cb(tablePool.value
    .filter((it) => !q || it.value.toLowerCase().includes(q) || (it._table || "").toLowerCase().startsWith(q))
    .slice(0, 8));
}

function columnSuggest(query: string, cb: (items: SuggestItem[]) => void) {
  // columns 为逗号分隔输入,仅对最后一个 token 联想
  const i = query.lastIndexOf(",");
  const token = (i >= 0 ? query.slice(i + 1) : query).trim();
  const f = form.value;
  const cur = [f.catalog, f.schema, f.table].filter((x) => x.trim()).join(".").toLowerCase();
  const pool = cur
    ? [...columnPool.value].sort((a, b) => {
        const ra = (a._table || "").toLowerCase().startsWith(cur) ? 0 : 1;
        const rb = (b._table || "").toLowerCase().startsWith(cur) ? 0 : 1;
        return ra - rb;
      })
    : columnPool.value;
  cb(matchList(pool, token));
}

/** 选中表建议时回填 catalog/schema/table 三段。 */
function onPickTable(item: SuggestItem) {
  const parts = item.value.split(".");
  form.value.table = parts.pop() || "";
  form.value.schema = parts.pop() || "";
  form.value.catalog = parts.join(".");
}

/** columns 为逗号分隔输入,选中仅替换最后一个 token。 */
function onPickColumn(item: SuggestItem) {
  const text = form.value.columnsText;
  const i = text.lastIndexOf(",");
  form.value.columnsText = (i >= 0 ? text.slice(0, i + 1).replace(/\s*$/, "") + ", " : "") + item.value;
}

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
onMounted(() => { load(); loadSuggestBase(); });

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
  loadSuggestBase(); // 每次打开抽屉刷新联想数据(UDF/表结构可能在其他 Tab 变更)
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

async function doExport() {
  exporting.value = true;
  try {
    const blob = await exportPoliciesYaml(props.instance);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `policies-${props.instance}.yaml`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    ElMessage.success("策略已导出为 policies.yaml");
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { exporting.value = false; }
}

function pickImportFile() {
  importInput.value?.click();
}

async function onImportFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ""; // 允许再次选择同一文件
  if (!file) return;
  const text = await file.text();
  try {
    await ElMessageBox.confirm(
      `将按名合并导入 ${file.name}：同名策略更新为新内容，新名策略创建。继续?`,
      "导入策略确认", { type: "warning", confirmButtonText: "导入" });
  } catch { return; }
  importing.value = true;
  try {
    const r = await importPoliciesYaml(props.instance, text);
    ElMessage.success(`导入完成:创建 ${r.created} 条,更新 ${r.updated} 条`);
    await load();
  } catch (e) {
    ElMessage.error({ message: (e as Error).message, duration: 6000 });
  } finally { importing.value = false; }
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
.el-autocomplete { width: 100%; }
.suggest-item { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12.5px; }
.drawer-foot { display: flex; align-items: center; gap: 10px; }
.form-error { color: var(--el-color-danger, #dc3545); font-size: 12.5px; word-break: break-all; }
.field-error { display: block; width: 100%; font-size: 12px; color: var(--el-color-danger, #dc3545); margin-top: 4px; }
.is-error :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger, #dc3545) inset; }
</style>
