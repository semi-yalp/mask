<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">敏感资产 · Sensitive Assets</span>
      <span class="muted">列级敏感度分级驱动行为规则(高频访问 / SELECT * / 脱敏旁路 / 首访基线)</span>
      <div class="topnav-actions">
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>&nbsp;登记敏感列
        </el-button>
        <el-button size="small" plain @click="load">
          <el-icon><Refresh /></el-icon>&nbsp;刷新
        </el-button>
      </div>
    </div>

    <div class="page">
      <ErrorAlert :error="error" />

      <el-card shadow="never" class="card-block">
        <div class="filters">
          <el-input v-model="keyword" placeholder="按列名过滤,如 customer 或 phone" clearable size="small"
            style="width: 300px" @keyup.enter="load" @clear="load" />
          <span class="muted" style="margin-left:auto">
            共 {{ columns.length }} 列(HIGH {{ countBy("high") }} / MEDIUM {{ countBy("medium") }} / LOW {{ countBy("low") }})
          </span>
        </div>

        <el-table :data="columns" v-loading="loading" size="default">
          <el-table-column label="列" min-width="300">
            <template #default="{ row }">
              <span class="mono">{{ row.columnKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="敏感级别" width="100">
            <template #default="{ row }">
              <el-tag :color="severityColor(row.sensitivity)" style="color:#fff;border:none">
                {{ severityLabel(row.sensitivity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类别" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="24h 访问" width="100" align="center">
            <template #default="{ row }"><b>{{ row.accesses24h }}</b> 次</template>
          </el-table-column>
          <el-table-column label="访问用户" width="96" align="center">
            <template #default="{ row }">{{ row.users24h }} 人</template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === 'MANUAL' ? 'primary' : 'info'" effect="plain">
                {{ row.source === "MANUAL" ? "手动" : "预置" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="参与检测" width="90" align="center">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="(v: string | number | boolean) => toggle(row, !!v)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-dialog v-model="dialog" :title="editing ? '编辑敏感列' : '登记敏感列'" width="480px">
        <el-form label-width="90px" label-position="left">
          <el-form-item label="列标识" required>
            <el-input v-model="form.columnKey" placeholder="catalog.schema.table.column,如 crm.public.customer.phone"
              :disabled="!!editing" class="mono" />
          </el-form-item>
          <el-form-item label="敏感级别">
            <el-select v-model="form.sensitivity" style="width: 100%">
              <el-option value="HIGH" label="HIGH(高危:身份/金融/生物特征)" />
              <el-option value="MEDIUM" label="MEDIUM(中危:联系方式/住址)" />
              <el-option value="LOW" label="LOW(低危:一般业务属性)" />
            </el-select>
          </el-form-item>
          <el-form-item label="类别">
            <el-select v-model="form.category" style="width: 100%">
              <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="参与检测">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialog = false">取消</el-button>
          <el-button type="primary" @click="save">保存</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import {
  fetchSensitiveColumns, createSensitiveColumn, updateSensitiveColumn, deleteSensitiveColumn,
  type SensitiveColumnVo
} from "@/api/risk";
import { severityColor, severityLabel } from "@/views/risk/risk-ui";

const columns = ref<SensitiveColumnVo[]>([]);
const loading = ref(false);
const error = ref("");
const keyword = ref("");
const dialog = ref(false);
const editing = ref<SensitiveColumnVo | null>(null);
const categories = ["IDENTITY", "PII", "CONTACT", "FINANCE", "LOCATION", "OTHER"];

const form = reactive({
  columnKey: "",
  sensitivity: "HIGH",
  category: "PII",
  enabled: true
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await fetchSensitiveColumns(keyword.value);
    columns.value = r.columns;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function countBy(sev: string): number {
  return columns.value.filter(c => c.sensitivity === sev).length;
}

function openCreate() {
  editing.value = null;
  form.columnKey = "";
  form.sensitivity = "HIGH";
  form.category = "PII";
  form.enabled = true;
  dialog.value = true;
}

function openEdit(row: SensitiveColumnVo) {
  editing.value = row;
  form.columnKey = row.columnKey;
  form.sensitivity = row.sensitivity.toUpperCase();
  form.category = row.category;
  form.enabled = row.enabled;
  dialog.value = true;
}

async function save() {
  if (!form.columnKey.trim()) {
    ElMessage.warning("请填写列标识(catalog.schema.table.column)");
    return;
  }
  try {
    if (editing.value) {
      await updateSensitiveColumn(editing.value.columnKey, {
        sensitivity: form.sensitivity, category: form.category, enabled: form.enabled
      });
      ElMessage.success("已更新");
    } else {
      await createSensitiveColumn({ ...form, columnKey: form.columnKey.trim() });
      ElMessage.success("已登记");
    }
    dialog.value = false;
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function toggle(row: SensitiveColumnVo, enabled: boolean) {
  try {
    await updateSensitiveColumn(row.columnKey, { enabled });
    row.enabled = enabled;
    ElMessage.success(`${row.columnKey} 已${enabled ? "参与" : "退出"}检测`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function remove(row: SensitiveColumnVo) {
  try {
    await ElMessageBox.confirm(`确定将「${row.columnKey}」移出敏感资产注册表?`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteSensitiveColumn(row.columnKey);
    ElMessage.success("已删除");
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<style scoped lang="scss">
.topnav-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12.5px; }
</style>
