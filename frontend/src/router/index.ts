import { createRouter, createWebHistory } from "vue-router";
import ConsoleLayout from "@/layouts/ConsoleLayout.vue";
import { useAuthStore } from "@/stores/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/login/LoginView.vue")
    },
    {
      path: "/",
      component: ConsoleLayout,
      children: [
        { path: "", name: "dashboard", component: () => import("@/views/Dashboard.vue") },
        { path: "access-manager", name: "access-manager", component: () => import("@/views/access/AccessManager.vue") },
        { path: "policy-manager/:name", name: "policy-manager", component: () => import("@/views/policymanager/PolicyManager.vue"), props: true },
        { path: "playground", name: "playground", component: () => import("@/views/playground/Playground.vue") },
        { path: "audit", name: "audit", component: () => import("@/views/audit/AuditView.vue") },
        { path: "settings", name: "settings", component: () => import("@/views/settings/Settings.vue") },
        // 旧路径兼容重定向
        { path: "instances", redirect: { name: "access-manager" } },
        { path: "instances/:name", redirect: (to) => ({ name: "policy-manager", params: { name: to.params.name } }) }
      ]
    },
    { path: "/:pathMatch(.*)*", redirect: { name: "dashboard" } }
  ]
});

/**
 * 登录守卫：LDAP 模式（后端 /api/auth/mode 探测确认）下未登录一律先去
 * /login，登录后回到原地址。模式未知/探测失败按 API Key 模式放行，
 * 后端不可用时不能把人锁死在登录页。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.name === "login") {
    return auth.isLoggedIn ? { name: "dashboard" } : true;
  }
  if (auth.ldapMode === null) {
    await auth.loadMode();
  }
  if (auth.ldapMode && !auth.isLoggedIn) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  return true;
});

export default router;
