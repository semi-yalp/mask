<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">风险大盘 · Risk Dashboard</span>
      <span class="muted">SQL 访问链路的风险监控与预警</span>
      <div class="topnav-actions">
        <el-select v-model="windowHours" size="small" style="width: 96px" @change="load">
          <el-option :value="6" label="近 6 小时" />
          <el-option :value="24" label="近 24 小时" />
          <el-option :value="48" label="近 48 小时" />
        </el-select>
        <el-dropdown trigger="click" @command="simulate">
          <el-button size="small" type="danger" plain>
            <el-icon style="margin-right:4px"><Lightning /></el-icon>模拟攻击
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="s in scenarios" :key="s.id" :command="s.id"
                :divided="s.severity === 'MEDIUM'">
                <span :style="{ color: severityColor(s.severity), fontWeight: 600 }">{{ s.name }}</span>
                <span class="muted" style="margin-left:8px">{{ s.description }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button size="small" @click="reseed" :loading="reseeding">重置演示数据</el-button>
        <el-button size="small" type="primary" plain @click="load">
          <el-icon><Refresh /></el-icon>&nbsp;刷新
        </el-button>
      </div>
    </div>

    <div class="page">
      <ErrorAlert :error="error" />

      <div class="hero card-block">
        <div class="hero-text">
          <div class="hero-title">SQL 访问风险监控</div>
          <div class="hero-sub muted">
            改写/执行事件实时流经规则引擎:内置 SQL 注入特征、敏感列高频访问、失败风暴、非工作时间、大结果集、脱敏旁路等检测,
            支持自定义条件规则;命中按「规则 × 用户」收敛为告警,在此总览处置。
          </div>
        </div>
        <div class="hero-actions">
          <el-button type="primary" @click="$router.push({ name: 'risk-alerts' })">告警中心({{ alertsOpen }})</el-button>
          <el-button @click="$router.push({ name: 'risk-rules' })">检测规则</el-button>
        </div>
      </div>

      <el-row :gutter="14" class="card-block">
        <el-col :span="6">
          <el-card shadow="never" class="stat">
            <div class="stat-num">{{ events.total }}</div>
            <div class="stat-label">事件(窗口内)</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat">
            <div class="stat-num" :style="{ color: events.hitRate > 20 ? '#e8590c' : 'inherit' }">
              {{ events.flagged }} <span class="stat-ratio">/ {{ events.hitRate }}%</span>
            </div>
            <div class="stat-label">风险命中</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat">
            <div class="stat-tags">
              <el-tag v-for="sev in ['critical', 'high', 'medium']" :key="sev" size="large"
                :color="severityColor(sev)" style="color:#fff;border:none">
                {{ severityLabel(sev) }} {{ alerts.bySeverity?.[sev] ?? 0 }}
              </el-tag>
            </div>
            <div class="stat-label">未处理告警(共 {{ alertsOpen }})</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat">
            <div class="stat-num" :style="{ color: scoreColor(avgScoreInt) }">{{ avgScoreInt }}</div>
            <div class="stat-label">平均风险分(0-100)</div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="14" class="card-block">
        <el-col :span="16">
          <el-card shadow="never">
            <template #header>
              <div class="chart-head">
                <b>事件与告警趋势</b>
                <span class="chart-legend">
                  <i class="lg" style="background:#35a7d3" />事件
                  <i class="lg" style="background:#dc3545" />风险命中
                  <i class="lg lg-dot" style="background:#f0b849" />告警
                </span>
              </div>
            </template>
            <div v-if="timeseries.length" class="trend-wrap">
              <svg :viewBox="`0 0 ${trendW} ${trendH}`" class="trend" preserveAspectRatio="none">
                <g>
                  <line v-for="g in gridY" :key="'gy' + g" x1="46" :x2="trendW - 6" :y1="g" :y2="g"
                    stroke="#e3edf1" stroke-width="1" />
                </g>
                <!-- 干净事件(浅色柱) -->
                <rect v-for="(b, i) in bars" :key="'c' + i"
                  :x="b.x" :y="b.cleanY" :width="Math.max(b.w - 1, 1)" :height="b.cleanH"
                  fill="#c9e4ef" rx="1">
                  <title>{{ b.title }} · 干净事件 {{ b.clean }}</title>
                </rect>
                <!-- 风险命中(红色堆叠段) -->
                <rect v-for="(b, i) in bars" :key="'f' + i"
                  :x="b.x" :y="b.flagY" :width="Math.max(b.w - 1, 1)" :height="b.flagH"
                  :fill="severityColor('critical')" opacity="0.85" rx="1">
                  <title>{{ b.title }} · 风险命中 {{ b.flagged }}</title>
                </rect>
                <!-- 告警标记点 -->
                <circle v-for="(b, i) in alertDots" :key="'a' + i"
                  :cx="b.cx" :cy="b.cy" r="3.2" :fill="severityColor('medium')"
                  stroke="#fff" stroke-width="1">
                  <title>{{ b.title }}</title>
                </circle>
                <g v-for="t in xLabels" :key="'x' + t.i" :transform="`translate(${t.x},${t.y})`">
                  <text text-anchor="middle" class="tick">{{ t.label }}</text>
                </g>
                <g v-for="t in yLabels" :key="'y' + t.v">
                  <text :x="40" :y="t.y" text-anchor="end" class="tick">{{ t.v }}</text>
                </g>
              </svg>
            </div>
            <el-empty v-else description="窗口内暂无事件" :image-size="60" />
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" header="风险等级分布(事件 Top 级别)">
            <div v-if="events.total" class="donut-wrap">
              <svg :viewBox="`0 0 180 180`" class="donut">
                <g transform="translate(90,90)">
                  <circle v-for="(a, i) in donutArcs" :key="i"
                    :stroke="a.color" :stroke-width="20" fill="none"
                    :stroke-dasharray="`${a.len} ${circ - a.len}`"
                    :stroke-dashoffset="a.offset"
                    :transform="`rotate(${a.start})`">
                    <title>{{ a.title }}</title>
                  </circle>
                  <text text-anchor="middle" y="-2" class="donut-num">{{ events.total }}</text>
                  <text text-anchor="middle" y="16" class="donut-label">事件</text>
                </g>
              </svg>
              <div class="donut-legend">
                <div v-for="sev in SEVERITY_ORDER" :key="sev" class="dl-row">
                  <i class="lg" :style="{ background: severityColor(sev) }" />
                  <span class="dl-name">{{ severityLabel(sev) }}</span>
                  <b class="dl-num">{{ events.bySeverity?.[sev] ?? 0 }}</b>
                </div>
              </div>
            </div>
            <el-empty v-else description="暂无数据" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="14" class="card-block">
        <el-col :span="8">
          <el-card shadow="never" header="Top 风险用户">
            <el-table :data="overview?.topUsers ?? []" size="small" :show-header="false">
              <el-table-column width="110">
                <template #default="{ row }">
                  <span class="mono">{{ row.user }}</span>
                </template>
              </el-table-column>
              <el-table-column>
                <template #default="{ row }">
                  <div class="score-bar">
                    <div class="score-fill" :style="{ width: pct(row.riskScore), background: scoreColor(row.riskScore) }" />
                  </div>
                </template>
              </el-table-column>
              <el-table-column width="86" align="right">
                <template #default="{ row }">
                  <span :style="{ color: scoreColor(row.riskScore), fontWeight: 600 }">{{ row.riskScore }}</span>
                  <span class="muted"> / {{ row.events }} 次</span>
                </template>
              </el-table-column>
              <el-table-column width="64" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.openAlerts > 0" type="danger" size="small" effect="plain">{{ row.openAlerts }} 告警</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" header="Top 命中规则">
            <el-table :data="overview?.topRules ?? []" size="small" :show-header="false">
              <el-table-column>
                <template #default="{ row }">
                  <el-tag :color="severityColor(row.severity)" style="color:#fff;border:none" size="small">
                    {{ severityLabel(row.severity) }}
                  </el-tag>
                  <span style="margin-left:8px">{{ row.ruleName }}</span>
                </template>
              </el-table-column>
              <el-table-column width="70" align="right">
                <template #default="{ row }">
                  <b>{{ row.hits }}</b> 次
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" header="敏感列访问 Top">
            <el-table :data="overview?.topSensitiveColumns ?? []" size="small" :show-header="false">
              <el-table-column>
                <template #default="{ row }">
                  <span class="mono">{{ shortKey(row.columnKey) }}</span>
                </template>
              </el-table-column>
              <el-table-column width="90">
                <template #default="{ row }">
                  <el-tag size="small" :color="severityColor(row.sensitivity)" style="color:#fff;border:none">
                    {{ severityLabel(row.sensitivity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column width="110" align="right">
                <template #default="{ row }">
                  <b>{{ row.accesses }}</b> 次 <span class="muted">/ {{ row.distinctUsers }} 人</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="card-block">
        <template #header>
          <div class="chart-head">
            <b>用户行为基线 (UEBA)</b>
            <span class="muted chart-legend">自动从历史学习:基线速率 / 惯常时段 / 结果集规模,偏离触发「UEBA 行为基线偏离」</span>
          </div>
        </template>
        <el-table :data="ueba" size="small">
          <el-table-column label="用户" width="130">
            <template #default="{ row }"><span class="mono">{{ row.user }}</span></template>
          </el-table-column>
          <el-table-column label="基线速率" width="100" align="center">
            <template #default="{ row }">{{ row.ratePerHour }} 次/时</template>
          </el-table-column>
          <el-table-column label="近10分钟" width="100" align="center">
            <template #default="{ row }">
              <el-badge :is-dot="row.bursting" type="danger">
                <b :style="{ color: row.bursting ? '#e8590c' : 'inherit' }">{{ row.currentWindowCount }}</b>
              </el-badge>
              <span class="muted"> 次</span>
            </template>
          </el-table-column>
          <el-table-column label="中位行数" width="100" align="center">
            <template #default="{ row }">{{ row.medianRowCount ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="样本" width="70" align="center">
            <template #default="{ row }">{{ row.events }}</template>
          </el-table-column>
          <el-table-column label="24 小时活跃分布" min-width="320">
            <template #default="{ row }">
              <div class="hour-strip">
                <div v-for="(count, hour) in row.histogram" :key="hour" class="hour-cell"
                  :style="{ background: hourColor(count, row.maxHourCount) }"
                  :title="String(hour).padStart(2, '0') + ' 点: ' + count + ' 次'">
                  <span v-if="hour % 6 === 0" class="hour-label">{{ hour }}</span>
                </div>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="chart-head">
            <b>最近告警</b>
            <router-link :to="{ name: 'risk-alerts' }" class="link">查看全部 →</router-link>
          </div>
        </template>
        <div v-if="recentAlerts.length" class="recent-alerts">
          <div v-for="a in recentAlerts" :key="a.id" class="ra-row" @click="$router.push({ name: 'risk-alerts' })">
            <el-tag :color="severityColor(a.severity)" style="color:#fff;border:none" size="small">
              {{ severityLabel(a.severity) }}
            </el-tag>
            <span class="ra-title">{{ a.title }}</span>
            <el-tag size="small" :type="statusElType(a.status)" effect="plain">{{ statusLabel(a.status) }}</el-tag>
            <span class="muted ra-meta">命中 {{ a.eventCount }} 次 · {{ formatTime(a.lastHitAt) }}</span>
          </div>
        </div>
        <el-empty v-else description="窗口内暂无告警" :image-size="60" />
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Lightning } from "@element-plus/icons-vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import {
  fetchOverview, fetchScenarios, runScenario, reseedDemo, fetchUebaProfiles,
  type OverviewVo, type ScenarioVo, type UebaProfileVo
} from "@/api/risk";
import {
  SEVERITY_ORDER, severityColor, severityLabel, scoreColor,
  formatTime, statusLabel, statusElType
} from "@/views/risk/risk-ui";

const overview = ref<OverviewVo | null>(null);
const scenarios = ref<ScenarioVo[]>([]);
const error = ref("");
const windowHours = ref(24);
const reseeding = ref(false);
let timer: number | undefined;

const events = computed(() => overview.value?.events ?? { total: 0, flagged: 0, hitRate: 0, avgScore: 0, bySeverity: {} });
const alerts = computed(() => overview.value?.alerts ?? { open: 0, acknowledged: 0, resolvedInWindow: 0, bySeverity: {} });
const alertsOpen = computed(() => (alerts.value.open ?? 0) + (alerts.value.acknowledged ?? 0));
const avgScoreInt = computed(() => Math.round(events.value.avgScore ?? 0));
const timeseries = computed(() => overview.value?.timeseries ?? []);
const recentAlerts = computed(() => overview.value?.recentAlerts ?? []);
const ueba = ref<UebaProfileVo[]>([]);

async function load() {
  error.value = "";
  try {
    overview.value = await fetchOverview(windowHours.value, windowHours.value <= 6 ? 10 : 30);
  } catch (e) {
    error.value = (e as Error).message;
  }
  try {
    ueba.value = (await fetchUebaProfiles(8)).profiles;
  } catch {
    ueba.value = [];
  }
}

async function simulate(id: string) {
  try {
    const r = await runScenario(id);
    ElMessage.success(`已注入「${id}」:${r.flagged}/${r.accepted} 条事件命中 ${r.hits} 项规则,新增告警 ${r.alertsCreated} 条`);
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function reseed() {
  reseeding.value = true;
  try {
    const r = await reseedDemo();
    ElMessage.success(`已重建演示数据:${r.seededEvents} 事件 / ${r.alerts} 告警`);
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    reseeding.value = false;
  }
}

// ---- SVG 趋势图(纯手绘:堆叠柱 + 告警点) ----
const trendW = 960;
const trendH = 230;
const padTop = 14;
const padBottom = 26;

const maxEvents = computed(() => Math.max(4, ...timeseries.value.map(t => t.events)));
const bars = computed(() => {
  const ts = timeseries.value;
  if (!ts.length) return [];
  const innerW = trendW - 52;
  const innerH = trendH - padTop - padBottom;
  const bw = innerW / ts.length;
  return ts.map((t, i) => {
    const h = (v: number) => (v / maxEvents.value) * innerH;
    const total = t.events;
    const flagH = h(t.flagged);
    const cleanH = h(total - t.flagged);
    const x = 46 + i * bw + bw * 0.12;
    const baseY = padTop + innerH;
    const d = new Date(t.bucket);
    const p = (n: number) => String(n).padStart(2, "0");
    return {
      x,
      w: bw * 0.76,
      cleanY: baseY - cleanH - flagH,
      cleanH,
      flagY: baseY - flagH,
      flagH,
      clean: total - t.flagged,
      flagged: t.flagged,
      title: `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())} 共 ${total}`
    };
  });
});

const alertDots = computed(() => {
  const ts = timeseries.value;
  if (!ts.length) return [];
  const innerW = trendW - 52;
  const bw = innerW / ts.length;
  return ts
    .map((t, i) => ({ t, i }))
    .filter(({ t }) => t.alerts > 0)
    .map(({ t, i }) => {
      const d = new Date(t.bucket);
      const p = (n: number) => String(n).padStart(2, "0");
      return {
        cx: 46 + i * bw + bw / 2,
        cy: padTop + 6,
        title: `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())} 新增告警 ${t.alerts}`
      };
    });
});

const gridY = computed(() => {
  const innerH = trendH - padTop - padBottom;
  return [0, 1, 2, 3, 4].map(k => padTop + (innerH * k) / 4);
});

const yLabels = computed(() => {
  const innerH = trendH - padTop - padBottom;
  return [0, 1, 2, 3, 4].map(k => ({
    v: Math.round((maxEvents.value * (4 - k)) / 4),
    y: padTop + (innerH * k) / 4 + 4
  }));
});

const xLabels = computed(() => {
  const ts = timeseries.value;
  if (!ts.length) return [];
  const innerW = trendW - 52;
  const bw = innerW / ts.length;
  const step = Math.max(1, Math.round(ts.length / 8));
  const out: { i: number; x: number; y: number; label: string }[] = [];
  ts.forEach((t, i) => {
    if (i % step !== 0) return;
    const d = new Date(t.bucket);
    const p = (n: number) => String(n).padStart(2, "0");
    out.push({ i, x: 46 + i * bw + bw / 2, y: trendH - 8, label: `${p(d.getHours())}:${p(d.getMinutes())}` });
  });
  return out;
});

// ---- SVG 环图 ----
const circ = 2 * Math.PI * 70;
const donutArcs = computed(() => {
  const total = events.value.total || 1;
  const counts = SEVERITY_ORDER.map(sev => ({ sev, n: events.value.bySeverity?.[sev] ?? 0 }));
  let start = 0;
  return counts
    .filter(c => c.n > 0)
    .map(c => {
      const len = (c.n / total) * circ;
      const arc = {
        color: severityColor(c.sev),
        len,
        offset: -start,
        start: -90,
        title: `${severityLabel(c.sev)} ${c.n} 条 (${Math.round((c.n / total) * 100)}%)`
      };
      start += len;
      return arc;
    });
});

function pct(score: number): string {
  const max = Math.max(100, ...((overview.value?.topUsers ?? []).map(u => u.riskScore)));
  return `${Math.max(3, Math.round((score / max) * 100))}%`;
}

function hourColor(count: number, max: number): string {
  if (!count) return "#f0f4f6";
  const alpha = 0.25 + 0.75 * (count / Math.max(1, max));
  return "rgba(53, 167, 211, " + alpha.toFixed(2) + ")";
}

function shortKey(key: string): string {
  const parts = key.split(".");
  return parts.slice(-2).join(".");
}

onMounted(async () => {
  await load();
  try {
    scenarios.value = await fetchScenarios();
  } catch {
    scenarios.value = [];
  }
  timer = window.setInterval(load, 15000);
});
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<style scoped lang="scss">
.topnav-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.hero {
  background: linear-gradient(100deg, var(--sm-navy) 0%, #0b5c7d 70%, var(--sm-primary) 100%);
  border: none;
  border-radius: 8px;
  padding: 22px 24px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}
.hero-title { font-size: 20px; font-weight: 700; letter-spacing: 0.5px; }
.hero-sub { margin-top: 6px; max-width: 720px; font-size: 12.5px; line-height: 1.7; color: #cfe3ec; }
.hero-actions { flex-shrink: 0; display: flex; flex-direction: column; gap: 8px; }

.stat-num { font-size: 26px; font-weight: 700; }
.stat-ratio { font-size: 14px; color: var(--sm-muted); font-weight: 500; }
.stat-label { color: var(--sm-muted); font-size: 12px; margin-top: 4px; }
.stat-tags { display: flex; gap: 6px; flex-wrap: wrap; }

.chart-head { display: flex; align-items: center; justify-content: space-between; }
.chart-legend { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--sm-muted); font-weight: 400; }
.lg { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-left: 8px; }
.lg-dot { border-radius: 50%; width: 8px; height: 8px; }
.link { color: var(--sm-primary); text-decoration: none; font-size: 12.5px; }

.trend-wrap { width: 100%; }
.trend { width: 100%; height: 240px; display: block; }
.tick { font-size: 10px; fill: var(--sm-muted); }

.donut-wrap { display: flex; align-items: center; gap: 12px; }
.donut { width: 168px; height: 168px; flex-shrink: 0; }
.donut-num { font-size: 22px; font-weight: 700; fill: var(--sm-text); }
.donut-label { font-size: 10px; fill: var(--sm-muted); }
.donut-legend { flex: 1; }
.dl-row { display: flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 12.5px; }
.dl-name { color: var(--sm-muted); }
.dl-num { margin-left: auto; }

.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12px; }
.score-bar { height: 8px; background: #edf3f6; border-radius: 4px; overflow: hidden; }
.score-fill { height: 100%; border-radius: 4px; }

.recent-alerts { display: flex; flex-direction: column; }
.ra-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 6px;
  border-bottom: 1px solid #eef3f6; cursor: pointer;
  &:hover { background: #f5fafc; }
  &:last-child { border-bottom: none; }
}
.ra-title { font-size: 13px; }
.ra-meta { margin-left: auto; font-size: 12px; }
.hour-strip { display: flex; gap: 2px; width: 100%; max-width: 480px; }
.hour-cell { flex: 1; height: 22px; border-radius: 3px; position: relative; }
.hour-label { position: absolute; bottom: -2px; left: 2px; font-size: 8.5px; color: var(--sm-muted); }
</style>
