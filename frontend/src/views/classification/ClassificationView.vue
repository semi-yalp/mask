<template>
  <div class="page">
    <div class="crumb">数据资产 / 分类分级</div>
    <h2 class="page-title">分类分级</h2>
    <p class="muted mb">
      基于列名的启发式自动识别 + 手工修正,分类(身份/个人信息/联系方式/财务/位置/医疗/其他)
      与级别(高/中/低)沉淀在元数据层,可直接用于脱敏策略选列与风控敏感资产联动。
    </p>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="mb" />

    <div class="stat-row mb">
      <el-card shadow="never" class="stat">
        <div class="stat-num">{{ overview?.totalClassified ?? "—" }}</div>
        <div class="stat-label">已分类列</div>
      </el-card>
      <el-card shadow="never" class="stat">
        <div class="stat-num high">{{ overview?.high ?? "—" }}</div>
        <div class="stat-label">高敏感</div>
      </el-card>
      <el-card shadow="never" class="stat">
        <div class="stat-num medium">{{ overview?.medium ?? "—" }}</div>
        <div class="stat-label">中敏感</div>
      </el-card>
      <el-card shadow="never" class="stat">
        <div class="stat-num low">{{ overview?.low ?? "—" }}</div>
        <div class="stat-label">低敏感</div>
      </el-card>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>按实例分布</template>
      <el-table :data="overview?.instances ?? []" size="small" border v-loading="loadingOverview">
        <el-table-column prop="instance" label="实例" min-width="140" />
        <el-table-column prop="columns" label="总列数" width="90" />
        <el-table-column prop="classified" label="已分类" width="90" />
        <el-table-column label="级别分布" min-width="200">
          <template #default="{ row }">
            <el-tag type="danger" size="small" class="mr">高 {{ row.high }}</el-tag>
            <el-tag type="warning" size="small" class="mr">中 {{ row.medium }}</el-tag>
            <el-tag type="info" size="small">低 {{ row.low }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" @click="openInstance(row.instance)">明细</el-button>
            <el-button size="small" type="primary" :loading="autoRunning === row.instance"
              @click="runAuto(row.instance)">自动识别</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" :title="`分类明细:${current}`" size="640px">
      <div class="toolbar mb">
        <el-input v-model="keyword" placeholder="按列标识过滤" clearable class="kw" />
        <el-button type="primary" @click="openEdit()">新增/修正</el-button>
      </div>
      <el-table :data="filteredRows" size="small" border v-loading="loadingRows">
        <el-table-column prop="columnKey" label="列标识" min-width="220" show-overflow-tooltip />
        <el-table-column prop="category" label="类别" width="110">
          <template #default="{ row }"><el-tag size="small">{{ row.category }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="level" label="级别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="levelType(row.level)">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="80" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="editDialog" title="分类分级" width="460px">
        <el-form label-width="80px">
          <el-form-item label="列标识">
            <el-input v-model="editForm.columnKey" placeholder="catalog.schema.table.column"
              :disabled="editForm.existing" />
          </el-form-item>
          <el-form-item label="类别">
            <el-select v-model="editForm.category">
              <el-option v-for="c in CATEGORIES" :key="c" :value="c" :label="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="级别">
            <el-select v-model="editForm.level">
              <el-option v-for="l in LEVELS" :key="l" :value="l" :label="levelText(l)" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="editForm.note" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="editDialog = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </template>
      </el-dialog>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  autoClassify, classificationOverview, CATEGORIES, deleteClassification,
  listClassification, LEVELS, upsertClassification,
  type ClassificationOverview, type ClassificationRow
} from "@/api/classification";
import { listInstances } from "@/api/instances";

const overview = ref<ClassificationOverview | null>(null);
const loadingOverview = ref(false);
const error = ref("");

const drawer = ref(false);
const current = ref("");
const rows = ref<ClassificationRow[]>([]);
const loadingRows = ref(false);
const keyword = ref("");
const autoRunning = ref("");

const editDialog = ref(false);
const saving = ref(false);
const editForm = ref({ existing: false, columnKey: "", category: "PII", level: "MEDIUM", note: "" });

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows.value;
  return rows.value.filter((r) => r.columnKey.toLowerCase().includes(kw));
});

function levelType(level: string) {
  return level === "HIGH" ? "danger" : level === "MEDIUM" ? "warning" : "info";
}
function levelText(level: string) {
  return level === "HIGH" ? "高" : level === "MEDIUM" ? "中" : "低";
}

async function loadOverview() {
  loadingOverview.value = true;
  error.value = "";
  try {
    overview.value = await classificationOverview();
  } catch (e) {
    error.value = "总览加载失败:" + (e as Error).message;
  } finally {
    loadingOverview.value = false;
  }
}

async function openInstance(name: string) {
  current.value = name;
  drawer.value = true;
  loadingRows.value = true;
  try {
    rows.value = await listClassification(name);
  } catch (e) {
    ElMessage.error("明细加载失败:" + (e as Error).message);
  } finally {
    loadingRows.value = false;
  }
}

async function runAuto(instance: string) {
  autoRunning.value = instance;
  try {
    const r = await autoClassify(instance);
    ElMessage.success(`识别完成:扫描 ${r.columnsConsidered} 列,新增 ${r.created},已有 ${r.alreadyClassified}`);
    await loadOverview();
    if (current.value === instance) await openInstance(instance);
  } catch (e) {
    ElMessage.error("自动识别失败:" + (e as Error).message);
  } finally {
    autoRunning.value = "";
  }
}

function openEdit(row?: ClassificationRow) {
  editForm.value = row
    ? { existing: true, columnKey: row.columnKey, category: row.category, level: row.level, note: row.note || "" }
    : { existing: false, columnKey: "", category: "PII", level: "MEDIUM", note: "" };
  editDialog.value = true;
}

async function save() {
  saving.value = true;
  try {
    await upsertClassification(current.value, {
      columnKey: editForm.value.columnKey,
      category: editForm.value.category,
      level: editForm.value.level,
      note: editForm.value.note
    });
    ElMessage.success("已保存");
    editDialog.value = false;
    await openInstance(current.value);
    await loadOverview();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function remove(row: ClassificationRow) {
  await ElMessageBox.confirm(`删除 ${row.columnKey} 的分类?`, "确认", { type: "warning" });
  await deleteClassification(current.value, row.columnKey);
  ElMessage.success("已删除");
  await openInstance(current.value);
  await loadOverview();
}

onMounted(async () => {
  await loadOverview();
  // 无任何实例时给出可理解的空态提示
  try {
    await listInstances();
  } catch { /* 总览已展示错误 */ }
});
</script>

<style scoped lang="scss">
.page-title { margin: 4px 0 8px; }
.stat-row { display: flex; gap: 12px; flex-wrap: wrap; }
.stat { flex: 1; min-width: 140px; text-align: center; }
.stat-num { font-size: 26px; font-weight: 600; }
.stat-num.high { color: var(--el-color-danger); }
.stat-num.medium { color: var(--el-color-warning); }
.stat-num.low { color: var(--el-color-info); }
.stat-label { color: var(--el-text-color-secondary); font-size: 12px; }
.toolbar { display: flex; gap: 8px; }
.kw { max-width: 280px; }
.mr { margin-right: 6px; }
.mb { margin-bottom: 12px; }
</style>
