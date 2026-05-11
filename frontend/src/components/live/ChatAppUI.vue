<script setup lang="ts">
// ChatAppUI — renders chat-style interaction for CHAT-type apps
</script>

<template>
  <div class="chat-app-ui">
    <div class="chat-placeholder">
      <div class="placeholder-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="40" height="40">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </div>
      <p>Chat interface ready</p>
    </div>
  </div>
</template>

<style scoped>
.chat-app-ui {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  gap: 0.75rem;
}

.placeholder-icon {
  opacity: 0.4;
}
</style>
