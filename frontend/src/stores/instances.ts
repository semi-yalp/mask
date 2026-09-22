import { ref } from "vue";
import { defineStore } from "pinia";
import { getInstance, listInstances } from "@/api/instances";
import type { InstanceInfo } from "@/types/domain";

export const useInstancesStore = defineStore("instances", () => {
  const list = ref<InstanceInfo[]>([]);
  const loading = ref(false);
  const loadError = ref("");
  const current = ref<InstanceInfo | null>(null);

  async function load() {
    loading.value = true;
    loadError.value = "";
    try {
      list.value = await listInstances();
      if (current.value) {
        const fresh = list.value.find((i) => i.name === current.value!.name);
        current.value = fresh || null;
      }
    } catch (e) {
      loadError.value = (e as Error).message;
    } finally {
      loading.value = false;
    }
  }

  async function select(name: string): Promise<InstanceInfo> {
    const inst = await getInstance(name);
    current.value = inst;
    return inst;
  }

  function forget(name: string) {
    if (current.value && current.value.name === name) current.value = null;
    list.value = list.value.filter((i) => i.name !== name);
  }

  return { list, loading, loadError, current, load, select, forget };
});
