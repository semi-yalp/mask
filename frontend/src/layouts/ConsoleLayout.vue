<template>
  <div class="console" @click="outsideClose">
    <aside class="sidebar">
      <div class="logo">
        <el-icon class="logo-icon"><Lock /></el-icon>
        <div class="logo-text">
          <div class="logo-title">sql-mask</div>
          <div class="logo-sub">Masking Console</div>
        </div>
      </div>
      <nav class="nav">
        <a class="nav-item" :class="{ active: route.name === 'dashboard' }" href="#/"
          @click.prevent.stop="go('/', 'dashboard')">
          <el-icon><Odometer /></el-icon><span>总览</span>
        </a>
        <a v-if="showAdmin" class="nav-item" id="nav-access" :class="{ active: isActive('access'), open: drawer === 'access' }"
          href="#/access-manager" @click.prevent.stop="toggleDrawer('access')">
          <el-icon><Coin /></el-icon><span>访问管理</span>
          <el-icon class="chev"><ArrowRight /></el-icon>
        </a>
        <a class="nav-item" :class="{ active: route.name === 'metadata-manager' }" href="#/metadata-manager"
          @click.prevent.stop="go('/metadata-manager', 'metadata-manager')">
          <el-icon><FolderOpened /></el-icon><span>元数据服务</span>
        </a>
        <a v-if="showAdmin" class="nav-item" :class="{ active: isActive('policy') }" href="#/access-manager"
          @click.prevent.stop="go('/access-manager', 'access')">
          <el-icon><Collection /></el-icon><span>策略管理器</span>
        </a>
        <a class="nav-item" :class="{ active: route.name === 'query-console' }" href="#/query-console"
          @click.prevent.stop="go('/query-console', 'query-console')">
          <el-icon><CaretRight /></el-icon><span>数据面查询</span>
        </a>
        <a class="nav-item" :class="{ active: route.name === 'playground' }" href="#/playground"
          @click.prevent.stop="go('/playground', 'playground')">
          <el-icon><EditPen /></el-icon><span>改写试验台</span>
        </a>
        <a v-if="showAudit" class="nav-item" id="nav-audit" :class="{ active: isActive('audit'), open: drawer === 'audit' }"
          href="#/audit" @click.prevent.stop="toggleDrawer('audit')">
          <el-icon><Document /></el-icon><span>审计</span>
          <el-icon class="chev"><ArrowRight /></el-icon>
        </a>
        <a class="nav-item" id="nav-risk" :class="{ active: isActive('risk'), open: drawer === 'risk' }"
          href="#/risk/dashboard" @click.prevent.stop="toggleDrawer('risk')">
          <el-icon><Warning /></el-icon><span>风险监控</span>
          <el-icon class="chev"><ArrowRight /></el-icon>
        </a>
        <a v-if="showAdmin" class="nav-item" id="nav-settings" :class="{ active: route.name === 'settings', open: drawer === 'settings' }"
          href="#/settings" @click.prevent.stop="toggleDrawer('settings')">
          <el-icon><Setting /></el-icon><span>设置</span>
          <el-icon class="chev"><ArrowRight /></el-icon>
        </a>
      </nav>
      <div class="sidebar-foot">
        <div class="gate" :class="{ open: !settings.gateConfigured && !auth.isLoggedIn }">
          {{ auth.isLoggedIn ? "认证:LDAP 已登录" : settings.gateConfigured ? "门禁:已配置鉴权" : "门禁:未配置(开放)" }}
        </div>
        <div v-if="auth.isLoggedIn && auth.user" class="whoami">
          <div class="whoami-name" :title="auth.user.displayName">{{ auth.user.displayName || auth.user.username }}</div>
          <div class="whoami-meta">
            <span class="role-badge" :class="'role-' + (auth.role || 'USER').toLowerCase()">{{ auth.role }}</span>
            <span class="whoami-user">{{ auth.user.username }}</span>
          </div>
          <a class="logout-link" href="#/login" @click.prevent.stop="logout">退出登录</a>
        </div>
        <a v-else class="foot-link" href="#/settings" @click.prevent.stop="openKeyDialog">API Key 设置</a>
      </div>
    </aside>

    <!-- Ranger 式飞出抽屉:访问管理(资源策略)/ 审计 / 设置 -->
    <div v-if="drawer" class="flyout" @click.stop>
      <template v-if="drawer === 'access'">
        <div class="flyout-title">
          <span>RESOURCE POLICIES</span>
          <el-icon class="flyout-close" @click="drawer = null"><Close /></el-icon>
        </div>
        <div class="flyout-filter">
          <el-select v-model="dialectFilter" multiple collapse-tags clearable placeholder="选择方言(服务类型)"
            size="small" style="width: 100%">
            <el-option v-for="d in dialects" :key="d" :value="d" :label="d.toUpperCase()" />
          </el-select>
        </div>
        <div class="flyout-scroll">
          <div v-for="group in groupedInstances" :key="group.dialect" class="flyout-group">
            <div class="flyout-group-title">{{ group.dialect.toUpperCase() }}</div>
            <a v-for="inst in group.instances" :key="inst.name" class="flyout-item"
              :class="{ current: inst.name === currentInstance }"
              @click="goInstance(inst.name)">
              <el-icon><Coin /></el-icon><span>{{ inst.name }}</span>
              <span class="flyout-meta">{{ (inst.tables || []).length }} 表</span>
            </a>
            <div v-if="!group.instances.length" class="flyout-empty">暂无实例</div>
          </div>
          <a class="flyout-item more" @click="go('/access-manager', 'access')">
            <el-icon><Grid /></el-icon><span>访问管理主页(全部实例)</span>
          </a>
        </div>
      </template>

      <template v-else-if="drawer === 'audit'">
        <div class="flyout-title">
          <span>AUDITS</span>
          <el-icon class="flyout-close" @click="drawer = null"><Close /></el-icon>
        </div>
        <div class="flyout-scroll">
          <a class="flyout-item" @click="goAudit('')">全部事件</a>
          <a class="flyout-item" @click="goAudit('REWRITE')">访问审计(REWRITE)</a>
          <a class="flyout-item" @click="goAudit('QUERY')">查询执行(QUERY)</a>
          <a class="flyout-item" @click="goAudit('ADMIN_CHANGE')">管理审计(ADMIN_CHANGE)</a>
          <a class="flyout-item" @click="goAudit('EFFECTIVE_PULL')">生效拉取(EFFECTIVE_PULL)</a>
        </div>
      </template>

      <template v-else-if="drawer === 'risk'">
        <div class="flyout-title">
          <span>RISK MONITOR</span>
          <el-icon class="flyout-close" @click="drawer = null"><Close /></el-icon>
        </div>
        <div class="flyout-scroll">
          <a class="flyout-item" :class="{ current: route.name === 'risk-dashboard' }"
            @click="go('/risk/dashboard', 'risk-dashboard')">
            <el-icon><Odometer /></el-icon><span>风险大盘</span>
            <span class="flyout-meta">趋势 / Top 榜</span>
          </a>
          <a class="flyout-item" :class="{ current: route.name === 'risk-alerts' }"
            @click="go('/risk/alerts', 'risk-alerts')">
            <el-icon><Bell /></el-icon><span>告警中心</span>
            <span class="flyout-meta">处置闭环</span>
          </a>
          <a class="flyout-item" :class="{ current: route.name === 'risk-events' }"
            @click="go('/risk/events', 'risk-events')">
            <el-icon><Document /></el-icon><span>事件流</span>
            <span class="flyout-meta">证据明细</span>
          </a>
          <a class="flyout-item" :class="{ current: route.name === 'risk-rules' }"
            @click="go('/risk/rules', 'risk-rules')">
            <el-icon><Filter /></el-icon><span>检测规则</span>
            <span class="flyout-meta">内置 + 自定义</span>
          </a>
          <a class="flyout-item" :class="{ current: route.name === 'risk-assets' }"
            @click="go('/risk/assets', 'risk-assets')">
            <el-icon><Coin /></el-icon><span>敏感资产</span>
            <span class="flyout-meta">列级分级</span>
          </a>
        </div>
      </template>

      <template v-else-if="drawer === 'settings'">
        <div class="flyout-title">
          <span>SETTINGS</span>
          <el-icon class="flyout-close" @click="drawer = null"><Close /></el-icon>
        </div>
        <div class="flyout-scroll">
          <a class="flyout-item" @click="openKeyDialog()"><el-icon><Key /></el-icon><span>API Key 设置</span></a>
          <a class="flyout-item" @click="go('/settings', 'settings')"><el-icon><Connection /></el-icon><span>服务拓扑</span></a>
        </div>
      </template>
    </div>

    <div class="content" @click="drawer = null">
      <router-view />
    </div>

    <el-dialog v-model="keyDialog" title="API Key 设置" width="460px" append-to-body>
      <el-form label-width="110px">
        <el-form-item label="管理 Key">
          <el-input v-model="adminKeyDraft" type="password" show-password
            placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
        </el-form-item>
        <el-form-item label="数据 Key">
          <el-input v-model="dataKeyDraft" type="password" show-password
            placeholder="留空 = 未配置(门禁开放)" autocomplete="off" />
        </el-form-item>
        <el-form-item label="查询 Key">
          <el-input v-model="queryKeyDraft" type="password" show-password
            placeholder="mask-query 数据面(未配置即全 401)" autocomplete="off" />
        </el-form-item>
      </el-form>
      <p class="muted" style="margin:0 0 4px">管理 Key 用于实例/策略/UDF 管理面,数据 Key 用于生效配置查询,查询 Key 用于统一查询数据面;仅保存在浏览器 localStorage。</p>
      <template #footer>
        <el-button @click="clearKeys">清除</el-button>
        <el-button type="primary" @click="saveKeys">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Odometer, Coin, EditPen, Document, Setting, ArrowRight, Close, Grid, Key, Connection, Lock, FolderOpened, CaretRight, Warning, Bell, Filter } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useSettingsStore } from "@/stores/settings";
import { useInstancesStore } from "@/stores/instances";
import { POLICY_DIALECTS } from "@/constants";
import { UNAUTHORIZED_EVENT } from "@/api/http";
import { useAuthStore, SESSION_EXPIRED_EVENT } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const settings = useSettingsStore();
const instances = useInstancesStore();
const auth = useAuthStore();

// LDAP 模式下按角色显隐；API Key 模式（未登录）保持原样全显
const showAdmin = computed(() => !auth.isLoggedIn || auth.isAdmin);
const showAudit = computed(() => !auth.isLoggedIn || auth.isAuditorPlus);

const drawer = ref<"access" | "audit" | "risk" | "settings" | null>(null);
const dialectFilter = ref<string[]>([]);
const keyDialog = ref(false);
const adminKeyDraft = ref(settings.adminKey);
const dataKeyDraft = ref(settings.dataKey);
const queryKeyDraft = ref(settings.queryKey);

const dialects = [...POLICY_DIALECTS];

function onUnauthorized(e: Event) {
  const role = (e as CustomEvent<{ role?: string }>).detail?.role || "admin";
  const label = role === "query" ? "查询 Key" : role === "data" ? "数据 Key" : "管理 Key";
  ElMessage.warning(`请求被 401 拒绝:请检查设置中的「${label}」是否已配置/正确`);
}

const groupedInstances = computed(() => {
  const selected = dialectFilter.value;
  return dialects
    .filter((d) => !selected.length || selected.includes(d))
    .map((d) => ({ dialect: d, instances: instances.list.filter((i) => i.dialect === d) }));
});

const currentInstance = computed(() =>
  route.name === "policy-manager" ? String(route.params.name || "") : ""
);

function isActive(group: "access" | "policy" | "audit" | "risk"): boolean {
  if (group === "access") return route.name === "access-manager";
  if (group === "policy") return route.name === "policy-manager";
  if (group === "risk") return String(route.name || "").startsWith("risk-");
  return route.name === "audit";
}

function toggleDrawer(name: "access" | "audit" | "risk" | "settings") {
  drawer.value = drawer.value === name ? null : name;
}

function outsideClose() { drawer.value = null; }

function go(path: string, name: string) {
  drawer.value = null;
  if (route.path !== path || name === "access") router.push(path).catch(() => undefined);
}

function goInstance(name: string) {
  drawer.value = null;
  router.push({ name: "policy-manager", params: { name } }).catch(() => undefined);
}

function goAudit(eventType: string) {
  drawer.value = null;
  router.push({ name: "audit", query: eventType ? { eventType } : {} }).catch(() => undefined);
}

function openKeyDialog() {
  drawer.value = null;
  adminKeyDraft.value = settings.adminKey;
  dataKeyDraft.value = settings.dataKey;
  queryKeyDraft.value = settings.queryKey;
  keyDialog.value = true;
}

function saveKeys() {
  settings.setKeys(adminKeyDraft.value, dataKeyDraft.value, queryKeyDraft.value);
  keyDialog.value = false;
  ElMessage.success("API Key 已保存到本地");
}
function clearKeys() {
  adminKeyDraft.value = "";
  dataKeyDraft.value = "";
  queryKeyDraft.value = "";
  settings.setKeys("", "", "");
  ElMessage.info("API Key 已清除");
}

function logout() {
  drawer.value = null;
  auth.logout();
  router.push({ name: "login" }).catch(() => undefined);
}

function onSessionExpired() {
  if (route.name !== "login") {
    router.push({ name: "login", query: { redirect: route.fullPath } }).catch(() => undefined);
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") drawer.value = null;
}

onMounted(() => {
  instances.load();
  document.addEventListener("keydown", onKey);
  window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized as EventListener);
  window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
});
onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKey);
  window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized as EventListener);
  window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
});

watch(() => route.fullPath, () => { drawer.value = null; });
</script>

<style scoped lang="scss">
.console { display: flex; height: 100vh; position: relative; }

/* ---- Ranger 深蓝侧边栏 ---- */
.sidebar {
  width: 212px; flex: 0 0 212px; background: var(--sm-navy);
  display: flex; flex-direction: column; z-index: 30;
}
.logo {
  padding: 16px 16px 14px; background: var(--sm-navy-dark);
  display: flex; align-items: center; gap: 10px; border-bottom: 1px solid #1c4467;
}
.logo-icon { font-size: 24px; color: #35a7d3; }
.logo-title { color: #fff; font-size: 16px; font-weight: 700; letter-spacing: 0.5px; line-height: 1.2; }
.logo-sub { font-size: 10.5px; margin-top: 2px; color: #6d8ca6; letter-spacing: 1px; text-transform: uppercase; }

.nav { flex: 1; padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px;
  border-radius: 6px; border-left: 3px solid transparent; color: var(--sm-sidebar-text);
  text-decoration: none; font-size: 13.5px; cursor: pointer;
  transition: background 0.15s, color 0.15s;
  .chev { margin-left: auto; font-size: 11px; opacity: 0.55; }
  &:hover { background: var(--sm-navy-hover); color: var(--sm-sidebar-active); }
  &.active { background: var(--sm-navy-hover); color: var(--sm-sidebar-active); border-left-color: #35a7d3; }
  &.open { background: var(--sm-navy-hover); color: var(--sm-sidebar-active); }
}

.sidebar-foot { padding: 10px 12px 14px; border-top: 1px solid #1c4467; }
.gate {
  font-size: 11px; border: 1px dashed #46658a; border-radius: 999px;
  padding: 4px 8px; text-align: center; margin-bottom: 8px; color: #9fc0d8;
  &.open { color: #f0b849; border-color: #8a6a1f; }
}
.whoami {
  margin-bottom: 6px; padding: 8px 10px; border: 1px solid #1c4467; border-radius: 6px;
  background: rgba(10, 44, 71, 0.5);
}
.whoami-name {
  color: #fff; font-size: 13px; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.whoami-meta {
  display: flex; align-items: center; gap: 6px; margin-top: 4px;
  .whoami-user { color: #6d8ca6; font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; }
}
.role-badge {
  font-size: 10px; font-weight: 700; letter-spacing: 0.6px; border-radius: 3px;
  padding: 1px 5px; color: #0a2237;
  &.role-admin { background: #f0b849; }
  &.role-auditor { background: #7fd1a8; }
  &.role-user { background: #9fc0d8; }
}
.logout-link {
  display: block; margin-top: 6px; color: var(--sm-sidebar-text); font-size: 12px;
  text-decoration: none; text-align: center; padding: 3px 0; border-radius: 4px;
  &:hover { color: #ff9d9d; background: var(--sm-navy-hover); }
}
.foot-link {
  display: block; text-align: center; color: var(--sm-sidebar-text); font-size: 12px;
  text-decoration: none; padding: 4px 0; border-radius: 4px;
  &:hover { color: var(--sm-sidebar-active); background: var(--sm-navy-hover); }
}

/* ---- Ranger 飞出抽屉 ---- */
.flyout {
  position: fixed; left: 212px; top: 0; bottom: 0; width: 300px; z-index: 25;
  background: var(--sm-navy-dark); border-right: 1px solid #1c4467;
  box-shadow: 4px 0 14px rgba(0, 0, 0, 0.25);
  display: flex; flex-direction: column; animation: flyout-in 0.14s ease-out;
}
@keyframes flyout-in { from { transform: translateX(-12px); opacity: 0; } to { transform: none; opacity: 1; } }
.flyout-title {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 14px 10px; color: #fff; font-size: 12.5px; font-weight: 700; letter-spacing: 1.2px;
  border-bottom: 1px solid #1c4467;
}
.flyout-close { cursor: pointer; color: #6d8ca6; &:hover { color: #fff; } }
.flyout-filter { padding: 10px 12px 6px; border-bottom: 1px solid #16374f; }
.flyout-scroll { flex: 1; overflow-y: auto; padding: 6px 8px 14px; }
.flyout-group { margin-bottom: 8px; }
.flyout-group-title {
  color: #6d8ca6; font-size: 10.5px; letter-spacing: 1px; font-weight: 700;
  padding: 8px 8px 4px; text-transform: uppercase;
}
.flyout-item {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-radius: 4px;
  color: #cfe0ec; font-size: 13px; cursor: pointer; text-decoration: none;
  .flyout-meta { margin-left: auto; font-size: 11px; color: #6d8ca6; }
  &:hover { background: var(--sm-primary); color: #fff; .flyout-meta { color: #d7ecf3; } }
  &.current { background: #0a2c47; color: #fff; box-shadow: inset 3px 0 0 #35a7d3; }
  &.more { color: #8fb4cd; font-size: 12.5px; }
}
.flyout-empty { padding: 4px 10px 6px; color: #55708c; font-size: 12px; }

.content { flex: 1; overflow: auto; }
</style>
