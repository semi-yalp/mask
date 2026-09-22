<template>
  <div class="code-block">
    <el-icon v-if="copyable" class="copy" title="复制" @click="doCopy"><CopyDocument /></el-icon>
    <pre><code>{{ code }}</code></pre>
  </div>
</template>

<script setup lang="ts">
import { CopyDocument } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

const props = defineProps<{ code: string; copyable?: boolean }>();

async function doCopy() {
  try {
    await navigator.clipboard.writeText(props.code);
    ElMessage.success("已复制");
  } catch {
    ElMessage.error("复制失败");
  }
}
</script>

<style scoped lang="scss">
.code-block { position: relative; }
.copy {
  position: absolute; top: 8px; right: 10px; color: #64748b; cursor: pointer;
  &:hover { color: var(--sm-code-text); }
}
pre {
  background: var(--sm-code-bg); color: var(--sm-code-text); border-radius: 8px;
  padding: 10px 12px; font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12.5px; line-height: 1.55; white-space: pre-wrap; word-break: break-word;
  overflow-x: auto; margin: 0;
}
</style>
