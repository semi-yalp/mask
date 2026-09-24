<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">元数据服务 · Metadata Manager</span>
      <span class="muted">引擎实例登记、在线采集(只读系统目录)、YAML 导入与表结构版本</span>
      <span class="spacer" />
      <el-button size="small" @click="openImport">YAML 导入</el-button>
      <el-button size="small" type="primary" @click="openCreate">新建实例</el-button>
    </div>

    <div class="page">
      <div class="crumb"><a href="#/metadata-manager">元数据服务</a><el-icon class="sep"><ArrowRight /></el-icon><span>实例列表</span></div>
      <ErrorAlert :error="loadError" />

      <el-card shadow="never" :body-style="{ padding: '4px 14px 14px' }">
        <div class="toolbar">
          <el-input v-model="keyword" placeholder="按实例名 / 引擎过滤" clearable style="width: 240px" :prefix-icon="Search" />
          <span class="muted">采集仅支持 postgresql / mysql / trino;hive / sparksql 用 YAML 导入</span>
          <span class="spacer" />
          <el-button size="small" @click="load">刷新</el-button>
        </div>

        <el-table :data="filtered" v-loading="loading" stripe>
          <el-table-column label="实例名" min-width="140">
            <template #default="{ row }">
              <a class="inst-link" @click="openDetail(row.name)">{{ row.name }}</a>
            </template>
          </el-table-column>
          <el-table-column label="引擎" width="110">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.engine || row.dialect }}</el-tag></template>
          </el-table-column>
          <el-table-column label="结构版本" width="100" align="center">
            <template #default="{ row }">v{{ row.metadataVersion }}</template>
          </el-table-column>
          <el-table-column label="连接" min-width="200">
            <template #default="{ row }">
              <span v-if="row.connection" class="mono">{{ connText(row.connection) }}</span>
              <span v-else class="muted">无(仅导入,不可采集/执行)</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" align="center">
            <template #default="{ row }">
              <el-button size="small" text type="success" :loading="collecting === row.name" @click="collect(row)">采集</el-button>
              <el-button size="small" text type="primary" @click="openConnection(row)">连接</el-button>
              <el-button size="small" text @click="openDetail(row.name)">表结构</el-button>
              <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <EmptyHint v-if="!loading && !filtered.length">
          {{ loadError ? "加载失败" : (keyword ? "无匹配实例" : "暂无实例,点「新建实例」登记或「YAML 导入」") }}
        </EmptyHint>
      </el-card>
    </div>

    <!-- 新建实例 -->
    <el-dialog v-model="createDialog" title="新建元数据实例" width="560px" destroy-on-close>
      <el-form ref="createFormRef" :model="form" :rules="instanceRules" label-width="150px" @submit.prevent>
        <el-form-item label="实例名" prop="name">
          <el-input v-model="form.name" placeholder="如 pg_prod(小写字母/数字/连字符)" />
        </el-form-item>
        <el-form-item label="引擎" prop="engine">
          <el-select v-model="form.engine" style="width: 100%" @change="onEngineChange">
            <el-option v-for="e in METADATA_ENGINES" :key="e" :value="e" :label="e" />
          </el-select>
        </el-form-item>
        <el-form-item label="方言(改写目标)">
          <el-input :model-value="form.dialect" disabled />
        </el-form-item>
        <el-form-item label="登记连接信息">
          <el-switch v-model="withConnection" />
          <span class="muted form-hint">不勾选则先建实例,稍后导入 YAML;采集必须要有连接</span>
        </el-form-item>
        <template v-if="withConnection">
          <el-form-item label="主机" prop="host"><el-input v-model="form.host" placeholder="127.0.0.1" /></el-form-item>
          <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 160px" /></el-form-item>
          <el-form-item label="数据库 / Catalog" prop="database"><el-input v-model="form.database" /></el-form-item>
          <el-form-item label="用户" prop="dbUser"><el-input v-model="form.dbUser" /></el-form-item>
          <el-form-item label="密码环境变量" prop="passwordRef">
            <el-input v-model="form.passwordRef" placeholder="如 SQLMASK_DS_PG_PROD_PASSWORD" />
            <span class="muted form-hint">服务端只登记环境变量名,密码不落库、不进日志</span>
          </el-form-item>
          <el-form-item label="schema 过滤(可选)"><el-input v-model="form.schemasText" placeholder="逗号分隔,缺省全部非系统 schema" /></el-form-item>
          <el-form-item label="包含视图"><el-switch v-model="form.includeViews" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 连接信息编辑 -->
    <el-dialog v-model="connDialog" :title="`连接信息:${connTarget}`" width="520px" destroy-on-close>
      <el-form ref="connFormRef" :model="form" :rules="connRules" label-width="150px" @submit.prevent>
        <el-form-item label="主机" prop="host"><el-input v-model="form.host" /></el-form-item>
        <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 160px" /></el-form-item>
        <el-form-item label="数据库 / Catalog" prop="database"><el-input v-model="form.database" /></el-form-item>
        <el-form-item label="用户" prop="dbUser"><el-input v-model="form.dbUser" /></el-form-item>
        <el-form-item label="密码环境变量" prop="passwordRef"><el-input v-model="form.passwordRef" /></el-form-item>
        <el-form-item label="schema 过滤(可选)"><el-input v-model="form.schemasText" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="包含视图"><el-switch v-model="form.includeViews" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingConn" @click="saveConnection">保存</el-button>
      </template>
    </el-dialog>

    <!-- YAML 导入 -->
    <el-dialog v-model="importDialog" title="YAML 导入表结构" width="640px" destroy-on-close>
      <el-form ref="importFormRef" :model="form" :rules="importRules" label-width="120px" @submit.prevent>
        <el-form-item label="实例名" prop="name"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="方言" prop="engine">
          <el-select v-model="form.engine" style="width: 100%">
            <el-option v-for="e in METADATA_ENGINES" :key="e" :value="e" :label="e" />
          </el-select>
        </el-form-item>
        <el-form-item label="metadataYaml" prop="yamlText">
          <el-input v-model="form.yamlText" type="textarea" :rows="12" class="mono" placeholder="只吃 metadata.tables 段;含 rowFilter 会被拒绝" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 表结构详情抽屉 -->
    <el-drawer v-model="detailDrawer" :title="detail ? `表结构:${detail.name}(v${detail.metadataVersion})` : '表结构'" size="620px">
      <div v-loading="detailLoading">
        <el-alert v-if="detail && !detail.connection" type="info" :closable="false" show-icon class="mb"
          title="该实例无连接信息:不可在线采集,也不可在数据面执行查询" />
        <el-input v-model="detailKeyword" placeholder="按 catalog / schema / 表名过滤" clearable class="mb" />
        <el-collapse>
          <el-collapse-item v-for="t in detailTables" :key="t.catalog + '.' + t.schema + '.' + t.name"
            :title="`${t.catalog}.${t.schema}.${t.name}(${t.columns.length} 列)`">
            <el-table :data="t.columns" size="small" border>
              <el-table-column prop="name" label="列" min-width="140" />
              <el-table-column prop="type" label="类型" min-width="140" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <EmptyHint v-if="detail && !detailTables.length">无匹配表</EmptyHint>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { ArrowRight, Search } from "@element-plus/icons-vue";
import {
  collectMetaInstance, createMetaInstance, deleteMetaInstance, getMetaInstance,
  importMetaYaml, listMetaInstances, updateMetaConnection,
  type MetaConnection, type MetaInstanceDetail, type MetaInstanceSummary
} from "@/api/meta";
import { COLLECTABLE_ENGINES, ENGINE_DEFAULT_PORT, METADATA_ENGINES, NAME_PATTERN } from "@/constants";
import EmptyHint from "@/components/EmptyHint.vue";
import ErrorAlert from "@/components/ErrorAlert.vue";

const instances = ref<(MetaInstanceSummary & { connection?: MetaConnection | null })[]>([]);
const loading = ref(false);
const loadError = ref("");
const keyword = ref("");
const collecting = ref("");

const createDialog = ref(false);
const connDialog = ref(false);
const importDialog = ref(false);
const creating = ref(false);
const savingConn = ref(false);
const importing = ref(false);
const connTarget = ref("");

const detailDrawer = ref(false);
const detailLoading = ref(false);
const detail = ref<MetaInstanceDetail | null>(null);
const detailKeyword = ref("");

const createFormRef = ref<FormInstance>();
const connFormRef = ref<FormInstance>();
const importFormRef = ref<FormInstance>();

const form = reactive({
  name: "", engine: "postgresql", dialect: "postgresql",
  host: "127.0.0.1", port: 5432, database: "", dbUser: "", passwordRef: "",
  schemasText: "", includeViews: false, yamlText: ""
});
const withConnection = ref(true);

const nameRule = { pattern: NAME_PATTERN, message: "小写字母开头,仅小写字母/数字/连字符", trigger: "blur" as const };
const instanceRules: FormRules = {
  name: [{ required: true, message: "实例名必填", trigger: "blur" }, nameRule],
  host: [{ required: true, message: "主机必填", trigger: "blur" }],
  port: [{ required: true, message: "端口必填", trigger: "blur" }],
  database: [{ required: true, message: "数据库必填", trigger: "blur" }],
  dbUser: [{ required: true, message: "用户必填", trigger: "blur" }]
};
const connRules: FormRules = instanceRules;
const importRules: FormRules = {
  name: [{ required: true, message: "实例名必填", trigger: "blur" }, nameRule],
  engine: [{ required: true, message: "引擎必选", trigger: "change" }],
  yamlText: [
    { required: true, message: "YAML 必填", trigger: "blur" },
    { pattern: /tables:/, message: "缺少 tables: 段", trigger: "blur" }
  ]
};

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return instances.value;
  return instances.value.filter((i) => i.name.toLowerCase().includes(k) || (i.engine || "").toLowerCase().includes(k));
});

const detailTables = computed(() => {
  const d = detail.value;
  if (!d) return [];
  const k = detailKeyword.value.trim().toLowerCase();
  if (!k) return d.tables || [];
  return (d.tables || []).filter((t) => `${t.catalog}.${t.schema}.${t.name}`.toLowerCase().includes(k));
});

function dialectFor(engine: string): string {
  return engine === "starrocks" ? "mysql" : engine;
}

function onEngineChange(engine: string) {
  form.dialect = dialectFor(engine);
  form.port = ENGINE_DEFAULT_PORT[engine] ?? 5432;
}

async function load() {
  loading.value = true;
  loadError.value = "";
  try {
    const list = await listMetaInstances();
    const withConn = await Promise.all(list.map((s) =>
      getMetaInstance(s.name).then((d) => ({ ...s, connection: d.connection })).catch(() => ({ ...s, connection: undefined as unknown as MetaConnection }))));
    instances.value = withConn;
  } catch (e) {
    loadError.value = (e as Error).message;
  } finally { loading.value = false; }
}
onMounted(load);

function connText(c: MetaConnection): string {
  const schemaPart = c.schemas?.length ? ` [${c.schemas.join(",")}]` : "";
  return `${c.host}:${c.port}/${c.database}${schemaPart}`;
}

function openCreate() {
  form.name = ""; form.engine = "postgresql"; form.dialect = "postgresql";
  form.host = "127.0.0.1"; form.port = 5432; form.database = ""; form.dbUser = "";
  form.passwordRef = ""; form.schemasText = ""; form.includeViews = false;
  withConnection.value = true;
  createDialog.value = true;
}

function connectionFromForm(): MetaConnection | null {
  if (!withConnection.value) return null;
  const schemas = form.schemasText.split(",").map((s) => s.trim()).filter(Boolean);
  return {
    host: form.host.trim(), port: form.port, database: form.database.trim(),
    dbUser: form.dbUser.trim(), passwordRef: form.passwordRef.trim(),
    schemas: schemas.length ? schemas : null, includeViews: form.includeViews
  };
}

async function doCreate() {
  const ok = await createFormRef.value?.validate().then(() => true).catch(() => false);
  if (!ok) return;
  creating.value = true;
  try {
    await createMetaInstance({
      name: form.name.trim(), dialect: dialectFor(form.engine), engine: form.engine, connection: connectionFromForm()
    });
    ElMessage.success(`实例 ${form.name} 已创建`);
    createDialog.value = false;
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { creating.value = false; }
}

function openConnection(row: MetaInstanceSummary & { connection?: MetaConnection | null }) {
  connTarget.value = row.name;
  const c = row.connection;
  form.host = c?.host || "127.0.0.1";
  form.port = c?.port ?? ENGINE_DEFAULT_PORT[row.engine] ?? 5432;
  form.database = c?.database || "";
  form.dbUser = c?.dbUser || "";
  form.passwordRef = c?.passwordRef || "";
  form.schemasText = (c?.schemas || []).join(", ");
  form.includeViews = Boolean(c?.includeViews);
  connDialog.value = true;
}

async function saveConnection() {
  const ok = await connFormRef.value?.validate().then(() => true).catch(() => false);
  if (!ok) return;
  savingConn.value = true;
  const schemas = form.schemasText.split(",").map((s) => s.trim()).filter(Boolean);
  try {
    await updateMetaConnection(connTarget.value, {
      host: form.host.trim(), port: form.port, database: form.database.trim(),
      dbUser: form.dbUser.trim(), passwordRef: form.passwordRef.trim(),
      schemas: schemas.length ? schemas : null, includeViews: form.includeViews
    });
    ElMessage.success("连接信息已更新");
    connDialog.value = false;
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { savingConn.value = false; }
}

async function collect(row: MetaInstanceSummary & { connection?: MetaConnection | null }) {
  if (!row.connection) { ElMessage.warning("该实例无连接信息,无法在线采集;请先在「连接」里补齐"); return; }
  if (!COLLECTABLE_ENGINES.includes(row.engine as (typeof COLLECTABLE_ENGINES)[number])) {
    ElMessage.warning(`${row.engine} 不支持在线采集(执行优先不采集),请用 YAML 导入`);
    return;
  }
  collecting.value = row.name;
  try {
    const res = await collectMetaInstance(row.name);
    const warnSuffix = res.warnings.length ? `,警告 ${res.warnings.length} 条` : "";
    ElMessage.success(`采集完成:${res.tableCount} 张表 / ${res.columnCount} 列,版本 v${res.metadataVersion}${warnSuffix}`);
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { collecting.value = ""; }
}

function openImport() {
  form.name = ""; form.engine = "postgresql"; form.yamlText = "";
  importDialog.value = true;
}

async function doImport() {
  const ok = await importFormRef.value?.validate().then(() => true).catch(() => false);
  if (!ok) return;
  importing.value = true;
  try {
    const res = await importMetaYaml({
      name: form.name.trim(), dialect: dialectFor(form.engine), connection: null, metadataYaml: form.yamlText
    });
    ElMessage.success(`导入成功:${res.tableCount} 张表 / ${res.columnCount} 列,版本 v${res.metadataVersion}`);
    importDialog.value = false;
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { importing.value = false; }
}

async function openDetail(name: string) {
  detailDrawer.value = true;
  detailLoading.value = true;
  detailKeyword.value = "";
  try { detail.value = await getMetaInstance(name); }
  catch (e) { ElMessage.error((e as Error).message); detail.value = null; }
  finally { detailLoading.value = false; }
}

async function remove(row: MetaInstanceSummary) {
  try {
    await ElMessageBox.confirm(`删除元数据实例 ${row.name} 及其表结构(v${row.metadataVersion})?`, "删除确认", { type: "warning" });
  } catch { return; }
  try {
    await deleteMetaInstance(row.name);
    ElMessage.success("实例已删除");
    await load();
  } catch (e) { ElMessage.error((e as Error).message); }
}
</script>

<style scoped lang="scss">
.toolbar { display: flex; align-items: center; gap: 12px; margin: 10px 0 12px; .spacer { flex: 1; } }
.inst-link { color: var(--sm-primary-dark); font-weight: 600; cursor: pointer; &:hover { text-decoration: underline; } }
.form-hint { margin-left: 10px; font-size: 12px; }
.mb { margin-bottom: 12px; }
</style>
