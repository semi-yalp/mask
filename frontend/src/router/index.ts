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
        { path: "instances", name: "instances", component: () => import("@/views/instances/InstanceList.vue") },
        { path: "instances/:name", name: "instance-detail", component: () => import("@/views/instances/InstanceDetail.vue"), props: true },
        { path: "playground", name: "playground", component: () => import("@/views/playground/Playground.vue") },
        { path: "audit", name: "audit", component: () => import("@/views/audit/AuditView.vue") }
      ]
    }
  ]
});

export default router;
