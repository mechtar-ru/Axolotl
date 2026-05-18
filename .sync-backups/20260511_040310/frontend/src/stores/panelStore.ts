import { defineStore } from 'pinia';

export type PanelTab = 'exec' | 'plan' | 'memory' | 'history' | 'templates';

export const usePanelStore = defineStore('panel', {
  state: () => ({
    visible: false as boolean,
    activeTab: 'exec' as PanelTab,
    width: 380 as number,
  }),
  actions: {
    open(tab?: PanelTab) {
      if (tab) this.activeTab = tab;
      this.visible = true;
    },
    close() {
      this.visible = false;
    },
    toggle(tab?: PanelTab) {
      if (this.visible && (!tab || tab === this.activeTab)) {
        this.close();
      } else {
        this.open(tab);
      }
    },
    setWidth(w: number) {
      this.width = Math.max(0, Math.min(600, w));
    },
  },
});
