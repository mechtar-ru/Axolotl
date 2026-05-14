<script setup lang="ts">
type StudioMode = 'blueprint' | 'timeline'

const props = defineProps<{
  appName: string
  activeMode: StudioMode
  isRunning: boolean
}>()

const emit = defineEmits<{
  'set-mode': [mode: StudioMode]
  'toggle-run': []
  'back': []
  'show-quick-start': []
}>()

const modes: { id: StudioMode; label: string; icon: string }[] = [
  { id: 'blueprint', label: 'Blueprint', icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z' },
  { id: 'timeline', label: 'Timeline', icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' }
]
</script>

<template>
  <header class="studio-topbar">
    <div class="topbar-left">
      <button class="back-btn" @click="emit('back')" title="Back to Dashboard">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
          <path d="M19 12H5M12 19l-7-7 7-7" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
      <h1 class="app-title">{{ appName }}</h1>
    </div>
    
    <nav class="mode-tabs">
      <button
        v-for="mode in modes"
        :key="mode.id"
        :class="['mode-tab', { active: activeMode === mode.id }]"
        @click="emit('set-mode', mode.id)"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
          <path :d="mode.icon" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        {{ mode.label }}
      </button>
    </nav>
    
    <div class="topbar-right">
      <button
        class="quickstart-btn"
        @click="emit('show-quick-start')"
        title="Generate pipeline from description"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
          <path d="M13 2L12 10H21L11 22L12 14H3L13 2Z" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        Quick Start
      </button>

      <button
        :class="['run-btn', { running: isRunning }]"
        @click="emit('toggle-run')"
      >
        <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
          <rect x="6" y="4" width="4" height="16" rx="1"/>
          <rect x="14" y="4" width="4" height="16" rx="1"/>
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
          <path d="M8 5v14l11-7z"/>
        </svg>
        {{ isRunning ? 'Stop' : 'Run' }}
      </button>
    </div>
  </header>
</template>

<style scoped>
.studio-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 1rem;
  height: 52px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
  gap: 1rem;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
}

.back-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.back-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.app-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mode-tabs {
  display: flex;
  gap: 0.25rem;
  background: var(--bg-primary);
  border-radius: 8px;
  padding: 3px;
}

.mode-tab {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.mode-tab:hover {
  color: var(--text-primary);
}

.mode-tab.active {
  background: var(--bg-secondary);
  color: var(--accent);
  box-shadow: var(--shadow-sm);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.run-btn {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, opacity 0.15s;
}

.run-btn:hover {
  background: var(--accent-light);
}

.run-btn.running {
  background: #ef4444;
  animation: pulse 1.5s ease-in-out infinite;
}

.run-btn.running:hover {
  background: #dc2626;
}

.quickstart-btn {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.85rem;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: transparent;
  color: var(--accent);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  white-space: nowrap;
}

.quickstart-btn:hover {
  background: var(--accent);
  color: white;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.85; }
}
</style>
