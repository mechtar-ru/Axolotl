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

const blockColor = '#4caf50'
const blockIcon = 'M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1'
</script>

<template>
  <BlockBase
    :id="id"
    :label="data?.label || label || 'Receive'"
    type="source"
    :color="blockColor"
    :icon="blockIcon"
    :status="data?.status"
    :selected="selected"
  />
</template>
