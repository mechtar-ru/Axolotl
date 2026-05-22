<script setup lang="ts">
import BlockBase from './BlockBase.vue'

const props = defineProps<{
  id: string
  label?: string
  type?: string
  selected?: boolean
  iteration?: string
  data?: {
    label?: string
    type?: string
    config?: Record<string, unknown>
    status?: string
  }
}>()

const blockColor = '#f59e0b'
const blockIcon = 'M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 14l2 2 4-4'
</script>

<template>
  <div class="review-block-root">
    <BlockBase
      :id="id"
      :label="data?.label || label || 'Review'"
      type="review"
      :color="blockColor"
      :icon="blockIcon"
      :status="data?.status"
      :selected="selected"
    />
    <span v-if="iteration" class="iteration-badge">{{ iteration }}</span>
  </div>
</template>

<style scoped>
.review-block-root {
  position: relative;
}
</style>
