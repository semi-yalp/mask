<template>
  <div class="page">
    <div class="page-head">
      <h2>总览</h2>
      <span class="muted">策略服务实例与脱敏资产概况</span>
    </div>

    <ErrorAlert :error="store.loadError" />

    <el-row :gutter="14" class="card-block">
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-num">{{ store.list.length }}</div>
          <div class="stat-label">实例</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-num">{{ totalTables }}</div>
          <div class="stat-label">表结构</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="stat-tags">
            <el-tag v-for="(n, d) in dialectCount" :key="d" effect="plain" size="large">{{ d }} · {{ n }}</el-tag>
          </div>
          <div class="stat-label">方言分布</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <el-col :span="12">
        <el-card shadow="never" header="快捷入口">
          <div class="quick">
            <router-link to="/instances" class="quick-item">
              <b>实例管理</b><span class="muted">表结构、策略、UDF 与生效配置</span>
            </router-link>
            <router-link to="/playground" class="quick-item">
              <b>改写试验台</b><span class="muted">提交 SQL,查看脱敏改写结果</span>
            </router-link>
            <router-link to="/audit" class="quick-item">
              <b>审计日志</b><span class="muted">按条件检索改写与管理面审计事件</span>
            </router-link>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" header="服务拓扑">
          <div class="topo">
            <div class="topo-row"><el-tag size="small">8080</el-tag><span>mask-core 改写服务(/api/rewrite、/api/audit)</span></div>
            <div class="topo-row"><el-tag size="small">8081</el-tag><span>mask-policy-server 策略服务(/api/instances、/api/effective)</span></div>
            <div class="topo-row"><el-tag size="small">8082</el-tag><span>mask-metadata 元数据服务(经策略服务导入)</span></div>
            <div class="topo-row"><el-tag size="small">8083</el-tag><span>mask-query 查询服务(/api/v1/query,预留)</span></div>
          </div>
          <div class="gate-line">
            门禁:<el-tag :type="settings.gateConfigured ? 'success' : 'warning'" size="small">
              {{ settings.gateConfigured ? "已配置 X-Api-Key 鉴权" : "未配置(开放)" }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useInstancesStore } from "@/stores/instances";
import { useSettingsStore } from "@/stores/settings";
import ErrorAlert from "@/components/ErrorAlert.vue";

const store = useInstancesStore();
const settings = useSettingsStore();

onMounted(() => store.load());

const totalTables = computed(() => store.list.reduce((sum, i) => sum + (i.tables || []).length, 0));
const dialectCount = computed(() => {
  const m: Record<string, number> = {};
  for (const i of store.list) m[i.dialect] = (m[i.dialect] || 0) + 1;
  return m;
});
</script>

<style scoped lang="scss">
.page-head {
  display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px;
  h2 { margin: 0; font-size: 18px; }
}
.stat { text-align: center; :deep(.el-card__body) { padding: 18px 12px; } }
.stat-num { font-size: 30px; font-weight: 700; color: var(--sm-primary); }
.stat-label { color: var(--sm-muted); font-size: 12.5px; margin-top: 4px; }
.stat-tags { display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; min-height: 34px; align-items: center; }
.quick { display: flex; flex-direction: column; gap: 10px; }
.quick-item {
  display: flex; flex-direction: column; gap: 2px; text-decoration: none; color: var(--sm-text);
  border: 1px solid var(--sm-border); border-radius: 8px; padding: 10px 12px;
  &:hover { border-color: var(--sm-primary); b { color: var(--sm-primary-dark); } }
}
.topo { display: flex; flex-direction: column; gap: 8px; }
.topo-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.gate-line { margin-top: 12px; font-size: 12.5px; display: flex; gap: 6px; align-items: center; }
</style>
