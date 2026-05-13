<script setup lang="ts">
import { useRouter } from 'vue-router'
import SokobanGame from '@/components/live/SokobanGame.vue'

const router = useRouter()
</script>

<template>
  <div class="game-view">
    <header class="game-header">
      <button class="btn-back" @click="router.push('/')">
        <svg width="18" height="18" viewBox="0 0 20 20" fill="currentColor"><path d="M9.707 4.293a1 1 0 010 1.414L6.414 9H17a1 1 0 110 2H6.414l3.293 3.293a1 1 0 01-1.414 1.414l-5-5a1 1 0 010-1.414l5-5a1 1 0 011.414 0z"/></svg>
        Back
      </button>
      <h1>🎮 Sokoban</h1>
      <div style="width:70px" />
    </header>
    <SokobanGame />
  </div>
</template>

<style scoped>
.game-view {
  min-height: 100vh;
  background: var(--bg-primary);
}

.game-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1.5rem;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.game-header h1 {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--text-primary);
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.875rem;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-back:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
</style>
