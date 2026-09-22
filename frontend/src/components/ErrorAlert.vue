<template>
  <el-alert v-if="error" type="error" :closable="false" show-icon class="error-alert">
    <template #title>{{ error }}</template>
  </el-alert>
</template>

<script setup lang="ts">
defineProps<{ error?: string | null }>();
</script>

<style scoped lang="scss">
.error-alert { margin-bottom: 12px; white-space: pre-wrap; word-break: break-all; }
</style>
