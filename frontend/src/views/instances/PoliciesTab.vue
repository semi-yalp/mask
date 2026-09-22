<template>
  <div>
    <div class="actions-bar">
      <el-button type="primary" plain @click="openForm(null)">＋ 新建策略</el-button>
      <span class="muted">DATAMASK = 列脱敏;ROW_FILTER = 行过滤</span>
    </div>

    <div v-for="(p, i) in policies" :key="p.name" class="policy-card">
      <div class="policy-head">
        <span class="policy-name">{{ p.name }}</span>
        <el-tag size="small" :type="p.isEnabled ? 'success' : 'info'">{{ p.isEnabled ? "启用" : "停用" }}</el-tag>
        <el-tag size="small" effect="plain">{{ p.policyType || "datamask" }}</el-tag>
        <el-tag v-if="p.priority !== null && p.priority !== undefined" size="small" effect="plain">priority {{ p.priority }}</el-tag>
        <span class="spacer" />
        <el-button size="small" @click="openForm(p)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="removePolicy(i)">删除</el-button>
      </div>
      <div class="kv"><b>资源</b>{{ resourceText(p) }}</div>
      <div class="kv">
        <b>{{ p.policyType === "row_filter" ? "行过滤" : "UDF" }}</b>
        {{ p.policyType === "row_filter" ? (p.filterExpr || "—") : udfText(p) }}
      </div>
      <div class="kv"><b>主体</b>{{ subjectText(p) }}</div>
    </div>
    <EmptyHint v-if="!policies.length">{{ loadError ? "加载失败:" + loadError : "暂无策略" }}</EmptyHint>

    <el-drawer v-model="drawer" :title="editing ? '编辑策略:' + editing.name : '新建策略'" size="520px" destroy-on-close>
      <el-form label-width="86px" label-position="left">
        <el-form-item label="策略名"><el-input v-model="form.name" placeholder="如 mask_phone_policy" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.policyType" style="width: 100%">
            <el-option value="datamask" label="datamask(列脱敏)" />
            <el-option value="row_filter" label="row_filter(行过滤)" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.isEnabled" /></el-form-item>
        <el-form-item label="priority"><el-input v-model="form.priorityText" placeholder="可选" /></el-form-item>
        <el-form-item label="资源">
          <div class="grid2">
            <el-input v-model="form.catalog" placeholder="catalog" />
            <el-input v-model="form.schema" placeholder="schema" />
            <el-input v-model="form.table" placeholder="table" />
            <el-input v-model="form.columnsText" placeholder="columns,逗号分隔(脱敏)" />
          </div>
        </el-form-item>
        <el-form-item label="主体">
          <div class="grid2">
            <el-input v-model="form.usersText" placeholder="users,逗号分隔(空=任意)" />
            <el-input v-model="form.groupsText" placeholder="groups,逗号分隔(空=任意)" />
          </div>
        </el-form-item>
        <el-form-item label="UDF 与参数">
          <div class="grid2">
            <el-input v-model="form.udf" placeholder="udf 名,如 mask_phone" :disabled="form.policyType === 'row_filter'" />
            <el-input v-model="form.argsText" placeholder="arguments,如 3, 4" :disabled="form.policyType === 'row_filter'" />
          </div>
        </el-form-item>
        <el-form-item label="行过滤表达式">
          <el-input v-model="form.filterExpr" placeholder="如 status = 'active'" />
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
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { createPolicy, deletePolicy, listPolicies, updatePolicy } from "@/api/policies";
import type { Policy } from "@/types/domain";
import EmptyHint from "@/components/EmptyHint.vue";

const props = defineProps<{ instance: string }>();

const policies = ref<Policy[]>([]);
const loadError = ref("");
const drawer = ref(false);
const editing = ref<Policy | null>(null);
const saving = ref(false);
const formError = ref("");

const form = ref(emptyForm());

function emptyForm() {
  return {
    name: "", policyType: "datamask", isEnabled: true, priorityText: "",
    catalog: "", schema: "", table: "", columnsText: "",
    usersText: "", groupsText: "", udf: "", argsText: "", filterExpr: ""
  };
}

async function load() {
  loadError.value = "";
  try {
    policies.value = await listPolicies(props.instance);
  } catch (e) { loadError.value = (e as Error).message; }
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

async function submit() {
  formError.value = "";
  saving.value = true;
  const f = form.value;
  const body: Policy = {
    name: f.name.trim(),
    policyType: f.policyType,
    isEnabled: f.isEnabled,
    priority: f.priorityText.trim() === "" ? null : parseInt(f.priorityText, 10),
    resource: { catalog: f.catalog.trim(), schema: f.schema.trim(), table: f.table.trim(), columns: csv(f.columnsText) },
    subjects: { users: csv(f.usersText), groups: csv(f.groupsText) },
    udf: f.udf.trim() || null,
    arguments: csv(f.argsText).map(parseArg),
    filterExpr: f.filterExpr.trim() || null
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

async function removePolicy(i: number) {
  const p = policies.value[i];
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
.actions-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.policy-card {
  border: 1px solid var(--sm-border); border-radius: 8px; padding: 12px;
  margin-bottom: 10px; background: #fbfcfe;
}
.policy-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap;
  .policy-name { font-weight: 700; font-size: 13.5px; color: var(--sm-primary-dark); }
  .spacer { flex: 1; }
}
.kv { font-size: 12.5px; line-height: 1.8; b { color: var(--sm-primary-dark); display: inline-block; min-width: 52px; } }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100%; }
.drawer-foot { display: flex; align-items: center; gap: 10px; }
.form-error { color: var(--el-color-danger, #dc2626); font-size: 12.5px; word-break: break-all; }
</style>
