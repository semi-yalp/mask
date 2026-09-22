<template>
  <div>
    <el-card shadow="never" class="card-block">
      <div class="eff-row">
        <el-input v-model="user" placeholder="user(可选)" style="width: 200px" />
        <el-input v-model="groupsText" placeholder="groups,逗号分隔(可选)" style="width: 260px" />
        <el-button type="primary" :loading="running" @click="run">拉取生效配置</el-button>
        <span class="muted">数据面 GET /api/effective(数据 Key)</span>
      </div>
      <div v-if="meta" class="eff-meta">
        <span><b>实例 / 方言</b>{{ meta.instance }} / {{ meta.dialect }}</span>
        <span><b>配置版本</b>{{ meta.configVersion }}</span>
        <span><b>策略摘要</b>启用 {{ meta.policySummary.enabled }} · 停用 {{ meta.policySummary.disabled }}</span>
      </div>
    </el-card>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="card-block" :title="error" />

    <template v-if="eff">
      <el-card v-if="tables.length" shadow="never" class="card-block">
        <template #header><span>表结构({{ tables.length }} 张)</span></template>
        <div v-for="t in tables" :key="tableKey(t)" class="eff-table">
          <div class="kv"><b>表</b>{{ tableKey(t) }}<span v-if="t.rowFilter" class="muted"> · 行过滤 {{ t.rowFilter }}</span></div>
          <div class="kv cols muted">{{ (t.columns || []).map((c) => `${c.name} ${c.type}`).join(";") || "—" }}</div>
        </div>
      </el-card>

      <el-card v-if="bindings.length" shadow="never" class="card-block">
        <template #header><span>列脱敏绑定({{ bindings.length }})</span></template>
        <div v-for="(c, i) in bindings" :key="i" class="kv">
          <b>{{ [c.catalog, c.schema, c.table].join(".") }}.{{ c.column }}</b>{{ c.policy }}
        </div>
      </el-card>

      <el-card v-if="compiledPolicies.length" shadow="never" class="card-block">
        <template #header><span>编译后的策略 UDF({{ compiledPolicies.length }})</span></template>
        <div v-for="[pk, pv] in compiledPolicies" :key="pk" class="kv">
          <b>{{ pk }}</b>{{ pv.udf || "—" }}{{ pv.arguments && pv.arguments.length ? `(${pv.arguments.join(", ")})` : "" }}
        </div>
      </el-card>

      <el-empty v-if="!tables.length && !bindings.length && !compiledPolicies.length"
        description="此主体无生效策略(wildcard 之外)。" :image-size="72" />

      <el-collapse class="raw-json">
        <el-collapse-item title="查看原始 JSON" name="raw">
          <CodeBlock :code="rawJson" :copyable="false" />
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { getEffective } from "@/api/effective";
import type { EffectiveConfig, TableDef } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";

const props = defineProps<{ instance: string }>();

const user = ref("");
const groupsText = ref("");
const running = ref(false);
const eff = ref<EffectiveConfig | null>(null);
const error = ref("");

const tables = computed<TableDef[]>(() => eff.value?.config?.metadata?.tables || []);
const bindings = computed(() => eff.value?.config?.columns || []);
const compiledPolicies = computed(() => Object.entries(eff.value?.config?.policies || {}));
const rawJson = computed(() => (eff.value ? JSON.stringify(eff.value, null, 2) : ""));
const meta = computed(() => {
  if (!eff.value) return null;
  const { instance, dialect, configVersion, policySummary } = eff.value;
  return { instance, dialect, configVersion, policySummary };
});

function tableKey(t: TableDef): string {
  return [t.catalog, t.schema, t.name].filter((x) => String(x || "").trim() !== "").join(".");
}

async function run() {
  error.value = "";
  running.value = true;
  try {
    eff.value = await getEffective(props.instance, {
      user: user.value.trim() || undefined,
      groups: groupsText.value.split(",").map((s) => s.trim()).filter(Boolean)
    });
  } catch (e) {
    error.value = (e as Error).message;
    eff.value = null;
  } finally { running.value = false; }
}
</script>

<style scoped lang="scss">
.eff-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.eff-meta { display: flex; gap: 22px; margin-top: 12px; font-size: 12.5px; flex-wrap: wrap;
  b { color: var(--sm-primary-dark); margin-right: 6px; }
}
.kv { font-size: 12.5px; line-height: 1.8; b { color: var(--sm-primary-dark); margin-right: 8px; } }
.eff-table { margin-bottom: 8px; }
.cols { margin-left: 14px; }
.raw-json { margin-top: 4px; }
</style>
