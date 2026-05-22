<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  executionResult: any
}>()

const messages = ref<Array<{role: string, content: string}>>([])

watch(() => props.executionResult, (result) => {
  if (result !== null && result !== undefined) {
    const content = typeof result === 'string' ? result : JSON.stringify(result, null, 2)
    messages.value.push({ role: 'assistant', content })
  }
})
</script>

<template>
  <div class="chat-app-ui">
    <div v-if="messages.length === 0" class="chat-placeholder">
      <div class="placeholder-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="40" height="40">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </div>
      <p>Chat interface ready</p>
    </div>
    <div v-else class="chat-messages">
      <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
        <pre class="message-content">{{ msg.content }}</pre>
      </div>
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

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.message {
  max-width: 85%;
}

.message.assistant {
  align-self: flex-start;
  background: var(--bg-secondary);
  border-radius: 0 12px 12px 12px;
  padding: 0.75rem 1rem;
}

.message-content {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}
</style>
