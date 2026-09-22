<template>
  <div class="page">
    <div class="page-head">
      <h2>实例管理</h2>
      <span class="muted">策略服务的脱敏实例:表结构 / 策略 / UDF / 生效配置</span>
    </div>

    <el-card shadow="never" class="card-block">
      <div class="create-row">
        <el-input v-model="newName" placeholder="实例名,如 crm" style="width: 220px" @keyup.enter="create" />
        <el-select v-model="newDialect" style="width: 150px">
          <el-option v-for="d in dialects" :key="d" :value="d" :label="d" />
        </el-select>
        <el-button type="primary" @click="create">新建实例</el-button>
        <el-divider direction="vertical" />
        <el-button @click="createSample">载入示例实例</el-button>
        <span class="muted">创建 crm(customer 表 + mask_phone UDF + 脱敏策略)</span>
      </div>
    </el-card>

    <ErrorAlert :error="store.loadError" />

    <el-alert v-if="error" type="error" :closable="false" show-icon class="card-block" :title="error" />

    <div v-if="store.list.length" class="inst-grid">
      <el-card v-for="inst in store.list" :key="inst.name" shadow="hover" class="inst-card"
        :class="{ current: store.current?.name === inst.name }" @click="open(inst.name)">
        <div class="inst-head">
          <span class="inst-name">{{ inst.name }}</span>
          <el-tag size="small" effect="plain">{{ inst.dialect }}</el-tag>
        </div>
        <div class="inst-meta">
          <span>{{ (inst.tables || []).length }} 张表</span>
          <el-button size="small" text type="danger" @click.stop="remove(inst.name)">删除</el-button>
        </div>
      </el-card>
    </div>
    <EmptyHint v-else-if="!store.loading && !store.loadError">暂无实例,输入名称点「新建实例」,或载入示例实例</EmptyHint>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { createInstance, deleteInstance } from "@/api/instances";
import { createPolicy } from "@/api/policies";
import { createUdf } from "@/api/udfs";
import { useInstancesStore } from "@/stores/instances";
import ErrorAlert from "@/components/ErrorAlert.vue";
import EmptyHint from "@/components/EmptyHint.vue";

const router = useRouter();
const store = useInstancesStore();
const dialects = ["postgresql", "trino", "mysql"];
const newName = ref("");
const newDialect = ref("postgresql");
const error = ref("");

onMounted(() => store.load());

async function create() {
  const name = newName.value.trim();
  if (!name) { ElMessage.warning("实例名不能为空"); return; }
  try {
    await createInstance(name, newDialect.value);
    newName.value = "";
    ElMessage.success(`实例 ${name} 已创建`);
    await store.load();
  } catch (e) { ElMessage.error((e as Error).message); }
}

async function remove(name: string) {
  try {
    await ElMessageBox.confirm(`删除实例 ${name} 及其全部策略/UDF?`, "删除确认", { type: "warning" });
  } catch { return; }
  try {
    await deleteInstance(name);
    store.forget(name);
    ElMessage.success("实例已删除");
  } catch (e) { ElMessage.error((e as Error).message); }
}

function open(name: string) {
  router.push({ name: "instance-detail", params: { name } });
}

async function createSample() {
  error.value = "";
  try {
    const name = "crm";
    if (!store.list.some((i) => i.name === name)) {
      await createInstance(name, "postgresql", [{
        catalog: "crm", schema: "public", name: "customer",
        columns: [
          { name: "id", type: "bigint" },
          { name: "phone", type: "varchar" },
          { name: "email", type: "varchar" },
          { name: "status", type: "varchar" }
        ]
      }]);
      await createUdf(name, {
        name: "mask_phone",
        signatures: [{ params: ["varchar", "integer", "integer"], returns: "varchar" }]
      });
      await createPolicy(name, {
        name: "mask_phone_policy", policyType: "datamask", isEnabled: true, priority: 1,
        resource: { catalog: "crm", schema: "public", table: "customer", columns: ["phone"] },
        subjects: { users: [], groups: [] }, udf: "mask_phone", arguments: [3, 4], filterExpr: null
      });
      ElMessage.success("示例实例 crm 已创建");
    } else {
      ElMessage.info("crm 已存在,无需重建");
    }
    await store.load();
    open(name);
  } catch (e) { error.value = (e as Error).message; }
}
</script>

<style scoped lang="scss">
.page-head {
  display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px;
  h2 { margin: 0; font-size: 18px; }
}
.create-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.inst-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px;
}
.inst-card {
  cursor: pointer; border-radius: 10px;
  :deep(.el-card__body) { padding: 14px 16px; }
  &:hover { border-color: var(--sm-primary); }
  &.current { border-color: var(--sm-primary); background: #eff6ff; }
}
.inst-head { display: flex; align-items: center; gap: 8px; }
.inst-name { font-weight: 700; font-size: 14.5px; flex: 1; }
.inst-meta {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8px; color: var(--sm-muted); font-size: 12.5px;
}
</style>
