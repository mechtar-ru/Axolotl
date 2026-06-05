<script setup lang="ts">
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

const blockColor = '#22c55e' // green
const blockIcon = 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253'
</script>

<template>
  <BlockBase
    :id="id"
    :label="data?.label || label || 'Doc-Agent'"
    type="agent"
    :color="blockColor"
    :icon="blockIcon"
    :status="data?.status"
    :selected="selected"
  />
</template>
