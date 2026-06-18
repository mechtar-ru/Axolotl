import { defineStore } from 'pinia';

// panelStore uses Options API style for legacy reasons — consistent with Pinia conventions
export const usePanelStore = defineStore('panel', {
  state: () => ({
    visible: false as boolean,
    activeBlockId: null as string | null,
  }),
  actions: {
    openBlockConfig(blockId: string) {
      this.activeBlockId = blockId;
      this.visible = true;
    },
    close() {
      this.visible = false;
      this.activeBlockId = null;
    },
  },
});
