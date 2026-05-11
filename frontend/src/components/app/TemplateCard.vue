<script setup lang="ts">
const props = defineProps<{
  template: {
    id: string
    name: string
    description: string
    appType: string
    icon: string
  }
}>()

const emit = defineEmits<{
  select: []
}>()

const bgColors: Record<string, string> = {
  CHAT: 'rgba(76, 175, 80, 0.1)',
  ANALYZER: 'rgba(33, 150, 243, 0.1)',
  GENERATOR: 'rgba(255, 152, 0, 0.1)',
  EMAIL: 'rgba(156, 39, 176, 0.1)',
  CUSTOM: 'rgba(108, 99, 255, 0.1)'
}

const accentColors: Record<string, string> = {
  CHAT: '#4caf50',
  ANALYZER: '#2196f3',
  GENERATOR: '#ff9800',
  EMAIL: '#9c27b0',
  CUSTOM: '#6c63ff'
}

function getBg(type: string): string {
  return bgColors[type] as string
}

function getAccent(type: string): string {
  return accentColors[type] as string
}
</script>

<template>
  <div class="template-card" @click="emit('select')">
    <div class="template-icon-wrap" :style="{ background: getBg(template.appType), color: getAccent(template.appType) }">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path :d="template.icon" />
      </svg>
    </div>
    <div class="template-info">
      <h3 class="template-name">{{ template.name }}</h3>
      <p class="template-desc">{{ template.description }}</p>
    </div>
  </div>
</template>

<style scoped>
.template-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.25rem;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s;
  display: flex;
  align-items: flex-start;
  gap: 1rem;
}

.template-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--accent);
}

.template-icon-wrap {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.template-icon-wrap svg {
  width: 24px;
  height: 24px;
}

.template-info {
  flex: 1;
  min-width: 0;
}

.template-name {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.25rem 0;
}

.template-desc {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin: 0;
  line-height: 1.4;
}
</style>
