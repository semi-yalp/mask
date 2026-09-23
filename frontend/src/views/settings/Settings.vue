<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">设置 · Settings</span>
      <span class="muted">访问凭据与服务拓扑</span>
    </div>

    <div class="page">
      <div class="crumb"><a href="#/settings">设置</a></div>

      <el-row :gutter="14">
        <el-col :span="12">
          <el-card shadow="never" class="card-block">
            <template #header>
              <div class="card-title">
                <el-icon><Key /></el-icon>API Key(门禁)
                <span class="spacer" />
                <el-tag size="small" :type="settings.gateConfigured ? 'success' : 'warning'">
                  {{ settings.gateConfigured ? "已配置 X-Api-Key 鉴权" : "未配置(开放)" }}
                </el-tag>
              </div>
            </template>
            <el-form label-width="110px" @submit.prevent>
              <el-form-item label="管理 Key">
                <el-input v-model="adminKeyDraft" type="password" show-password
                  placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
              </el-form-item>
              <el-form-item label="数据 Key">
                <el-input v-model="dataKeyDraft" type="password" show-password
                  placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
              </el-form-item>
            </el-form>
            <div class="key-actions">
              <el-button @click="clearKeys">清除</el-button>
              <el-button type="primary" @click="saveKeys">保存</el-button>
            </div>
            <p class="muted key-note">管理 Key 用于实例/策略/UDF 管理面(8081),数据 Key 用于生效配置查询;仅保存在浏览器 localStorage,请求时经 X-Api-Key 头携带。</p>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <div class="card-title"><el-icon><Connection /></el-icon>服务拓扑</div>
            </template>
            <div class="topo">
              <div class="topo-row"><el-tag size="small">8080</el-tag><span><b>mask-core</b> 改写服务(/api/rewrite、/api/audit)</span></div>
              <div class="topo-row"><el-tag size="small">8081</el-tag><span><b>mask-policy-server</b> 策略服务(/api/instances、/api/effective)</span></div>
              <div class="topo-row"><el-tag size="small">8082</el-tag><span><b>mask-metadata</b> 元数据服务(经策略服务导入)</span></div>
              <div class="topo-row"><el-tag size="small">8083</el-tag><span><b>mask-query</b> 查询服务(/api/v1/query,预留)</span></div>
            </div>
            <p class="muted key-note">前端 nginx 按路径前缀反代上述服务;/api/instances 与 /api/effective 指向 8081,其余 /api 指向 8080。</p>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { Key, Connection } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useSettingsStore } from "@/stores/settings";

const settings = useSettingsStore();
const adminKeyDraft = ref(settings.adminKey);
const dataKeyDraft = ref(settings.dataKey);

function saveKeys() {
  settings.setKeys(adminKeyDraft.value, dataKeyDraft.value);
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
.card-title {
  display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 13.5px;
  .spacer { flex: 1; }
}
.key-actions { display: flex; justify-content: flex-end; gap: 10px; }
.key-note { font-size: 12px; line-height: 1.7; margin: 10px 0 0; }
.topo { display: flex; flex-direction: column; gap: 10px; }
.topo-row {
  display: flex; align-items: center; gap: 10px; font-size: 12.5px;
  b { color: var(--sm-primary-dark); }
}
</style>
