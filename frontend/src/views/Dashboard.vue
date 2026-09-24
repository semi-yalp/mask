<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">总览 · Get Started</span>
      <span class="muted">sql-mask 脱敏策略控制台</span>
    </div>

    <div class="page">
      <ErrorAlert :error="store.loadError" />

      <div class="hero card-block">
        <div class="hero-text">
          <div class="hero-title">统一脱敏策略中心</div>
          <div class="hero-sub muted">
            按「方言 → 实例 → 策略」组织:在访问管理中创建实例,在策略管理器中维护列脱敏与行过滤策略,
            经改写试验台验证,由审计追溯全部改写与管理事件。
          </div>
        </div>
        <div class="hero-actions">
          <el-button type="primary" @click="$router.push({ name: 'access-manager' })">进入访问管理</el-button>
          <el-button @click="$router.push({ name: 'playground' })">改写试验台</el-button>
        </div>
      </div>

      <el-row :gutter="14" class="card-block">
        <el-col :span="8">
          <el-card shadow="never" class="stat">
            <div class="stat-num">{{ store.list.length }}</div>
            <div class="stat-label">脱敏实例</div>
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
        <el-col :span="14">
          <el-card shadow="never" header="快速开始">
            <div class="quick">
              <router-link :to="{ name: 'access-manager' }" class="quick-item">
                <div class="quick-head"><el-icon><Coin /></el-icon><b>创建第一个实例</b></div>
                <span class="muted">按方言(postgresql / trino / mysql / hive / sparksql)创建实例,可一键载入示例 crm</span>
              </router-link>
              <router-link :to="{ name: 'metadata-manager' }" class="quick-item">
                <div class="quick-head"><el-icon><FolderOpened /></el-icon><b>登记数据源</b></div>
                <span class="muted">元数据服务登记引擎连接,在线采集表结构或 YAML 导入</span>
              </router-link>
              <router-link :to="{ name: 'playground' }" class="quick-item">
                <div class="quick-head"><el-icon><EditPen /></el-icon><b>验证改写结果</b></div>
                <span class="muted">试验台提交 SQL,查看「原始查询内层 + 外层脱敏 UDF」的改写产物</span>
              </router-link>
              <router-link :to="{ name: 'query-console' }" class="quick-item">
                <div class="quick-head"><el-icon><CaretRight /></el-icon><b>受控查询数据</b></div>
                <span class="muted">数据面查询:改写不可绕过,只返回脱敏后的结果集</span>
              </router-link>
              <router-link :to="{ name: 'audit' }" class="quick-item">
                <div class="quick-head"><el-icon><Document /></el-icon><b>检索审计事件</b></div>
                <span class="muted">按事件类型 / 主体 / 时间范围检索改写与管理面审计</span>
              </router-link>
              <router-link :to="{ name: 'settings' }" class="quick-item">
                <div class="quick-head"><el-icon><Key /></el-icon><b>配置 API Key</b></div>
                <span class="muted">管理 / 数据 / 查询三把 Key,对应各服务的 X-Api-Key 门禁</span>
              </router-link>
            </div>
          </el-card>
        </el-col>
        <el-col :span="10">
          <el-card shadow="never" header="近期实例">
            <div v-if="recent.length" class="recent">
              <div v-for="inst in recent" :key="inst.name" class="recent-row"
                @click="$router.push({ name: 'policy-manager', params: { name: inst.name } })">
                <el-icon class="recent-icon"><Coin /></el-icon>
                <span class="recent-name">{{ inst.name }}</span>
                <el-tag size="small" effect="plain">{{ inst.dialect }}</el-tag>
                <span class="muted recent-meta">{{ (inst.tables || []).length }} 表</span>
              </div>
            </div>
            <EmptyHint v-else>暂无实例,去访问管理创建</EmptyHint>
            <div class="gate-line">
              门禁:<el-tag :type="settings.gateConfigured ? 'success' : 'warning'" size="small">
                {{ settings.gateConfigured ? "已配置 X-Api-Key 鉴权" : "未配置(开放)" }}
              </el-tag>
              <router-link :to="{ name: 'settings' }" class="muted gate-link">前往设置</router-link>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from "vue";
import { Coin, EditPen, Document, FolderOpened, CaretRight, Key } from "@element-plus/icons-vue";
import { useInstancesStore } from "@/stores/instances";
import { useSettingsStore } from "@/stores/settings";
import ErrorAlert from "@/components/ErrorAlert.vue";
import EmptyHint from "@/components/EmptyHint.vue";

const store = useInstancesStore();
const settings = useSettingsStore();

onMounted(() => store.load());

const totalTables = computed(() => store.list.reduce((sum, i) => sum + (i.tables || []).length, 0));
const recent = computed(() => store.list.slice(0, 6));
const dialectCount = computed(() => {
  const m: Record<string, number> = {};
  for (const i of store.list) m[i.dialect] = (m[i.dialect] || 0) + 1;
  return m;
});
</script>

<style scoped lang="scss">
.hero {
  background: linear-gradient(90deg, var(--sm-navy) 0%, #194468 70%, #0b7fad 140%);
  border-radius: 10px; padding: 22px 24px; display: flex; align-items: center; gap: 20px; flex-wrap: wrap;
}
.hero-text { flex: 1; min-width: 300px; }
.hero-title { color: #fff; font-size: 19px; font-weight: 700; letter-spacing: 0.5px; }
.hero-sub { color: #b9d2e2; font-size: 12.5px; margin-top: 6px; line-height: 1.7; max-width: 720px; }
.hero-actions { display: flex; gap: 10px; }

.stat { text-align: center; :deep(.el-card__body) { padding: 18px 12px; } }
.stat-num { font-size: 30px; font-weight: 700; color: var(--sm-primary); }
.stat-label { color: var(--sm-muted); font-size: 12.5px; margin-top: 4px; }
.stat-tags { display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; min-height: 34px; align-items: center; }

.quick { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.quick-item {
  display: flex; flex-direction: column; gap: 4px; text-decoration: none; color: var(--sm-text);
  border: 1px solid var(--sm-border); border-radius: 8px; padding: 12px; font-size: 12.5px;
  &:hover { border-color: var(--sm-primary); .quick-head b { color: var(--sm-primary-dark); } }
}
.quick-head { display: flex; align-items: center; gap: 8px; font-size: 13.5px;
  .el-icon { color: var(--sm-primary); } }

.recent { display: flex; flex-direction: column; gap: 2px; }
.recent-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 6px; cursor: pointer;
  &:hover { background: #f0f7fa; }
}
.recent-icon { color: var(--sm-primary); }
.recent-name { font-weight: 600; flex: 1; }
.recent-meta { font-size: 12px; }
.gate-line { margin-top: 12px; font-size: 12.5px; display: flex; gap: 8px; align-items: center; }
.gate-link { margin-left: auto; text-decoration: none; &:hover { color: var(--sm-primary); } }
</style>
