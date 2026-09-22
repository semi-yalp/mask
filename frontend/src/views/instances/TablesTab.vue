<template>
  <div>
    <div v-for="(t, ti) in tables" :key="ti" class="table-card">
      <div class="table-head">
        <span class="table-title">表 {{ ti + 1 }}</span>
        <el-button size="small" type="danger" plain @click="removeTable(ti)">删除表</el-button>
      </div>
      <div class="grid3">
        <el-input v-model="t.catalog" placeholder="catalog" />
        <el-input v-model="t.schema" placeholder="schema" />
        <el-input v-model="t.name" placeholder="表名" />
      </div>
      <el-input v-model="t.rowFilter" class="rowfilter" placeholder="行过滤条件(预留,编译前的原始声明)" />
      <div v-for="(c, ci) in t.columns" :key="ci" class="col-row">
        <el-input v-model="c.name" placeholder="列名" style="flex: 1" />
        <el-input v-model="c.type" placeholder="类型" style="flex: 1" />
        <el-button size="small" type="danger" plain @click="removeCol(ti, ci)">✕</el-button>
      </div>
      <el-button size="small" @click="addCol(ti)">＋ 添加列</el-button>
    </div>

    <EmptyHint v-if="!tables.length">暂无表结构。可手动添加,或从页顶的跨服务元数据导入。</EmptyHint>

    <div class="actions">
      <el-button @click="addTable">＋ 添加表</el-button>
      <el-button type="primary" @click="save">保存全部表结构</el-button>
      <span v-if="dirty" class="dirty">有未保存修改</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { putTables } from "@/api/instances";
import type { InstanceInfo, TableDef } from "@/types/domain";
import EmptyHint from "@/components/EmptyHint.vue";

const props = defineProps<{ inst: InstanceInfo }>();
const emit = defineEmits<{ (e: "refresh"): void }>();

const tables = ref<TableDef[]>([]);
const dirty = ref(false);

watch(() => props.inst, (inst) => {
  tables.value = JSON.parse(JSON.stringify(inst.tables || [])) as TableDef[];
  tables.value.forEach((t) => { t.rowFilter = t.rowFilter || ""; });
  dirty.value = false;
}, { immediate: true });

function markDirty() { dirty.value = true; }

function addTable() {
  tables.value.push({
    catalog: props.inst.dialect === "mysql" ? "shop" : "crm",
    schema: "public", name: "", rowFilter: "", columns: []
  });
  markDirty();
}
function removeTable(ti: number) { tables.value.splice(ti, 1); markDirty(); }
function addCol(ti: number) { tables.value[ti].columns.push({ name: "", type: "varchar" }); markDirty(); }
function removeCol(ti: number, ci: number) { tables.value[ti].columns.splice(ci, 1); markDirty(); }

async function save() {
  try {
    await putTables(props.inst.name, tables.value);
    dirty.value = false;
    ElMessage.success("表结构已保存");
    emit("refresh");
  } catch (e) { ElMessage.error((e as Error).message); }
}
</script>

<style scoped lang="scss">
.table-card {
  border: 1px solid var(--sm-border); border-radius: 8px; padding: 12px;
  margin-bottom: 12px; background: #fbfcfe;
}
.table-head {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;
  .table-title { font-weight: 700; font-size: 13px; color: var(--sm-primary-dark); }
}
.grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 8px; }
.rowfilter { width: 100%; margin-bottom: 8px; }
.col-row { display: flex; gap: 8px; margin-bottom: 6px; }
.actions { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.dirty { color: var(--sm-muted); font-size: 12px; }
</style>
