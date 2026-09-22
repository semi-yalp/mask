<template>
  <div class="page" v-if="inst">
    <div class="page-head">
      <el-page-header @back="router.push({ name: 'instances' })">
        <template #content>
          <span class="head-title">{{ inst.name }}</span>
          <el-tag size="small" effect="plain">{{ inst.dialect }}</el-tag>
          <el-tag size="small" type="info" effect="plain">{{ (inst.tables || []).length }} 张表</el-tag>
        </template>
        <template #extra>
          <el-button type="danger" plain size="small" @click="removeInst">删除实例</el-button>
        </template>
      </el-page-header>
    </div>

    <el-card shadow="never" class="card-block">
      <div class="import-row">
        <span class="import-title">跨服务元数据导入</span>
        <span class="muted">从 mask-metadata(8082)拉取表结构到本实例</span>
      </div>
      <div class="import-form">
        <el-input v-model="impBase" placeholder="metadataBaseUrl,如 http://127.0.0.1:8082" style="width: 260px" />
        <el-input v-model="impInstance" placeholder="metadata 实例名,如 crm" style="width: 200px" />
        <el-input v-model="impKey" placeholder="metadata API Key(可选)" style="width: 200px" />
        <el-button type="primary" plain @click="doImport">导入</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="表结构" name="tables">
          <TablesTab :inst="inst" @refresh="reload" />
        </el-tab-pane>
        <el-tab-pane label="策略" name="policies" lazy>
          <PoliciesTab :instance="inst.name" />
        </el-tab-pane>
        <el-tab-pane label="UDF" name="udfs" lazy>
          <UdfsTab :instance="inst.name" />
        </el-tab-pane>
        <el-tab-pane label="生效配置" name="effective" lazy>
          <EffectiveTab :instance="inst.name" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
  <div class="page" v-else>
    <ErrorAlert :error="loadError" />
    <EmptyHint v-if="!loadError">加载中…</EmptyHint>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { deleteInstance, importMetadata } from "@/api/instances";
import { useInstancesStore } from "@/stores/instances";
import type { InstanceInfo } from "@/types/domain";
import ErrorAlert from "@/components/ErrorAlert.vue";
import EmptyHint from "@/components/EmptyHint.vue";
import TablesTab from "@/views/instances/TablesTab.vue";
import PoliciesTab from "@/views/instances/PoliciesTab.vue";
import UdfsTab from "@/views/instances/UdfsTab.vue";
import EffectiveTab from "@/views/instances/EffectiveTab.vue";

const props = defineProps<{ name: string }>();
const router = useRouter();
const store = useInstancesStore();

const inst = ref<InstanceInfo | null>(store.current?.name === props.name ? store.current : null);
const loadError = ref("");
const tab = ref("tables");

const impBase = ref("http://127.0.0.1:8082");
const impInstance = ref("");
const impKey = ref("");

async function reload() {
  loadError.value = "";
  try {
    inst.value = await store.select(props.name);
  } catch (e) {
    loadError.value = (e as Error).message;
    inst.value = null;
  }
}

onMounted(reload);
watch(() => props.name, reload);

async function doImport() {
  if (!impInstance.value.trim()) { ElMessage.warning("metadata 实例名必填"); return; }
  try {
    const res = await importMetadata(props.name, {
      metadataBaseUrl: impBase.value.trim() || "http://127.0.0.1:8082",
      metadataInstance: impInstance.value.trim(),
      metadataApiKey: impKey.value.trim() || null
    });
    ElMessage.success(`导入成功:${(res.tables || []).length} 张表`);
    await reload();
  } catch (e) { ElMessage.error((e as Error).message); }
}

async function removeInst() {
  try {
    await ElMessageBox.confirm(`删除实例 ${props.name} 及其全部策略/UDF?`, "删除确认", { type: "warning" });
  } catch { return; }
  try {
    await deleteInstance(props.name);
    store.forget(props.name);
    ElMessage.success("实例已删除");
    router.push({ name: "instances" });
  } catch (e) { ElMessage.error((e as Error).message); }
}
</script>

<style scoped lang="scss">
.page-head { margin-bottom: 14px; }
.head-title { font-size: 16px; font-weight: 700; margin-right: 8px; }
.import-row { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px;
  .import-title { font-weight: 700; font-size: 13.5px; color: var(--sm-primary-dark); }
}
.import-form { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
