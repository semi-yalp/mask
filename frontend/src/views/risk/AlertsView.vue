<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">告警中心 · Alerts</span>
      <span class="muted">按「规则 × 用户」分组收敛的风险告警与处置闭环</span>
      <div class="topnav-actions">
        <el-radio-group v-model="statusFilter" size="small" @change="resetAndLoad">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="OPEN">未处理</el-radio-button>
          <el-radio-button value="ACKNOWLEDGED">已确认</el-radio-button>
          <el-radio-button value="RESOLVED">已解决</el-radio-button>
        </el-radio-group>
        <el-badge :value="blocks.length" :hidden="!blocks.length" type="danger">
          <el-button size="small" type="danger" plain @click="openBlocks">
            <el-icon><Lock /></el-icon>&nbsp;阻断名单
          </el-button>
        </el-badge>
        <el-button size="small" plain @click="openNotifications">
          <el-icon><Bell /></el-icon>&nbsp;通知记录
        </el-button>
        <el-button size="small" type="primary" plain @click="load">
          <el-icon><Refresh /></el-icon>&nbsp;刷新
        </el-button>
      </div>
    </div>

    <div class="page">
      <ErrorAlert :error="error" />

      <el-card shadow="never" class="card-block">
        <div class="filters">
          <el-select v-model="severityFilter" placeholder="级别" clearable size="small" style="width: 110px" @change="resetAndLoad">
            <el-option v-for="s in ['critical', 'high', 'medium', 'low', 'info']" :key="s" :value="s" :label="severityLabel(s)" />
          </el-select>
          <el-select v-model="ruleFilter" placeholder="规则" clearable filterable size="small" style="width: 220px" @change="resetAndLoad">
            <el-option v-for="r in rules" :key="r.id" :value="r.id" :label="r.name" />
          </el-select>
          <el-input v-model="keyword" placeholder="搜索用户 / 标题 / SQL" clearable size="small" style="width: 240px"
            @keyup.enter="resetAndLoad" @clear="resetAndLoad" />
          <span class="muted" style="margin-left:auto">共 {{ total }} 条告警</span>
        </div>

        <el-table :data="alerts" v-loading="loading" size="default">
          <el-table-column label="级别" width="84">
            <template #default="{ row }">
              <el-tag :color="severityColor(row.severity)" style="color:#fff;border:none">
                {{ severityLabel(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="300" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="openDetail(row)">{{ row.title }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="user" label="用户" width="130">
            <template #default="{ row }">
              <span class="mono">{{ row.user }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="sourceIp" label="来源 IP" width="120">
            <template #default="{ row }"><span class="mono">{{ row.sourceIp || "-" }}</span></template>
          </el-table-column>
          <el-table-column label="命中" width="76" align="center">
            <template #default="{ row }"><b>{{ row.eventCount }}</b> 次</template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="statusElType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处置" width="86" align="center">
            <template #default="{ row }">
              <el-tag v-if="blockedUsers.has(row.user)" size="small" type="danger" effect="dark">已阻断</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近命中" width="150">
            <template #default="{ row }">{{ formatTime(row.lastHitAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'OPEN'" size="small" text type="warning" @click="transition(row, 'ACKNOWLEDGED')">
                确认
              </el-button>
              <el-button v-if="row.status !== 'RESOLVED'" size="small" text type="success" @click="transition(row, 'RESOLVED')">
                解决
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
            layout="prev, pager, next" @current-change="load" />
        </div>
      </el-card>

      <!-- 告警详情抽屉 -->
      <el-drawer v-model="drawer" :title="detail?.title || '告警详情'" size="560px">
        <template v-if="detail">
          <div class="d-section">
            <div class="d-kv">
              <span class="d-k">级别</span>
              <el-tag :color="severityColor(detail.severity)" style="color:#fff;border:none">
                {{ severityLabel(detail.severity) }}
              </el-tag>
              <span class="d-k" style="margin-left:16px">状态</span>
              <el-tag size="small" :type="statusElType(detail.status)" effect="plain">{{ statusLabel(detail.status) }}</el-tag>
            </div>
            <div class="d-kv"><span class="d-k">用户</span><span class="mono">{{ detail.user }}</span>
              <span class="d-k" style="margin-left:16px">IP</span><span class="mono">{{ detail.sourceIp || "-" }}</span></div>
            <div class="d-kv"><span class="d-k">规则</span>{{ detail.ruleName }} <span class="muted mono">({{ detail.ruleId }})</span></div>
            <div class="d-kv"><span class="d-k">命中</span>{{ detail.eventCount }} 次 · 首次 {{ formatTime(detail.createdAt) }} · 最近 {{ formatTime(detail.lastHitAt) }}</div>
            <p class="d-desc">{{ detail.description }}</p>
          </div>

          <div class="d-section">
            <div class="d-title">证据 SQL</div>
            <pre class="sql-block">{{ detail.sqlSnippet }}</pre>
          </div>

          <div class="d-section" v-if="detail.ackNote">
            <div class="d-title">处置备注</div>
            <p class="d-desc">{{ detail.ackNote }}</p>
          </div>

          <div class="d-section block-section" :class="{ active: isUserBlocked(detail.user) }">
            <div class="d-title">
              响应处置 · 一键阻断
              <el-tag v-if="isUserBlocked(detail.user)" size="small" type="danger" effect="dark">已阻断</el-tag>
              <span v-else class="muted block-hint">在 policy-server 为该用户下发 ROW_FILTER「1 = 0」,切断数据访问</span>
            </div>
            <div v-if="blockVerification && isUserBlocked(detail.user)" class="block-verify mono">{{ blockVerification }}</div>
            <div class="block-actions">
              <el-button v-if="!isUserBlocked(detail.user)" size="small" type="danger"
                :loading="blocking" @click="doBlock(detail)">
                阻断 {{ detail.user }}
              </el-button>
              <el-button v-else size="small" type="warning" plain
                :loading="blocking" @click="doUnblock(detail)">
                解除阻断
              </el-button>
            </div>
          </div>

          <div class="d-section">
            <div class="d-title">命中事件({{ detail.events?.length ?? 0 }})</div>
            <div v-for="ev in detail.events" :key="ev.id" class="ev-row"
              @click="$router.push({ name: 'risk-events', query: { keyword: ev.user } })">
              <span class="ev-score" :style="{ color: scoreColor(ev.riskScore) }">{{ ev.riskScore }}</span>
              <span class="mono ev-user">{{ ev.user }}</span>
              <span class="muted">{{ formatTime(ev.timestamp) }}</span>
              <pre class="ev-sql">{{ ev.sql }}</pre>
            </div>
            <div v-if="!detail.events?.length" class="muted">事件已随滚动窗口淘汰,仅保留计数。</div>
          </div>

          <div class="d-actions">
            <el-input v-model="note" placeholder="处置备注(可选)" size="small" style="flex:1" />
            <el-button v-if="detail.status === 'OPEN'" size="small" type="warning" @click="transition(detail, 'ACKNOWLEDGED', true)">确认</el-button>
            <el-button v-if="detail.status !== 'RESOLVED'" size="small" type="success" @click="transition(detail, 'RESOLVED', true)">解决</el-button>
            <el-button v-if="detail.status === 'ACKNOWLEDGED'" size="small" @click="transition(detail, 'OPEN', true)">重新打开</el-button>
          </div>
        </template>
      </el-drawer>

      <!-- 通知记录抽屉 -->
      <el-drawer v-model="notifDrawer" title="通知记录 · Notifications" size="480px">
        <div class="notif-head">
          <el-tag :type="notifWebhook ? 'success' : 'info'" effect="plain" size="small">
            {{ notifWebhook ? "webhook 已配置,实时投递" : "未配置 webhook(仅本地记录)" }}
          </el-tag>
          <el-button size="small" text type="primary" @click="openNotifications">刷新</el-button>
        </div>
        <div v-if="notifications.length" class="notif-list">
          <div v-for="n in notifications" :key="n.id" class="notif-row">
            <el-tag size="small" :color="severityColor(n.severity)" style="color:#fff;border:none">
              {{ severityLabel(n.severity) }}
            </el-tag>
            <div class="notif-body">
              <div class="notif-title">{{ n.title }}</div>
              <div class="muted notif-meta">
                {{ formatTime(n.createdAt) }} ·
                <el-tag size="small" effect="plain"
                  :type="n.delivery === 'SENT' ? 'success' : n.delivery === 'FAILED' ? 'danger' : 'info'">
                  {{ n.delivery === "SENT" ? "已投递" : n.delivery === "FAILED" ? "投递失败" : "本地记录" }}
                </el-tag>
                {{ n.detail }}
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无通知(新告警达到通知级别后出现在这里)" :image-size="70" />
      </el-drawer>

      <!-- 阻断名单 -->
      <el-dialog v-model="blocksDialog" title="阻断名单 · Active Blocks" width="640px">
        <p class="muted" style="margin-top:0">
          被阻断用户在目标实例上的所有查询将被注入 ROW_FILTER「1 = 0」——由 policy-server 编译进生效配置执行。
        </p>
        <el-table :data="blocks" size="small">
          <el-table-column prop="user" label="用户" width="150">
            <template #default="{ row }"><span class="mono">{{ row.user }}</span></template>
          </el-table-column>
          <el-table-column prop="instance" label="实例" width="100" />
          <el-table-column label="策略" width="80" align="center">
            <template #default="{ row }">{{ row.policyCount }} 条</template>
          </el-table-column>
          <el-table-column label="阻断时间" width="150">
            <template #default="{ row }">{{ formatTime(row.blockedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button size="small" text type="warning" @click="unblockFromList(row)">解除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!blocks.length" class="muted" style="padding:20px 0;text-align:center">当前没有阻断中的用户</div>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh, Bell, Lock } from "@element-plus/icons-vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import {
  fetchAlerts, fetchAlertDetail, updateAlertStatus, fetchRules,
  fetchNotifications, fetchBlocks, blockUser, unblockUser,
  type AlertVo, type RiskRuleVo, type NotificationVo, type BlockVo
} from "@/api/risk";
import { severityColor, severityLabel, statusLabel, statusElType, formatTime, scoreColor } from "@/views/risk/risk-ui";

const alerts = ref<AlertVo[]>([]);
const rules = ref<RiskRuleVo[]>([]);
const detail = ref<AlertVo | null>(null);
const drawer = ref(false);
const loading = ref(false);
const error = ref("");
const total = ref(0);
const page = ref(0);
const pageSize = 50;
const statusFilter = ref("");
const severityFilter = ref("");
const ruleFilter = ref("");
const keyword = ref("");
const note = ref("");

// 通知与阻断
const notifDrawer = ref(false);
const notifWebhook = ref(false);
const notifications = ref<NotificationVo[]>([]);
const blocksDialog = ref(false);
const blocks = ref<BlockVo[]>([]);
const blocking = ref(false);
const blockVerification = ref("");

const blockedUsers = computed(() => new Set(blocks.value.map(b => b.user)));

function isUserBlocked(user: string): boolean {
  return blockedUsers.value.has(user);
}

async function loadBlocks() {
  try {
    const r = await fetchBlocks();
    blocks.value = r.blocks;
  } catch {
    blocks.value = [];
  }
}

async function openNotifications() {
  notifDrawer.value = true;
  try {
    const r = await fetchNotifications(100);
    notifications.value = r.notifications;
    notifWebhook.value = r.webhookConfigured;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function openBlocks() {
  blocksDialog.value = true;
  loadBlocks();
}

async function doBlock(alert: AlertVo) {
  blocking.value = true;
  try {
    const r = await blockUser(alert.user, undefined, `告警 ${alert.id}`);
    blockVerification.value = r.verification || "";
    ElMessage.success(`已阻断 ${alert.user}:下发 ${r.policyCount} 条行过滤策略`);
    if (alert.status === "OPEN") {
      await updateAlertStatus(alert.id, "ACKNOWLEDGED",
        `已下发阻断策略(${r.policyCount} 条 ROW_FILTER)`).catch(() => undefined);
    }
    await Promise.all([loadBlocks(), load()]);
    if (detail.value?.id === alert.id) {
      detail.value = await fetchAlertDetail(alert.id).catch(() => detail.value!);
    }
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    blocking.value = false;
  }
}

async function doUnblock(alert: AlertVo) {
  blocking.value = true;
  try {
    const r = await unblockUser(alert.user);
    blockVerification.value = r.verification || "";
    ElMessage.success(`已解除 ${alert.user} 的阻断(移除 ${r.policyCount} 条策略)`);
    await loadBlocks();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    blocking.value = false;
  }
}

async function unblockFromList(row: BlockVo) {
  try {
    await ElMessageBox.confirm(`恢复用户「${row.user}」对实例 ${row.instance} 的访问?`, "解除阻断", { type: "warning" });
  } catch {
    return;
  }
  try {
    await unblockUser(row.user, row.instance);
    ElMessage.success(`已解除 ${row.user} 的阻断`);
    await loadBlocks();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const r = await fetchAlerts({
      status: statusFilter.value,
      severity: severityFilter.value,
      ruleId: ruleFilter.value,
      keyword: keyword.value,
      page: page.value,
      size: pageSize
    });
    alerts.value = r.alerts;
    total.value = r.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function resetAndLoad() {
  page.value = 0;
  load();
}

async function openDetail(row: AlertVo) {
  try {
    detail.value = await fetchAlertDetail(row.id);
    note.value = detail.value.ackNote || "";
    drawer.value = true;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function transition(alert: AlertVo, status: string, withNote = false) {
  try {
    await updateAlertStatus(alert.id, status, withNote ? note.value : undefined);
    ElMessage.success(`告警已${statusLabel(status)}`);
    if (detail.value?.id === alert.id) {
      detail.value = await fetchAlertDetail(alert.id);
    }
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(async () => {
  await load();
  try {
    rules.value = await fetchRules();
  } catch {
    rules.value = [];
  }
  loadBlocks();
});
</script>

<style scoped lang="scss">
.topnav-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12.5px; }

.d-section { margin-bottom: 18px; }
.d-kv { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.d-k { color: var(--sm-muted); min-width: 40px; }
.d-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.d-desc { font-size: 13px; line-height: 1.7; margin: 8px 0 0; }
.sql-block {
  background: var(--sm-code-bg); color: var(--sm-code-text);
  padding: 12px 14px; border-radius: 6px; font-size: 12px; line-height: 1.6;
  font-family: "JetBrains Mono", Consolas, monospace; white-space: pre-wrap; word-break: break-all;
  margin: 0;
}
.ev-row {
  border: 1px solid #e6eef2; border-radius: 6px; padding: 8px 10px; margin-bottom: 8px;
  cursor: pointer; &:hover { background: #f5fafc; }
}
.ev-score { font-weight: 700; margin-right: 10px; }
.ev-user { margin-right: 10px; }
.ev-sql {
  margin: 6px 0 0; font-size: 11.5px; color: var(--sm-muted);
  font-family: "JetBrains Mono", Consolas, monospace; white-space: pre-wrap; word-break: break-all;
  max-height: 66px; overflow: hidden;
}
.d-actions { display: flex; gap: 8px; align-items: center; }

.block-section {
  border: 1px solid #f1d7d7; border-radius: 6px; padding: 10px 12px; background: #fdf8f8;
  &.active { border-color: #f0b8b8; background: #fdf4f4; }
  .block-hint { font-weight: 400; font-size: 12px; margin-left: 8px; }
  .block-verify {
    font-size: 12px; color: #a33; margin: 6px 0;
    font-family: "JetBrains Mono", Consolas, monospace;
  }
  .block-actions { margin-top: 6px; }
}

.notif-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.notif-list { display: flex; flex-direction: column; }
.notif-row {
  display: flex; gap: 10px; padding: 10px 4px; border-bottom: 1px solid #eef3f6;
  &:last-child { border-bottom: none; }
}
.notif-title { font-size: 13px; margin-bottom: 4px; }
.notif-meta { font-size: 12px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
</style>
