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

const blockColor = '#ff9800'
const blockIcon = 'M13 7l5 5m0 0l-5 5m5-5H6'
</script>

<template>
  <BlockBase
    :id="id"
    :label="data?.label || label || 'Act'"
    type="output"
    :color="blockColor"
    :icon="blockIcon"
    :status="data?.status"
    :selected="selected"
  />
</template>
