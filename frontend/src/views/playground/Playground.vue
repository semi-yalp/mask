<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">改写试验台 · Playground</span>
      <span class="muted">原始查询作为内层子查询,最外层对结果列调用脱敏 UDF;只解析与改写,从不执行业务 SQL</span>
    </div>
    <div class="page playground">

    <el-row :gutter="14">
      <el-col :span="11">
        <el-card shadow="never" class="card-block">
          <template #header>
            <div class="head-row">
              <el-radio-group v-model="mode" size="small">
                <el-radio-button value="instance">instance 模式</el-radio-button>
                <el-radio-button value="inline">内联 YAML</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <template v-if="mode === 'instance'">
            <div class="form-row">
              <el-select v-model="instance" placeholder="选择实例" style="flex: 1" loading>
                <el-option v-for="i in store.list" :key="i.name" :value="i.name" :label="`${i.name}(${i.dialect})`" />
              </el-select>
            </div>
            <div class="form-row">
              <el-input v-model="user" placeholder="user(可选)" style="flex: 1" />
              <el-input v-model="groupsText" placeholder="groups,逗号分隔(可选)" style="flex: 1" />
            </div>
          </template>
          <template v-else>
            <div class="form-row">
              <el-select v-model="dialect" style="flex: 1">
                <el-option v-for="d in DIALECTS" :key="d" :value="d" :label="`dialect: ${d}`" />
              </el-select>
              <el-input v-model="user" placeholder="user(可选)" style="flex: 1" />
              <el-input v-model="groupsText" placeholder="groups,逗号分隔" style="flex: 1" />
            </div>
            <div class="yaml-label">metadata.yaml<span v-if="policyYaml.trim()" class="muted label-hint">(policies.yaml 模式下须去掉内嵌 policies/columns/rowFilter)</span></div>
            <el-input v-model="metadataYaml" type="textarea" :rows="6" class="mono" placeholder="metadata YAML" />
            <div class="yaml-label">policies.yaml(可选,Ranger 式主体策略)</div>
            <el-input v-model="policyYaml" type="textarea" :rows="6" class="mono" placeholder="Ranger 式策略文件;填写后 user/groups 参与主体匹配" />
          </template>

          <div class="yaml-label">SQL</div>
          <SqlEditor v-model="sql" />
          <div class="run-row">
            <el-button type="success" class="run-btn" :loading="running" @click="run">改写</el-button>
            <span class="muted">工具只解析与改写,从不执行业务 SQL</span>
          </div>
        </el-card>
      </el-col>

      <el-col :span="13">
        <ErrorAlert :error="error" />
        <el-card v-if="result" shadow="never" class="card-block">
          <template #header><span>改写结果({{ result.statements.length }} 条语句)</span></template>
          <div v-for="s in result.statements" :key="s.ordinal" class="stmt">
            <div class="stmt-head">
              <span>#{{ s.ordinal + 1 }}</span>
              <el-tag v-if="s.kind" size="small" effect="plain">{{ s.kind }}</el-tag>
              <el-tag size="small" :type="s.masked ? 'success' : 'info'">{{ s.masked ? "已脱敏" : "未脱敏" }}</el-tag>
              <el-tag v-if="s.rowFiltered" size="small" type="warning">行过滤</el-tag>
            </div>
            <CodeBlock :code="s.rewrittenSql || s.originalSql || ''" :copyable="true" />
          </div>
          <div class="combined-label">合并脚本 rewrittenSql</div>
          <CodeBlock :code="result.rewrittenSql" :copyable="true" />
        </el-card>
        <el-card v-else-if="!error" shadow="never">
          <el-empty description="提交 SQL 后在此查看改写结果" :image-size="72" />
        </el-card>
      </el-col>
    </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { rewrite } from "@/api/rewrite";
import { useInstancesStore } from "@/stores/instances";
import { POLICY_DIALECTS } from "@/constants";
import type { RewriteResponse } from "@/types/domain";
import CodeBlock from "@/components/CodeBlock.vue";
import ErrorAlert from "@/components/ErrorAlert.vue";
import SqlEditor from "@/components/SqlEditor.vue";

const store = useInstancesStore();
const DIALECTS = [...POLICY_DIALECTS];

const mode = ref<"instance" | "inline">("instance");
const instance = ref("");
const user = ref("");
const groupsText = ref("");
const dialect = ref<string>("postgresql");
const metadataYaml = ref("");
const policyYaml = ref("");
const sql = ref("");
const running = ref(false);
const error = ref("");
const result = ref<RewriteResponse | null>(null);

onMounted(() => store.load());

async function run() {
  error.value = "";
  result.value = null;
  if (mode.value === "inline" && !metadataYaml.value.trim()) { error.value = "metadata YAML 不能为空"; return; }
  if (!sql.value.trim()) { error.value = "SQL 不能为空"; return; }
  if (mode.value === "instance" && !instance.value) { error.value = "请选择实例"; return; }
  running.value = true;
  try {
    const groups = groupsText.value.split(",").map((s) => s.trim()).filter(Boolean);
    result.value = await rewrite(mode.value === "instance"
      ? { instance: instance.value, user: user.value.trim() || undefined, groups, sql: sql.value }
      : {
          metadataYaml: metadataYaml.value, policyYaml: policyYaml.value.trim() || undefined,
          dialect: dialect.value, user: user.value.trim() || undefined,
          groups: policyYaml.value.trim() ? groups : undefined, sql: sql.value
        });
  } catch (e) { error.value = (e as Error).message; }
  finally { running.value = false; }
}
</script>

<style scoped lang="scss">
.run-btn {
  background: var(--sm-success); border-color: var(--sm-success); color: #fff; font-weight: 600;
  &:hover, &:focus { background: var(--sm-success-dark); border-color: var(--sm-success-dark); color: #fff; }
}
.head-row { display: flex; align-items: center; }
.form-row { display: flex; gap: 8px; margin-bottom: 10px; }
.yaml-label { font-size: 12.5px; font-weight: 600; margin: 10px 0 6px; .label-hint { font-weight: 400; margin-left: 6px; } }
.run-row { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.stmt { margin-bottom: 14px; }
.stmt-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 12.5px; }
.combined-label { font-size: 12.5px; font-weight: 600; margin: 16px 0 6px; }
</style>
