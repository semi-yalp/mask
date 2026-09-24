<template>
  <div class="page">
    <div class="crumb">策略中心 / 统一授权</div>
    <h2 class="page-title">统一授权(元数据层)</h2>
    <p class="muted mb">
      在元数据层统一登记「主体 × 资源 × 权限」,一键编译为各引擎的 GRANT DDL:
      PostgreSQL / MySQL 可直接预览并执行;Hive / SparkSQL / Trino 先行预览
      (引擎授权模型各异,执行接线在路线图中)。
    </p>

    <el-card shadow="never" class="mb">
      <div class="toolbar">
        <el-select v-model="instance" placeholder="选择引擎实例" filterable class="inst">
          <el-option v-for="i in instances" :key="i.name" :value="i.name"
            :label="`${i.name}(${i.dialect})`" />
        </el-select>
        <el-button :disabled="!instance" @click="loadMatrix">刷新矩阵</el-button>
        <el-button type="primary" :disabled="!instance" @click="openCreate">新增授权</el-button>
      </div>
    </el-card>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="mb" />

    <el-card v-if="instance" shadow="never">
      <template #header>主体 × 授权矩阵{{ dialectTag }}</template>
      <el-table :data="matrix" size="small" border v-loading="loading">
        <el-table-column prop="principal" label="主体" min-width="140" />
        <el-table-column prop="principalType" label="类型" width="80" />
        <el-table-column label="授权明细" min-width="360">
          <template #default="{ row }">
            <el-tag v-for="(g, i) in row.grants" :key="i" size="small" class="mr">
              {{ g.privilege }} @ {{ g.resourceType }}:{{ g.resourceId }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" @click="preview(row)">DDL 预览</el-button>
            <el-button size="small" type="warning" :disabled="!executable" @click="apply(row)">
              应用到引擎
            </el-button>
            <el-button size="small" type="danger" @click="openManage(row)">管理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createDialog" title="新增授权" width="520px">
      <el-form label-width="90px">
        <el-form-item label="主体类型">
          <el-select v-model="form.principalType">
            <el-option value="USER" label="USER(用户)" />
            <el-option value="GROUP" label="GROUP(用户组)" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体">
          <el-input v-model="form.principal" placeholder="用户名或组名" />
        </el-form-item>
        <el-form-item label="资源层级">
          <el-select v-model="form.resourceType">
            <el-option v-for="rt in RESOURCE_TYPES" :key="rt" :value="rt" :label="rt" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源路径">
          <el-input v-model="form.resourceId"
            :placeholder="resourcePlaceholder" />
          <div class="muted small">{{ resourceHint }}</div>
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="form.privilege">
            <el-option v-for="p in PRIVILEGES" :key="p" :value="p" :label="p" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="previewDrawer" title="编译后的 GRANT DDL" size="560px">
      <CodeBlock v-for="(s, i) in previewStatements" :key="i" :code="s.sql" />
      <EmptyHint v-if="!previewStatements.length">该主体暂无授权</EmptyHint>
    </el-drawer>

    <el-drawer v-model="manageDrawer" :title="`授权明细:${managePrincipal}`" size="620px">
      <el-table :data="manageRows" size="small" border v-loading="loadingManage">
        <el-table-column prop="resourceType" label="层级" width="90" />
        <el-table-column prop="resourceId" label="资源" min-width="200" show-overflow-tooltip />
        <el-table-column prop="privilege" label="权限" width="90" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link size="small" type="danger"
              @click="removeGrant(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  applyGrants, createGrant, deleteGrant, grantsMatrix, listAllGrants, previewGrants,
  type CompiledStatement, type MatrixRow, type PrincipalType, type Privilege,
  type ResourceType
} from "@/api/grants";
import { listInstances } from "@/api/instances";
import type { InstanceInfo } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";
import EmptyHint from "@/components/EmptyHint.vue";

const RESOURCE_TYPES: ResourceType[] = ["CATALOG", "SCHEMA", "TABLE", "COLUMN"];
const PRIVILEGES: Privilege[] = ["SELECT", "INSERT", "UPDATE", "DELETE", "ALL"];

const instances = ref<InstanceInfo[]>([]);
const instance = ref("");
const matrix = ref<MatrixRow[]>([]);
const loading = ref(false);
const error = ref("");

const createDialog = ref(false);
const saving = ref(false);
const form = ref<{ principalType: PrincipalType; principal: string; resourceType: ResourceType;
  resourceId: string; privilege: Privilege }>({
  principalType: "USER", principal: "", resourceType: "TABLE", resourceId: "", privilege: "SELECT"
});

const previewDrawer = ref(false);
const previewStatements = ref<CompiledStatement[]>([]);
const manageDrawer = ref(false);
const managePrincipal = ref("");
const manageRows = ref<{ id: number; resourceType: string; resourceId: string; privilege: string }[]>([]);
const loadingManage = ref(false);

const dialect = computed(() => instances.value.find((i) => i.name === instance.value)?.dialect || "");
const dialectTag = computed(() => (dialect.value ? `(${dialect.value})` : ""));
const executable = computed(() => ["postgresql", "mysql"].includes(dialect.value));
const resourcePlaceholder = computed(() => ({
  CATALOG: "catalog", SCHEMA: "catalog.schema",
  TABLE: "catalog.schema.table", COLUMN: "catalog.schema.table.column"
}[form.value.resourceType]));
const resourceHint = computed(() =>
  `资源路径用点号分隔,${{ CATALOG: 1, SCHEMA: 2, TABLE: 3, COLUMN: 4 }[form.value.resourceType]} 段`);

async function loadMatrix() {
  if (!instance.value) return;
  loading.value = true;
  error.value = "";
  try {
    matrix.value = await grantsMatrix(instance.value);
  } catch (e) {
    error.value = "矩阵加载失败:" + (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  form.value = { principalType: "USER", principal: "", resourceType: "TABLE", resourceId: "", privilege: "SELECT" };
  createDialog.value = true;
}

async function save() {
  saving.value = true;
  try {
    await createGrant(instance.value, form.value);
    ElMessage.success("已保存");
    createDialog.value = false;
    await loadMatrix();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function preview(row: MatrixRow) {
  try {
    const r = await previewGrants(instance.value, row.principalType as PrincipalType, row.principal);
    previewStatements.value = r.statements;
    previewDrawer.value = true;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function apply(row: MatrixRow) {
  await ElMessageBox.confirm(
    `将把 ${row.principal} 的授权 DDL 执行到引擎 ${instance.value},继续?`,
    "应用到引擎", { type: "warning" });
  try {
    const r = await applyGrants(instance.value, row.principalType as PrincipalType, row.principal);
    ElMessage.success(`执行 ${r.executed.length} 条${r.failed.length ? `,失败 ${r.failed.length} 条` : ""}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function openManage(row: MatrixRow) {
  managePrincipal.value = `${row.principalType}:${row.principal}`;
  manageDrawer.value = true;
  loadingManage.value = true;
  try {
    manageRows.value = await listAllGrants(instance.value).then((all) =>
      all.filter((g) => g.principal === row.principal && g.principalType === row.principalType));
  } finally {
    loadingManage.value = false;
  }
}

async function removeGrant(row: { id: number }) {
  await ElMessageBox.confirm("删除这条授权?", "确认", { type: "warning" });
  await deleteGrant(instance.value, row.id);
  manageDrawer.value = false;
  await loadMatrix();
}

onMounted(async () => {
  try {
    instances.value = await listInstances();
  } catch (e) {
    error.value = "实例列表加载失败:" + (e as Error).message;
  }
});
</script>

<style scoped lang="scss">
.toolbar { display: flex; gap: 10px; flex-wrap: wrap; }
.inst { min-width: 240px; }
.mr { margin-right: 6px; margin-bottom: 4px; }
.mb { margin-bottom: 12px; }
.small { font-size: 12px; }
</style>
