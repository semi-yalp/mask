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
        { path: "metadata-manager", name: "metadata-manager", component: () => import("@/views/metadatamanager/MetadataManager.vue") },
        { path: "policy-manager/:name", name: "policy-manager", component: () => import("@/views/policymanager/PolicyManager.vue"), props: true },
        { path: "query-console", name: "query-console", component: () => import("@/views/queryconsole/QueryConsole.vue") },
        { path: "playground", name: "playground", component: () => import("@/views/playground/Playground.vue") },
        { path: "audit", name: "audit", component: () => import("@/views/audit/AuditView.vue") },
        { path: "risk/dashboard", name: "risk-dashboard", component: () => import("@/views/risk/RiskDashboard.vue") },
        { path: "risk/alerts", name: "risk-alerts", component: () => import("@/views/risk/AlertsView.vue") },
        { path: "risk/events", name: "risk-events", component: () => import("@/views/risk/RiskEventsView.vue") },
        { path: "risk/rules", name: "risk-rules", component: () => import("@/views/risk/RulesView.vue") },
        { path: "risk/assets", name: "risk-assets", component: () => import("@/views/risk/AssetsView.vue") },
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
