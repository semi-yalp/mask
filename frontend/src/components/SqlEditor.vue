<template>
  <div ref="host" class="sql-editor"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { EditorView, basicSetup } from "codemirror";
import { sql } from "@codemirror/lang-sql";
import { EditorView as EV } from "@codemirror/view";

const props = defineProps<{ modelValue: string; placeholder?: string }>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();

const host = ref<HTMLDivElement>();
let view: EditorView | null = null;

const darkTheme = EV.theme({
  "&": { backgroundColor: "#0f172a", color: "#e2e8f0", fontSize: "13px" },
  ".cm-content": { fontFamily: "'JetBrains Mono', Consolas, monospace", caretColor: "#7dd3fc" },
  ".cm-gutters": { backgroundColor: "#0f172a", color: "#475569", border: "none" },
  ".cm-activeLine": { backgroundColor: "#16223a" },
  ".cm-activeLineGutter": { backgroundColor: "#16223a" },
  "&.cm-focused": { outline: "none" },
  ".cm-placeholder": { color: "#64748b" }
});

onMounted(() => {
  view = new EditorView({
    parent: host.value!,
    doc: props.modelValue,
    extensions: [
      basicSetup,
      sql(),
      darkTheme,
      EV.editable.of(true),
      EV.updateListener.of((u) => {
        if (u.docChanged) emit("update:modelValue", u.state.doc.toString());
      })
    ]
  });
});

watch(() => props.modelValue, (v) => {
  if (view && v !== view.state.doc.toString()) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: v } });
  }
});

onBeforeUnmount(() => view?.destroy());
</script>

<style scoped lang="scss">
.sql-editor {
  border: 1px solid var(--sm-border); border-radius: 8px; overflow: hidden;
  :deep(.cm-editor) { min-height: 140px; max-height: 420px; }
}
</style>
