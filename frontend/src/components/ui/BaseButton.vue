<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  disabled?: boolean
  loading?: boolean
}>(), {
  variant: 'primary',
  disabled: false,
  loading: false,
})

const emit = defineEmits<{
  click: []
}>()
</script>

<template>
  <button
    class="base-btn"
    :class="[`base-btn--${variant}`]"
    :disabled="disabled || loading"
    @click="emit('click')"
  >
    <span v-if="loading" class="base-btn__spinner" />
    <slot />
  </button>
</template>

<style scoped>
.base-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--transition), background var(--transition);
  line-height: 1.4;
}
.base-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.base-btn--primary {
  background: var(--accent-gradient);
  color: white;
}
.base-btn--primary:hover:not(:disabled) {
  opacity: 0.9;
}
.base-btn--secondary {
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}
.base-btn--secondary:hover:not(:disabled) {
  background: var(--bg-hover);
}
.base-btn--danger {
  background: var(--error);
  color: white;
}
.base-btn--danger:hover:not(:disabled) {
  background: var(--error-hover);
}
.base-btn--ghost {
  background: transparent;
  color: var(--text-muted);
  padding: var(--space-1) var(--space-2);
}
.base-btn--ghost:hover:not(:disabled) {
  color: var(--text-primary);
}
.base-btn__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: base-spin 0.6s linear infinite;
}
@keyframes base-spin { to { transform: rotate(360deg); } }
</style>
