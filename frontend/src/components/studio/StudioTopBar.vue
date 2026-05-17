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
  'show-generate-from-prompt': []
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
        class="generate-prompt-btn"
        @click="emit('show-generate-from-prompt')"
        title="Generate workflow from prompt"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
          <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        Generate from Prompt
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
  padding: 0 var(--space-4);
  height: 52px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
  gap: var(--space-4);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
}

.back-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
  flex-shrink: 0;
}

.back-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.app-title {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mode-tabs {
  display: flex;
  gap: var(--space-1);
  background: var(--bg-primary);
  border-radius: var(--radius-sm);
  padding: 3px;
}

.mode-tab {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
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
  gap: var(--space-2);
}

.run-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: white;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), opacity var(--transition-fast);
}

.run-btn:hover {
  background: var(--accent-hover);
}

.run-btn.running {
  background: var(--error);
  animation: pulse 1.5s ease-in-out infinite;
}

.run-btn.running:hover {
  background: var(--error-hover);
}

.quickstart-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
  white-space: nowrap;
}

.quickstart-btn:hover {
  background: var(--accent);
  color: white;
}

.generate-prompt-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: white;
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
  white-space: nowrap;
}

.generate-prompt-btn:hover {
  background: var(--accent-light);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.85; }
}
</style>
