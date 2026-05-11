<template>
  <div class="theme-toggle" role="radiogroup" aria-label="Theme selector">
    <button
      v-for="opt in options"
      :key="opt.value"
      class="theme-option"
      :class="{ active: modelValue === opt.value }"
      :aria-checked="modelValue === opt.value"
      role="radio"
      @click="$emit('update:modelValue', opt.value)"
      :title="opt.label"
    >
      <!-- Sun icon -->
      <svg v-if="opt.value === 'light'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="5"/>
        <line x1="12" y1="1" x2="12" y2="3"/>
        <line x1="12" y1="21" x2="12" y2="23"/>
        <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
        <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
        <line x1="1" y1="12" x2="3" y2="12"/>
        <line x1="21" y1="12" x2="23" y2="12"/>
        <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
        <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
      </svg>
      <!-- Moon icon -->
      <svg v-else-if="opt.value === 'dark'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
      </svg>
      <!-- System/auto icon -->
      <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/>
        <line x1="8" y1="21" x2="16" y2="21"/>
        <line x1="12" y1="17" x2="12" y2="21"/>
      </svg>
      <span class="option-label">{{ opt.label }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: 'light' | 'dark' | 'system'
}>()

defineEmits<{
  'update:modelValue': [value: 'light' | 'dark' | 'system']
}>()

const options = [
  { value: 'light' as const, label: 'Light' },
  { value: 'dark' as const, label: 'Dark' },
  { value: 'system' as const, label: 'System' },
]
</script>

<style scoped>
.theme-toggle {
  display: inline-flex;
  background: var(--bg-primary);
  border: 1px solid var(--border-color, var(--border));
  border-radius: var(--radius-md);
  overflow: hidden;
}

.theme-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s ease;
  border-right: 1px solid var(--border-color, var(--border));
}

.theme-option:last-child {
  border-right: none;
}

.theme-option:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.theme-option.active {
  background: var(--accent-bg, rgba(108, 99, 255, 0.12));
  color: var(--accent);
}

.option-label {
  font-size: 12px;
  font-weight: 500;
}
</style>
