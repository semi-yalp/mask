<template>
  <div>
    <div class="page-topnav">
      <span class="topnav-title">设置 · Settings</span>
      <span class="muted">认证模式与服务拓扑</span>
    </div>

    <div class="page">
      <div class="crumb"><a href="#/settings">设置</a></div>

      <el-row :gutter="14">
        <el-col :xs="24" :md="12">
          <el-card shadow="never" class="card-block">
            <template #header>
              <div class="card-title">
                <el-icon><Key /></el-icon>认证模式
                <span class="spacer" />
                <el-tag size="small" :type="modeTag">{{ modeText }}</el-tag>
              </div>
            </template>
            <div v-loading="loading">
              <template v-if="mode === 'simple'">
                <p class="mode-line">本地用户模式:登录走 <code>/api/auth/login</code>,用户由管理员在
                  <code>/api/auth/users</code> 维护,首次启动自动创建 <b>admin/admin</b>(请立即改密)。</p>
              </template>
              <template v-else-if="mode === 'ldap'">
                <p class="mode-line">LDAP 目录模式:登录走企业目录,角色由
                  <code>MASK_AUTH_ADMIN_GROUPS / MASK_AUTH_AUDITOR_GROUPS</code> 组映射。</p>
              </template>
              <template v-else>
                <p class="mode-line">无认证模式(<b>默认</b>):所有页面与 API 开放,审计记 ANONYMOUS。
                  开启认证需在服务端设置环境变量后重启:</p>
                <pre class="env-snip">MASK_AUTH_MODE=simple        # 本地用户(首启 admin/admin)
# 或
MASK_AUTH_MODE=ldap          # LDAP:还需 MASK_AUTH_LDAP_URL / MASK_AUTH_LDAP_BASE_DN
MASK_AUTH_SECRET=至少32字节随机串</pre>
              </template>
            </div>
          </el-card>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-card shadow="never">
            <template #header>
              <div class="card-title"><el-icon><Connection /></el-icon>服务拓扑</div>
            </template>
            <div class="topo">
              <div class="topo-row"><el-tag size="small">单体</el-tag><span><b>mask-server</b> 一个进程承载全部 API 与控制台——元数据(/api/meta、/api/classification)、策略(/api/instances)、查询网关(/api/v1)、审计(/api/audit)、风控(/api/risk)、认证(/api/auth)、授权(/api/grants)</span></div>
              <div class="topo-row"><el-tag size="small">内核</el-tag><span><b>mask-engine</b> 纯库(零 Spring),改写/血缘/行过滤,可独立嵌入查询引擎</span></div>
              <div class="topo-row"><el-tag size="small">存储</el-tag><span>平台库:H2 文件(默认)或 PostgreSQL(<code>MASK_STORAGE_PG_URL</code>);数据引擎按实例连接走 JDBC/HTTP</span></div>
            </div>
            <p class="muted key-note">控制台与 API 同源(单端口),前端 nginx/dev 代理只需一条 /api 规则;旧的多服务端口(8081-8084)已退役。</p>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Key, Connection } from "@element-plus/icons-vue";
import { modeRequest } from "@/api/auth";

const mode = ref<"none" | "simple" | "ldap" | null>(null);
const loading = ref(true);

const modeText = ref("…");
const modeTag = ref<"info" | "success" | "warning">("info");

onMounted(async () => {
  try {
    const { ok, body } = await modeRequest();
    mode.value = ok && body?.mode
      ? (String(body.mode).toLowerCase() as "none" | "simple" | "ldap")
      : "none";
  } catch {
    mode.value = "none";
  } finally {
    loading.value = false;
    if (mode.value === "simple") { modeText.value = "simple(本地用户)"; modeTag.value = "success"; }
    else if (mode.value === "ldap") { modeText.value = "LDAP(目录登录)"; modeTag.value = "success"; }
    else { modeText.value = "none(无认证)"; modeTag.value = "warning"; }
  }
});
</script>

<style scoped lang="scss">
.card-title {
  display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 13.5px;
  .spacer { flex: 1; }
}
.mode-line { font-size: 13px; line-height: 1.8; margin: 0 0 8px; code { background: #eef3f6; padding: 1px 5px; border-radius: 3px; } }
.env-snip {
  background: #0f172a; color: #d7e3ee; font-size: 12px; line-height: 1.7;
  padding: 10px 12px; border-radius: 6px; overflow-x: auto;
}
.key-note { font-size: 12px; line-height: 1.7; margin: 10px 0 0; }
.topo { display: flex; flex-direction: column; gap: 10px; }
.topo-row {
  display: flex; align-items: flex-start; gap: 10px; font-size: 12.5px;
  b { color: var(--sm-primary-dark); }
  .el-tag { margin-top: 1px; }
}
</style>
