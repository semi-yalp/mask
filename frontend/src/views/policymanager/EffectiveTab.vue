<template>
  <div>
    <div class="eff-bar">
      <el-input v-model="user" placeholder="user(可选)" style="width: 200px" />
      <el-input v-model="groupsText" placeholder="groups,逗号分隔(可选)" style="width: 280px" />
      <el-button type="primary" :loading="running" @click="run">拉取生效配置</el-button>
      <span class="muted">数据面 GET /api/effective(数据 Key),按主体编译的策略视图</span>
    </div>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="eff-error" :title="error" />

    <template v-if="eff">
      <div v-if="meta" class="eff-meta">
        <span><b>实例 / 方言</b>{{ meta.instance }} / {{ meta.dialect }}</span>
        <span><b>配置版本</b>{{ meta.configVersion }}</span>
        <span><b>策略摘要</b>启用 {{ meta.policySummary.enabled }} · 停用 {{ meta.policySummary.disabled }}</span>
      </div>

      <el-table v-if="bindings.length" :data="bindings" size="small" stripe class="eff-table">
        <el-table-column label="列(catalog.schema.table.column)" min-width="320">
          <template #default="{ row }">
            <span class="mono">{{ [row.catalog, row.schema, row.table].join(".") }}.{{ row.column }}</span>
          </template>
        </el-table-column>
        <el-table-column label="策略" min-width="200" prop="policy" />
      </el-table>

      <el-table v-if="compiledPolicies.length" :data="compiledPolicies.map(([k, v]) => ({ k, v }))" size="small" stripe class="eff-table">
        <el-table-column label="编译后的策略" min-width="200">
          <template #default="{ row }"><b>{{ row.k }}</b></template>
        </el-table-column>
        <el-table-column label="UDF(参数)" min-width="260">
          <template #default="{ row }">
            <span class="mono">{{ row.v.udf || "—" }}{{ row.v.arguments && row.v.arguments.length ? `(${row.v.arguments.join(", ")})` : "" }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table v-if="tables.length" :data="tables" size="small" stripe class="eff-table">
        <el-table-column label="表" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ tableKey(row) }}</span>
            <span v-if="row.rowFilter" class="muted"> · 行过滤 {{ row.rowFilter }}</span>
          </template>
        </el-table-column>
        <el-table-column label="列" min-width="500" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted mono">{{ colsText(row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!tables.length && !bindings.length && !compiledPolicies.length"
        description="此主体无生效策略(wildcard 之外)。" :image-size="72" />

      <el-collapse class="raw-json">
        <el-collapse-item title="查看原始 JSON" name="raw">
          <CodeBlock :code="rawJson" :copyable="false" />
        </el-collapse-item>
      </el-collapse>
    </template>
    <EmptyHint v-else-if="!error && !running">输入主体(留空 = 任意主体)后拉取生效配置。</EmptyHint>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { getEffective } from "@/api/effective";
import type { EffectiveConfig, TableDef } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";
import EmptyHint from "@/components/EmptyHint.vue";

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

function colsText(t: TableDef): string {
  return (t.columns || []).map((c) => `${c.name} ${c.type}`).join("; ") || "—";
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
.eff-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 10px 0 14px; }
.eff-error { margin-bottom: 12px; }
.eff-meta {
  display: flex; gap: 26px; margin-bottom: 12px; font-size: 12.5px; flex-wrap: wrap;
  padding: 8px 12px; background: #eaf2f6; border-radius: 6px;
  b { color: var(--sm-primary-dark); margin-right: 6px; }
}
.eff-table { margin-bottom: 14px; }
.raw-json { margin-top: 4px; }
</style>
