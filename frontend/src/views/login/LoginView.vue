<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <el-icon class="brand-icon"><Lock /></el-icon>
        <div>
          <div class="brand-title">sql-mask</div>
          <div class="brand-sub">Masking Console</div>
        </div>
      </div>

      <template v-if="modeReady && !ldapEnabled">
        <el-result icon="info" title="本部署未启用 LDAP 登录"
          sub-title="后端未配置 MASK_AUTH_SECRET / MASK_AUTH_LDAP_URL，控制台以 API Key 模式运行。">
          <template #extra>
            <el-button type="primary" @click="router.push('/')">进入控制台</el-button>
          </template>
        </el-result>
      </template>

      <template v-else>
        <el-form @submit.prevent="submit">
          <el-form-item>
            <el-input v-model="username" placeholder="用户名" size="large" autocomplete="username"
              :disabled="loading" @keyup.enter="submit">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model="password" type="password" placeholder="密码" size="large"
              show-password autocomplete="current-password" :disabled="loading" @keyup.enter="submit">
              <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button class="login-button" type="primary" size="large" :loading="loading"
            @click="submit">登 录</el-button>
        </el-form>
        <p v-if="error" class="login-error">{{ error }}</p>
        <p class="login-hint">使用企业 LDAP / AD 账号登录；角色由所属用户组决定。</p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Lock, User, Key } from "@element-plus/icons-vue";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const username = ref("");
const password = ref("");
const loading = ref(false);
const error = ref("");
const modeReady = ref(false);
const ldapEnabled = ref(true);

onMounted(async () => {
  ldapEnabled.value = await auth.loadMode(true);
  modeReady.value = true;
});

async function submit() {
  if (loading.value) return;
  if (!username.value.trim() || !password.value) {
    error.value = "请输入用户名和密码";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const user = await auth.login(username.value.trim(), password.value);
    ElMessage.success(`欢迎，${user.displayName || user.username}`);
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    router.push(redirect).catch(() => undefined);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped lang="scss">
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #0d2740 0%, var(--sm-navy, #123a5c) 55%, #0a2237 100%);
}
.login-card {
  width: 380px;
  background: #fff;
  border-radius: 10px;
  padding: 34px 36px 26px;
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.35);
}
.login-brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 26px;
  .brand-icon { font-size: 34px; color: #1e6fa8; }
  .brand-title { font-size: 20px; font-weight: 700; color: #14344f; letter-spacing: 0.5px; }
  .brand-sub { font-size: 11px; color: #7d93a6; letter-spacing: 1.4px; text-transform: uppercase; margin-top: 2px; }
}
.login-button { width: 100%; margin-top: 4px; }
.login-error {
  margin: 12px 2px 0;
  color: #c45656;
  font-size: 13px;
}
.login-hint {
  margin: 14px 2px 0;
  color: #98a8b6;
  font-size: 12px;
}
</style>
