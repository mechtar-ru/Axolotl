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

const blockColor = '#8b5cf6'
const blockIcon = 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z'
</script>

<template>
  <BlockBase
    :id="id"
    :label="data?.label || label || 'Verify'"
    type="verifier"
    :color="blockColor"
    :icon="blockIcon"
    :status="data?.status"
    :selected="selected"
  />
</template>
