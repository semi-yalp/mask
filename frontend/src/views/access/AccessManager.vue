<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">访问管理 · Access Manager</span>
      <span class="muted">按方言(服务类型)分组的脱敏实例,点击实例进入策略管理器</span>
      <span class="spacer" />
      <el-button size="small" @click="createSample">载入示例实例</el-button>
      <el-button size="small" type="primary" @click="openCreate(allDialects[0])">新建实例</el-button>
    </div>

    <div class="page">
      <div class="crumb"><a href="#/access-manager">访问管理</a></div>
      <ErrorAlert :error="store.loadError" />
      <el-alert v-if="error" type="error" :closable="false" show-icon class="card-block" :title="error" />

      <div class="svc-grid">
        <el-card v-for="g in groups" :key="g.dialect" shadow="never" class="svc-card" :body-style="{ padding: '0' }">
          <div class="svc-head">
            <span class="svc-type">{{ g.dialect.toUpperCase() }}</span>
            <span class="svc-sub">Service Manager</span>
            <span class="spacer" />
            <el-tooltip content="新建该方言的实例" placement="top">
              <el-button size="small" type="success" class="add-btn" @click="openCreate(g.dialect)">＋</el-button>
            </el-tooltip>
          </div>
          <div class="svc-body">
            <div v-for="inst in g.instances" :key="inst.name" class="svc-row" @click="open(inst.name)">
              <el-icon class="svc-icon"><Coin /></el-icon>
              <span class="svc-name">{{ inst.name }}</span>
              <span class="svc-meta">{{ (inst.tables || []).length }} 张表</span>
              <span class="svc-ops" @click.stop>
                <el-tooltip content="策略管理器" placement="top">
                  <el-icon @click="open(inst.name)"><Setting /></el-icon>
                </el-tooltip>
                <el-tooltip content="删除实例" placement="top">
                  <el-icon class="danger" @click="remove(inst.name)"><Delete /></el-icon>
                </el-tooltip>
              </span>
            </div>
            <div v-if="!g.instances.length" class="svc-empty muted">暂无实例,点 ＋ 创建</div>
          </div>
        </el-card>
      </div>
    </div>

    <el-dialog v-model="createDialog" :title="`新建 ${createDialect.toUpperCase()} 实例`" width="420px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="实例名">
          <el-input v-model="createName" placeholder="如 crm" @keyup.enter="doCreate" />
        </el-form-item>
        <el-form-item label="方言">
          <el-select v-model="createDialect" style="width: 100%">
            <el-option v-for="d in allDialects" :key="d" :value="d" :label="d" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Coin, Setting, Delete } from "@element-plus/icons-vue";
import { createInstance, deleteInstance } from "@/api/instances";
import { createPolicy } from "@/api/policies";
import { createUdf } from "@/api/udfs";
import { useInstancesStore } from "@/stores/instances";
import ErrorAlert from "@/components/ErrorAlert.vue";

const router = useRouter();
const store = useInstancesStore();
const allDialects = ["postgresql", "trino", "mysql"];
const error = ref("");

const createDialog = ref(false);
const createName = ref("");
const createDialect = ref(allDialects[0]);
const creating = ref(false);

const groups = computed(() => {
  // 全部已知方言常驻展示(空类型卡片即该方言的创建入口,与 Ranger Service Manager 一致);
  // 另补展示后端可能出现的新方言。
  const present = new Set(store.list.map((i) => i.dialect));
  const dialects = [...allDialects, ...[...present].filter((d) => !allDialects.includes(d))];
  return dialects.map((d) => ({ dialect: d, instances: store.list.filter((i) => i.dialect === d) }));
});

onMounted(() => store.load());

function open(name: string) {
  router.push({ name: "policy-manager", params: { name } }).catch(() => undefined);
}

function openCreate(dialect: string) {
  createName.value = "";
  createDialect.value = dialect;
  createDialog.value = true;
}

async function doCreate() {
  const name = createName.value.trim();
  if (!name) { ElMessage.warning("实例名不能为空"); return; }
  creating.value = true;
  try {
    await createInstance(name, createDialect.value);
    createDialog.value = false;
    ElMessage.success(`实例 ${name} 已创建`);
    await store.load();
    open(name);
  } catch (e) { ElMessage.error((e as Error).message); }
  finally { creating.value = false; }
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
        subjects: { users: ["*"], groups: [] }, udf: "mask_phone", arguments: [3, 4], filterExpr: null
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
.svc-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px;
}
.svc-card { border-radius: 8px; overflow: hidden; }
.svc-head {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  background: #eaf2f6; border-bottom: 1px solid var(--sm-border);
  .svc-type { font-weight: 700; font-size: 13.5px; color: var(--sm-primary-dark); letter-spacing: 0.6px; }
  .svc-sub { font-size: 11px; color: var(--sm-muted); letter-spacing: 0.8px; text-transform: uppercase; }
  .spacer { flex: 1; }
}
.svc-body { padding: 6px 8px; }
.svc-row {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; cursor: pointer;
  &:hover { background: #f0f7fa; .svc-ops { opacity: 1; } }
}
.svc-icon { color: var(--sm-primary); }
.svc-name { font-weight: 600; font-size: 13.5px; }
.svc-meta { color: var(--sm-muted); font-size: 12px; }
.svc-ops {
  margin-left: auto; display: flex; gap: 12px; opacity: 0; transition: opacity 0.12s;
  .el-icon { color: var(--sm-muted); cursor: pointer; &:hover { color: var(--sm-primary); } &.danger:hover { color: var(--sm-danger); } }
}
.svc-empty { padding: 14px 12px; font-size: 12.5px; }
</style>
