<template>
  <div class="console">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-title">sql-mask</div>
        <div class="logo-sub">统一策略控制台</div>
      </div>
      <nav class="nav">
        <router-link to="/" class="nav-item" :class="{ active: route.name === 'dashboard' }">
          <el-icon><Odometer /></el-icon><span>总览</span>
        </router-link>
        <router-link to="/instances" class="nav-item" :class="{ active: String(route.name).startsWith('instance') }">
          <el-icon><Coin /></el-icon><span>实例管理</span>
        </router-link>
        <router-link to="/playground" class="nav-item" :class="{ active: route.name === 'playground' }">
          <el-icon><EditPen /></el-icon><span>改写试验台</span>
        </router-link>
        <router-link to="/audit" class="nav-item" :class="{ active: route.name === 'audit' }">
          <el-icon><Document /></el-icon><span>审计日志</span>
        </router-link>
      </nav>
      <div class="sidebar-foot">
        <div class="gate" :class="{ open: !settings.gateConfigured }">
          {{ settings.gateConfigured ? "门禁:已配置鉴权" : "门禁:未配置(开放)" }}
        </div>
        <el-button size="small" text class="key-btn" @click="keyDialog = true">API Key 设置</el-button>
      </div>
    </aside>

    <div class="content">
      <router-view />
    </div>

    <el-dialog v-model="keyDialog" title="API Key 设置" width="460px">
      <el-form label-width="110px">
        <el-form-item label="管理 Key">
          <el-input v-model="adminKeyDraft" type="password" show-password
            placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
        </el-form-item>
        <el-form-item label="数据 Key">
          <el-input v-model="dataKeyDraft" type="password" show-password
            placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
        </el-form-item>
      </el-form>
      <p class="muted" style="margin:0 0 4px">管理 Key 用于实例/策略/UDF 管理面,数据 Key 用于生效配置查询;仅保存在浏览器 localStorage。</p>
      <template #footer>
        <el-button @click="clearKeys">清除</el-button>
        <el-button type="primary" @click="saveKeys">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRoute } from "vue-router";
import { Odometer, Coin, EditPen, Document } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useSettingsStore } from "@/stores/settings";

const route = useRoute();
const settings = useSettingsStore();
const keyDialog = ref(false);
const adminKeyDraft = ref(settings.adminKey);
const dataKeyDraft = ref(settings.dataKey);

function saveKeys() {
  settings.setKeys(adminKeyDraft.value, dataKeyDraft.value);
  keyDialog.value = false;
  ElMessage.success("API Key 已保存到本地");
}
function clearKeys() {
  adminKeyDraft.value = "";
  dataKeyDraft.value = "";
  settings.setKeys("", "");
  ElMessage.info("API Key 已清除");
}
</script>

<style scoped lang="scss">
.console { display: flex; height: 100vh; }
.sidebar {
  width: 216px; flex: 0 0 216px; background: var(--sm-sidebar);
  display: flex; flex-direction: column; color: var(--sm-sidebar-text);
}
.logo { padding: 20px 18px 16px; border-bottom: 1px solid #1e293b; }
.logo-title { color: #f1f5f9; font-size: 18px; font-weight: 700; letter-spacing: 0.5px; }
.logo-sub { font-size: 12px; margin-top: 3px; color: #64748b; }
.nav { flex: 1; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: 8px; color: var(--sm-sidebar-text); text-decoration: none; font-size: 13.5px;
  transition: background 0.15s, color 0.15s;
  &:hover { background: #16223a; color: var(--sm-sidebar-active); }
  &.active { background: var(--sm-primary); color: #fff; }
}
.sidebar-foot { padding: 12px 14px 16px; border-top: 1px solid #1e293b; }
.gate {
  font-size: 11.5px; border: 1px dashed #475569; border-radius: 999px;
  padding: 4px 10px; text-align: center; margin-bottom: 8px;
  &.open { color: #fbbf24; border-color: #92610e; }
}
.key-btn { width: 100%; color: var(--sm-sidebar-text); &:hover { color: var(--sm-sidebar-active); } }
.content { flex: 1; overflow: auto; }
</style>
