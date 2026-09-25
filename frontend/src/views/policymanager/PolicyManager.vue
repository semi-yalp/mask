<template>
  <div v-if="inst">
    <div class="page-topnav">
      <span class="topnav-title">{{ inst.dialect.toUpperCase() }} Policies</span>
      <el-select :model-value="inst.name" size="small" filterable style="width: 220px"
        @update:model-value="switchInstance">
        <el-option v-for="i in store.list" :key="i.name" :value="i.name" :label="`Service : ${i.name}(${i.dialect})`" />
      </el-select>
      <span class="muted topnav-meta">{{ (inst.tables || []).length }} 张表</span>
      <span class="spacer" />
      <el-dropdown trigger="click" @command="onManage">
        <el-button size="small">
          管理实例<el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="import">从元数据服务导入</el-dropdown-item>
            <el-dropdown-item command="delete" divided>删除实例</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button size="small" type="success" class="add-policy" @click="addPolicy">Add New Policy</el-button>
    </div>

    <div class="page">
      <div class="crumb">
        <a href="#/access-manager">访问管理</a><el-icon class="sep"><ArrowRight /></el-icon><span>{{ inst.name }}</span>
      </div>
      <ErrorAlert :error="loadError" />

      <el-card shadow="never" :body-style="{ padding: '4px 14px 14px' }">
        <el-tabs v-model="tab">
          <el-tab-pane label="策略" name="policies">
            <PoliciesTab ref="policiesRef" :instance="inst.name" />
          </el-tab-pane>
          <el-tab-pane label="资源(表结构)" name="tables">
            <TablesTab :inst="inst" @refresh="reload" />
          </el-tab-pane>
          <el-tab-pane label="脱敏函数(UDF)" name="udfs" lazy>
            <UdfsTab :instance="inst.name" />
          </el-tab-pane>
          <el-tab-pane label="生效配置" name="effective" lazy>
            <EffectiveTab :instance="inst.name" />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>

    <el-dialog v-model="importDialog" title="从元数据服务导入表结构" width="520px">
      <el-form label-width="150px" @submit.prevent>
        <el-form-item label="metadata 服务地址">
          <el-input v-model="impBase" placeholder="metadataBaseUrl,如 http://127.0.0.1:8082" />
        </el-form-item>
        <el-form-item label="metadata 实例名">
          <el-input v-model="impInstance" placeholder="如 crm" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
  <div v-else class="page">
    <ErrorAlert :error="loadError" />
    <EmptyHint v-if="!loadError">加载中…</EmptyHint>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowDown, ArrowRight } from "@element-plus/icons-vue";
import { deleteInstance, importMetadata } from "@/api/instances";
import { useInstancesStore } from "@/stores/instances";
import type { InstanceInfo } from "@/types/domain";
import ErrorAlert from "@/components/ErrorAlert.vue";
import EmptyHint from "@/components/EmptyHint.vue";
import TablesTab from "@/views/policymanager/TablesTab.vue";
import PoliciesTab from "@/views/policymanager/PoliciesTab.vue";
import UdfsTab from "@/views/policymanager/UdfsTab.vue";
import EffectiveTab from "@/views/policymanager/EffectiveTab.vue";

const props = defineProps<{ name: string }>();
const router = useRouter();
const store = useInstancesStore();

const inst = ref<InstanceInfo | null>(store.current?.name === props.name ? store.current : null);
const loadError = ref("");
const tab = ref("policies");
const policiesRef = ref<InstanceType<typeof PoliciesTab> | null>(null);

const importDialog = ref(false);
const importing = ref(false);
const impBase = ref("http://127.0.0.1:8082");
const impInstance = ref("");

async function reload() {
  loadError.value = "";
  try {
    inst.value = await store.select(props.name);
  } catch (e) {
    loadError.value = (e as Error).message;
    inst.value = null;
  }
}

onMounted(() => { reload(); if (!store.list.length) store.load(); });
watch(() => props.name, reload);

function switchInstance(name: string) {
  router.push({ name: "policy-manager", params: { name } }).catch(() => undefined);
}

function onManage(cmd: string) {
  if (cmd === "import") { impInstance.value = ""; importDialog.value = true; }
  else if (cmd === "delete") removeInst();
}

async function doImport() {
  if (!impInstance.value.trim()) { ElMessage.warning("metadata 实例名必填"); return; }
  importing.value = true;
  try {
    const res = await importMetadata(props.name, {
      metadataBaseUrl: impBase.value.trim() || "http://127.0.0.1:8082",
      metadataInstance: impInstance.value.trim(),
    });
    ElMessage.success(`导入成功:${(res.tables || []).length} 张表`);
    importDialog.value = false;
    await reload();
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { importing.value = false; }
}

async function removeInst() {
  try {
    await ElMessageBox.confirm(`删除实例 ${props.name} 及其全部策略/UDF?`, "删除确认", { type: "warning" });
  } catch { return; }
  try {
    await deleteInstance(props.name);
    store.forget(props.name);
    ElMessage.success("实例已删除");
    router.push({ name: "access-manager" }).catch(() => undefined);
  } catch (e) { ElMessage.error((e as Error).message); }
}

function addPolicy() {
  tab.value = "policies";
  policiesRef.value?.openCreate();
}
</script>

<style scoped lang="scss">
.topnav-meta { font-size: 12.5px; }
.add-policy {
  background: var(--sm-success); border-color: var(--sm-success); color: #fff;
  font-weight: 600;
  &:hover, &:focus { background: var(--sm-success-dark); border-color: var(--sm-success-dark); color: #fff; }
}
.crumb .sep { font-size: 11px; color: var(--sm-muted); }
</style>
