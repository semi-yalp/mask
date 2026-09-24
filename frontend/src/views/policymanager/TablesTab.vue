<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="按 catalog / schema / 表名过滤" clearable style="width: 260px" :prefix-icon="Search" />
      <span class="muted">资源(表结构)供策略解析与改写使用;可手动维护或从元数据服务导入</span>
      <span class="spacer" />
      <span v-if="dirty" class="dirty">有未保存修改</span>
      <el-button :disabled="!dirty" :loading="saving" @click="save">保存全部</el-button>
      <el-button type="primary" @click="addTable">添加表</el-button>
    </div>

    <el-table :data="paged" size="default" stripe>
      <el-table-column label="资源路径(catalog.schema.table)" min-width="300">
        <template #default="{ row }">
          <span class="mono resource-link">{{ tableKey(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="列数" width="80" align="center">
        <template #default="{ row }">{{ (row.columns || []).length }}</template>
      </el-table-column>
      <el-table-column label="列" min-width="380" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="muted mono">{{ colsText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="行过滤" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.rowFilter" class="mono">{{ row.rowFilter }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ $index }">
          <el-button size="small" text type="primary" @click="editTable($index)">编辑</el-button>
          <el-button size="small" text type="danger" @click="removeTable($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="filtered.length > pageSize" class="pager-row">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :total="filtered.length"
        layout="total, prev, pager, next" size="small" />
    </div>
    <EmptyHint v-if="!filtered.length">{{ keyword ? "无匹配表" : "暂无表结构。可「添加表」,或经顶栏「管理实例 → 从元数据服务导入」。" }}</EmptyHint>

    <el-drawer v-model="drawer" :title="editIndex >= 0 ? '编辑表:' + (draft.columns.length ? draft.name : '') : '添加表'"
      size="640px" destroy-on-close>
      <div class="section">资源路径</div>
      <div class="grid3">
        <el-input v-model="draft.catalog" placeholder="catalog" />
        <el-input v-model="draft.schema" placeholder="schema" />
        <el-input v-model="draft.name" placeholder="表名" />
      </div>
      <el-input v-model="draft.rowFilter" class="rowfilter" placeholder="行过滤条件(可选,编译前的原始声明)" />

      <div class="section">列</div>
      <div v-for="(c, ci) in draft.columns" :key="ci" class="col-row">
        <el-input v-model="c.name" placeholder="列名" style="flex: 1" />
        <el-input v-model="c.type" placeholder="类型,如 varchar" style="flex: 1" />
        <el-button size="small" type="danger" plain @click="draft.columns.splice(ci, 1)">✕</el-button>
      </div>
      <el-button size="small" @click="draft.columns.push({ name: '', type: 'varchar' })">＋ 添加列</el-button>

      <div class="drawer-foot">
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" @click="confirmDraft">确定</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Search } from "@element-plus/icons-vue";
import { putTables } from "@/api/instances";
import type { InstanceInfo, TableDef } from "@/types/domain";
import EmptyHint from "@/components/EmptyHint.vue";

const props = defineProps<{ inst: InstanceInfo }>();
const emit = defineEmits<{ (e: "refresh"): void }>();

const tables = ref<TableDef[]>([]);
const dirty = ref(false);
const keyword = ref("");
const drawer = ref(false);
const editIndex = ref(-1);
const draft = ref<TableDef>({ catalog: "", schema: "", name: "", rowFilter: "", columns: [] });
const page = ref(1);
const pageSize = 20;
const saving = ref(false);

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return tables.value;
  return tables.value.filter((t) => tableKey(t).toLowerCase().includes(k));
});

const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize));

watch(() => props.inst, (inst) => {
  tables.value = JSON.parse(JSON.stringify(inst.tables || [])) as TableDef[];
  tables.value.forEach((t) => { t.rowFilter = t.rowFilter || ""; });
  dirty.value = false;
}, { immediate: true });

function tableKey(t: TableDef): string {
  return [t.catalog, t.schema, t.name].filter((x) => String(x || "").trim() !== "").join(".");
}

function colsText(t: TableDef): string {
  return (t.columns || []).map((c) => `${c.name} ${c.type}`).join("; ") || "—";
}

function addTable() {
  editIndex.value = -1;
  draft.value = {
    catalog: props.inst.dialect === "mysql" ? "shop" : "crm",
    schema: "public", name: "", rowFilter: "", columns: [{ name: "id", type: "bigint" }]
  };
  drawer.value = true;
}

function editTable(ti: number) {
  editIndex.value = ti;
  draft.value = JSON.parse(JSON.stringify(tables.value[ti])) as TableDef;
  draft.value.rowFilter = draft.value.rowFilter || "";
  drawer.value = true;
}

function confirmDraft() {
  if (!draft.value.name.trim()) { ElMessage.warning("表名不能为空"); return; }
  if (editIndex.value >= 0) tables.value[editIndex.value] = JSON.parse(JSON.stringify(draft.value)) as TableDef;
  else tables.value.push(JSON.parse(JSON.stringify(draft.value)) as TableDef);
  dirty.value = true;
  drawer.value = false;
}

async function removeTable(ti: number) {
  const t = tables.value[ti];
  try {
    await ElMessageBox.confirm(`删除表 ${tableKey(t)}?保存全部后生效。`, "删除确认", { type: "warning" });
  } catch { return; }
  tables.value.splice(ti, 1);
  dirty.value = true;
}

async function save() {
  saving.value = true;
  try {
    await putTables(props.inst.name, tables.value);
    dirty.value = false;
    ElMessage.success("表结构已保存");
    emit("refresh");
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { saving.value = false; }
}
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 12px; margin: 10px 0 12px; flex-wrap: wrap; .spacer { flex: 1; } }
.dirty { color: #b07a12; font-size: 12px; }
.pager-row { display: flex; justify-content: flex-end; margin-top: 10px; }
.resource-link { color: var(--sm-text); font-weight: 600; }
.section {
  font-size: 12px; font-weight: 700; color: var(--sm-primary-dark); letter-spacing: 0.8px;
  text-transform: uppercase; margin: 4px 0 12px; padding-bottom: 6px; border-bottom: 1px solid var(--sm-border);
}
.grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 8px; }
.rowfilter { width: 100%; margin-bottom: 8px; }
.col-row { display: flex; gap: 8px; margin-bottom: 6px; }
.drawer-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }
</style>
