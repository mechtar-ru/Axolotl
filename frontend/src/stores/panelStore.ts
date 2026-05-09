import { defineStore } from 'pinia';
import { ref } from 'vue';

export type PanelTab = 'exec' | 'plan' | 'memory' | 'history' | 'templates';

export const usePanelStore = defineStore('panel', () => {
  const visible = ref(false);
  const activeTab = ref<PanelTab>('exec');
  const width = ref(380);

  function open(tab?: PanelTab) {
    if (tab) activeTab.value = tab;
    visible.value = true;
  }

  function close() {
    visible.value = false;
  }

  function toggle(tab?: PanelTab) {
    if (visible.value && (!tab || tab === activeTab.value)) {
      close();
    } else {
      open(tab);
    }
  }

  function setWidth(w: number) {
    width.value = Math.max(0, Math.min(600, w));
  }

  return { visible, activeTab, width, open, close, toggle, setWidth };
});
