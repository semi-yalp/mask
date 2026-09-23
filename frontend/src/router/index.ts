import { createRouter, createWebHistory } from "vue-router";
import ConsoleLayout from "@/layouts/ConsoleLayout.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
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

export default router;
