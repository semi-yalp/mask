<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="按 UDF 名过滤" clearable style="width: 240px" :prefix-icon="Search" />
      <span class="muted">注册脱敏 UDF 及其签名,供策略引用</span>
      <span class="spacer" />
      <el-button type="primary" @click="openForm(null)">注册 UDF</el-button>
    </div>

    <el-table :data="filtered" size="default" stripe v-loading="loading">
      <el-table-column label="UDF 名" min-width="200">
        <template #default="{ row }">
          <a class="udf-link mono" @click="openForm(row)">{{ row.name }}</a>
        </template>
      </el-table-column>
      <el-table-column label="签名数" width="90" align="center">
        <template #default="{ row }">{{ (row.signatures || []).length }}</template>
      </el-table-column>
      <el-table-column label="签名(参数 → 返回)" min-width="420" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono muted">{{ sigText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openForm(row)">编辑</el-button>
          <el-button size="small" text type="danger" @click="removeUdf(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <EmptyHint v-if="!filtered.length">{{ loadError ? "加载失败:" + loadError : (keyword ? "无匹配 UDF" : "暂无 UDF") }}</EmptyHint>

    <el-drawer v-model="drawer" :title="editing ? '编辑 UDF:' + editing.name : '注册 UDF'" size="480px" destroy-on-close>
      <el-form label-width="86px" label-position="left">
        <el-form-item label="UDF 名"><el-input v-model="form.name" placeholder="如 mask_phone" /></el-form-item>
        <el-form-item label="签名列表">
          <div class="sig-list">
            <div v-for="(s, si) in form.signatures" :key="si" class="sig-row">
              <el-input v-model="s.paramsText" placeholder="参数类型,逗号分隔,如 varchar, integer, integer" />
              <el-input v-model="s.returns" placeholder="返回类型" style="width: 120px" />
              <el-button size="small" type="danger" plain :disabled="form.signatures.length <= 1"
                @click="form.signatures.splice(si, 1)">✕</el-button>
            </div>
            <el-button size="small" @click="form.signatures.push({ paramsText: '', returns: 'varchar' })">＋ 签名</el-button>
          </div>
        </el-form-item>
      </el-form>
      <div class="drawer-foot">
        <el-button type="primary" :loading="saving" @click="submit">保存 UDF</el-button>
        <span v-if="formError" class="form-error">{{ formError }}</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Search } from "@element-plus/icons-vue";
import { createUdf, deleteUdf, listUdfs, updateUdf } from "@/api/udfs";
import type { Udf } from "@/types/domain";
import EmptyHint from "@/components/EmptyHint.vue";

const props = defineProps<{ instance: string }>();

const udfs = ref<Udf[]>([]);
const loadError = ref("");
const loading = ref(false);
const keyword = ref("");
const drawer = ref(false);
const editing = ref<Udf | null>(null);
const saving = ref(false);
const formError = ref("");

const form = ref(emptyForm());

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  return k ? udfs.value.filter((u) => (u.name || "").toLowerCase().includes(k)) : udfs.value;
});

function emptyForm() {
  return { name: "", signatures: [{ paramsText: "varchar", returns: "varchar" }] };
}

async function load() {
  loadError.value = "";
  loading.value = true;
  try { udfs.value = await listUdfs(props.instance); }
  catch (e) { loadError.value = (e as Error).message; }
  finally { loading.value = false; }
}
onMounted(load);

function sigText(u: Udf): string {
  return (u.signatures || []).map((s) => `(${(s.params || []).join(", ") || "—"}) → ${s.returns || "varchar"}`).join("  |  ") || "—";
}

function openForm(u: Udf | null) {
  editing.value = u;
  formError.value = "";
  form.value = u && u.signatures && u.signatures.length
    ? {
        name: u.name,
        signatures: u.signatures.map((s) => ({
          paramsText: (s.params || []).join(", "),
          returns: s.returns || "varchar"
        }))
      }
    : emptyForm();
  drawer.value = true;
}

async function submit() {
  formError.value = "";
  saving.value = true;
  const body: Udf = {
    name: form.value.name.trim(),
    signatures: form.value.signatures.map((s) => ({
      params: s.paramsText.split(",").map((x) => x.trim()).filter(Boolean),
      returns: s.returns.trim() || "varchar"
    }))
  };
  try {
    if (editing.value) await updateUdf(props.instance, editing.value.name, body);
    else await createUdf(props.instance, body);
    drawer.value = false;
    ElMessage.success("UDF 已保存");
    await load();
  } catch (e) { formError.value = (e as Error).message; }
  finally { saving.value = false; }
}

async function removeUdf(u: Udf) {
  try { await ElMessageBox.confirm(`删除 UDF ${u.name}?`, "删除确认", { type: "warning" }); } catch { return; }
  try {
    await deleteUdf(props.instance, u.name);
    ElMessage.success("UDF 已删除");
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
}
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 12px; margin: 10px 0 12px; .spacer { flex: 1; } }
.udf-link { color: var(--sm-primary-dark); font-weight: 600; cursor: pointer; &:hover { text-decoration: underline; } }
.sig-list { width: 100%; display: flex; flex-direction: column; gap: 8px; }
.sig-row { display: flex; gap: 8px; }
.drawer-foot { display: flex; align-items: center; gap: 10px; }
.form-error { color: var(--el-color-danger, #dc3545); font-size: 12.5px; word-break: break-all; }
</style>
