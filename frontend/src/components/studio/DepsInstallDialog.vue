<template>
  <div v-if="visible" class="deps-overlay">
    <div class="deps-modal">
      <div class="deps-header">
        <span class="deps-title">
          {{ installing ? '╰┈➤ Installing dependencies...' : '⚠️ Missing Dependencies' }}
        </span>
      </div>

      <div class="deps-body">
        <p v-if="!installing && !done" class="deps-intro">
          The app needs these tools to build:
        </p>
        <p v-else-if="installing" class="deps-intro">
          Installing requested dependencies...
        </p>
        <p v-else class="deps-intro success-text">
          All done! You can now build the app.
        </p>

        <div class="dep-list">
          <div
            v-for="(dep, idx) in deps"
            :key="idx"
            class="dep-item"
            :class="{ installing: installing, done: doneDep(dep) }"
          >
            <span class="dep-icon">{{ doneDep(dep) ? '✅' : installing ? '⟳' : '⬜' }}</span>
            <span class="dep-name">{{ dep }}</span>
            <span v-if="installResults[dep] === 'ok'" class="dep-status ok">Installed</span>
            <span v-else-if="installResults[dep] === 'error'" class="dep-status error">Failed</span>
            <span v-else-if="installing" class="dep-status pending">{{ getCurrentDep(dep) ? '...' : '' }}</span>
          </div>
        </div>

        <div v-if="installError" class="install-error">{{ installError }}</div>
      </div>

      <div class="deps-footer">
        <div class="footer-actions">
          <button v-if="!installing && !done" class="btn-dismiss" @click="handleClose">
            Dismiss
          </button>
          <button v-if="!installing && !done" class="btn-install" @click="handleInstall">
            Install All (brew)
          </button>
          <button v-if="installing" class="btn-installing" disabled>
            Installing...
          </button>
          <button v-if="done" class="btn-continue" @click="handleClose">
            Continue
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { api } from '@/services/api';

const props = withDefaults(defineProps<{
  visible: boolean;
  schemaId: string;
  executionId: string;
  nodeId: string;
  deps: string[];
  projectPath: string;
}>(), {
  visible: false,
  schemaId: '',
  executionId: '',
  nodeId: '',
  deps: () => [],
  projectPath: '',
});

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const installing = ref(false);
const done = ref(false);
const currentDep = ref('');
const installResults = ref<Record<string, string>>({});
const installError = ref('');

function doneDep(dep: string): boolean {
  return installResults.value[dep] === 'ok';
}

function getCurrentDep(dep: string): boolean {
  return currentDep.value === dep;
}

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8082/api';

async function handleInstall() {
  installing.value = true;
  installError.value = '';

  for (const dep of props.deps) {
    currentDep.value = dep;
    try {
      const res = await fetch(`${API_BASE}/execution/${props.executionId}/install-deps`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deps: [dep],
          schemaId: props.schemaId,
        }),
      });
      const data = await res.json();
      if (data.status === 'ok') {
        installResults.value[dep] = 'ok';
      } else {
        installResults.value[dep] = 'error';
        const msg = data.results?.[0]?.message || 'Unknown error';
        installError.value = `Failed to install ${dep}: ${msg}`;
      }
    } catch (e: any) {
      installResults.value[dep] = 'error';
      installError.value = `Failed to install ${dep}: ${e.message}`;
    }
  }

  currentDep.value = '';
  installing.value = false;
  done.value = true;
}

function handleClose() {
  emit('close');
}
</script>

<style scoped>
.deps-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay-heavy, rgba(0,0,0,0.5));
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-tooltip, 1000);
}

.deps-modal {
  background: var(--bg-primary, #1a1a2e);
  border-radius: var(--radius-lg, 12px);
  width: 520px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg, 0 8px 32px rgba(0,0,0,0.3));
  border: 1px solid var(--border-color, #2a2a4a);
  overflow: hidden;
}

.deps-header {
  padding: var(--space-4, 16px) var(--space-5, 20px);
  border-bottom: 1px solid var(--border-color, #2a2a4a);
}

.deps-title {
  color: var(--text-primary, #e0e0e0);
  font-weight: 600;
  font-size: var(--text-sm, 14px);
}

.deps-body {
  flex: 1;
  padding: var(--space-4, 16px) var(--space-5, 20px);
  overflow-y: auto;
}

.deps-intro {
  color: var(--text-secondary, #a0a0b0);
  font-size: var(--text-xs, 12px);
  margin: 0 0 var(--space-3, 12px);
}

.success-text {
  color: var(--success, #4caf50);
  font-weight: 600;
}

.dep-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2, 8px);
}

.dep-item {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  border-radius: var(--radius-md, 8px);
  background: var(--bg-hover, rgba(255,255,255,0.05));
  border: 1px solid var(--border-subtle, rgba(255,255,255,0.08));
}

.dep-item.installing {
  border-color: var(--accent, #7c5cfc);
}

.dep-item.done {
  border-color: var(--success, #4caf50);
  opacity: 0.8;
}

.dep-icon {
  width: 20px;
  text-align: center;
  font-size: 14px;
}

.dep-name {
  flex: 1;
  color: var(--text-primary, #e0e0e0);
  font-size: var(--text-xs, 12px);
  font-family: var(--font-mono, monospace);
}

.dep-status {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--radius-full, 12px);
}

.dep-status.ok {
  background: color-mix(in srgb, var(--success, #4caf50) 20%, transparent);
  color: var(--success, #4caf50);
}

.dep-status.error {
  background: color-mix(in srgb, var(--error, #f44336) 20%, transparent);
  color: var(--error, #f44336);
}

.dep-status.pending {
  background: color-mix(in srgb, var(--accent, #7c5cfc) 20%, transparent);
  color: var(--accent, #7c5cfc);
}

.install-error {
  margin-top: var(--space-3, 12px);
  padding: var(--space-2, 8px);
  background: color-mix(in srgb, var(--error, #f44336) 15%, transparent);
  border: 1px solid color-mix(in srgb, var(--error, #f44336) 30%, transparent);
  border-radius: var(--radius-md, 8px);
  color: var(--error, #f44336);
  font-size: var(--text-xs, 12px);
  white-space: pre-wrap;
}

.deps-footer {
  padding: 14px var(--space-5, 20px);
  border-top: 1px solid var(--border-color, #2a2a4a);
}

.footer-actions {
  display: flex;
  gap: var(--space-2, 8px);
  justify-content: flex-end;
}

.btn-dismiss {
  background: var(--bg-hover, rgba(255,255,255,0.05));
  border: 1px solid var(--border-color, #2a2a4a);
  color: var(--text-primary, #e0e0e0);
  padding: var(--space-2, 8px) 18px;
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  font-size: var(--text-xs, 12px);
  font-weight: 500;
}

.btn-install {
  background: var(--accent, #7c5cfc);
  border: none;
  color: white;
  padding: var(--space-2, 8px) var(--space-5, 20px);
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  font-size: var(--text-xs, 12px);
  font-weight: 600;
}

.btn-installing {
  background: var(--bg-hover, rgba(255,255,255,0.05));
  border: 1px solid var(--accent, #7c5cfc);
  color: var(--text-muted, #707080);
  padding: var(--space-2, 8px) var(--space-5, 20px);
  border-radius: var(--radius-md, 8px);
  cursor: not-allowed;
  font-size: var(--text-xs, 12px);
  font-weight: 600;
}

.btn-continue {
  background: var(--success, #4caf50);
  border: none;
  color: white;
  padding: var(--space-2, 8px) var(--space-5, 20px);
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  font-size: var(--text-sm, 14px);
  font-weight: 600;
}
</style>
