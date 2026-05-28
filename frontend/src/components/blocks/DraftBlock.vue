<script setup lang="ts">
import { computed } from 'vue'
import BlockBase from './BlockBase.vue'

const props = defineProps<{
  id: string
  label?: string
  type?: string
  selected?: boolean
  data?: {
    label?: string
    type?: string
    config?: Record<string, unknown>
    status?: string
  }
}>()

const blockColor = '#14b8a6'
const blockIcon = 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z'

const draftType = computed(() => {
  const t = props.data?.config?.draftType as string | undefined
  return t || 'spec'
})

const draftTypeLabel = computed(() => {
  const labels: Record<string, string> = {
    spec: 'SPEC',
    plan: 'PLAN',
    ui: 'UI',
    backend: 'BACKEND'
  }
  return labels[draftType.value] || draftType.value.toUpperCase()
})

const draftColors: Record<string, string> = {
  spec: '#6366f1',
  plan: '#f59e0b',
  ui: '#ec4899',
  backend: '#3b82f6'
}

const badgeColor = computed(() => draftColors[draftType.value] || '#14b8a6')
</script>

<template>
  <div class="draft-block-root">
    <BlockBase
      :id="id"
      :label="data?.label || label || 'Draft'"
      type="draft"
      :color="blockColor"
      :icon="blockIcon"
      :status="data?.status"
      :selected="selected"
    />
    <span class="draft-type-badge" :style="{ background: badgeColor }">{{ draftTypeLabel }}</span>
  </div>
</template>

<style scoped>
.draft-block-root {
  position: relative;
}

.draft-type-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  padding: 2px 6px;
  border-radius: 4px;
  letter-spacing: 0.04em;
  line-height: 1.4;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
</style>
