<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { schemaApi } from '@/services/api'

const props = defineProps<{
  schemaId: string
}>()

const emit = defineEmits<{
  execute: [planText: string]
  close: []
}>()

const messages = ref<Array<{ role: string; content: string }>>([])
const inputText = ref('')
const loading = ref(false)
const chatRef = ref<HTMLElement | null>(null)
const planReady = ref(false)

async function sendMessage() {
  if (!inputText.value.trim() || loading.value) return

  const userMsg = inputText.value.trim()
  messages.value.push({ role: 'user', content: userMsg })
  inputText.value = ''
  planReady.value = false

  await scrollToBottom()
  loading.value = true

  try {
    const history = messages.value.slice(0, -1).map(m => ({ role: m.role, content: m.content }))
    const response = await schemaApi.sessionPlan(props.schemaId, userMsg, history)
    messages.value.push({ role: 'assistant', content: response.reply })
    planReady.value = true
  } catch (e) {
    messages.value.push({ role: 'assistant', content: '⚠️ Error: ' + ((e as Error).message || e) })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function startSession() {
  // Use the last assistant message as the session plan
  const reversed = [...messages.value].reverse()
  const lastAssistant = reversed.find(m => m.role === 'assistant')
  const planText = lastAssistant?.content || ''
  emit('execute', planText)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

async function scrollToBottom() {
  await nextTick()
  if (chatRef.value) {
    chatRef.value.scrollTop = chatRef.value.scrollHeight
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const response = await schemaApi.sessionPlan(props.schemaId, 'What can we build next?', [])
    messages.value.push({ role: 'assistant', content: response.reply })
  } catch (e) {
    messages.value.push({ role: 'assistant', content: 'Hello! I can see the project state and help you plan the next session. What would you like to build?' })
  } finally {
    planReady.value = true
    loading.value = false
    await scrollToBottom()
  }
})
</script>

<template>
  <Teleport to="body">
    <div class="sc-overlay" @click.self="emit('close')">
      <div class="sc-dialog">
        <!-- Header -->
        <div class="sc-header">
          <h3 class="sc-title">New Session — Plan</h3>
          <button class="sc-close" @click="emit('close')" title="Close">✕</button>
        </div>

        <!-- Chat messages -->
        <div ref="chatRef" class="sc-chat">
          <div v-for="(msg, i) in messages" :key="i"
               class="sc-msg"
               :class="msg.role === 'user' ? 'sc-msg-user' : 'sc-msg-ai'">
            <div class="sc-msg-bubble">{{ msg.content }}</div>
          </div>
          <div v-if="loading" class="sc-msg sc-msg-ai">
            <div class="sc-msg-bubble sc-typing">Thinking...</div>
          </div>
        </div>

        <!-- Input -->
        <div class="sc-input-row">
          <textarea
            v-model="inputText"
            class="sc-input"
            placeholder="Discuss what to build next..."
            rows="2"
            @keydown="handleKeydown"
            :disabled="loading"
          />
          <button class="sc-send-btn" @click="sendMessage" :disabled="loading || !inputText.trim()">
            Send
          </button>
        </div>

        <!-- Actions -->
        <div class="sc-footer">
          <button class="sc-btn-cancel" @click="emit('close')">Cancel</button>
          <button class="sc-btn-execute" @click="startSession" :disabled="loading">
            Start Session →
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sc-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.sc-dialog {
  background: var(--bg-surface, #fff);
  border-radius: 12px;
  width: 600px;
  max-width: 90vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
  overflow: hidden;
}
.sc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color, #eee);
}
.sc-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.sc-close {
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  color: var(--text-secondary, #666);
  padding: 4px 8px;
  border-radius: 4px;
}
.sc-close:hover { background: var(--bg-hover, #f0f0f0); }

.sc-chat {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  min-height: 200px;
  max-height: 400px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sc-msg {
  display: flex;
}
.sc-msg-user {
  justify-content: flex-end;
}
.sc-msg-ai {
  justify-content: flex-start;
}
.sc-msg-bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.sc-msg-user .sc-msg-bubble {
  background: var(--accent, #6366f1);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.sc-msg-ai .sc-msg-bubble {
  background: var(--bg-chat, #f0f0f5);
  color: var(--text-primary, #111);
  border-bottom-left-radius: 4px;
}
.sc-typing {
  opacity: 0.6;
}

.sc-input-row {
  display: flex;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--border-color, #eee);
}
.sc-input {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
  resize: none;
  background: var(--bg-primary, #fafafa);
  color: var(--text-primary, #111);
}
.sc-input:focus {
  outline: none;
  border-color: var(--accent, #6366f1);
}
.sc-send-btn {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  background: var(--accent, #6366f1);
  color: #fff;
  border: none;
  cursor: pointer;
  align-self: flex-end;
}
.sc-send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.sc-send-btn:hover:not(:disabled) { opacity: 0.9; }

.sc-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--border-color, #eee);
}
.sc-btn-cancel {
  padding: 8px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid var(--border-color, #ddd);
  background: transparent;
  color: var(--text-secondary, #666);
}
.sc-btn-cancel:hover { background: var(--bg-hover, #f0f0f0); }
.sc-btn-execute {
  padding: 8px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  background: var(--accent, #6366f1);
  color: #fff;
}
.sc-btn-execute:disabled { opacity: 0.5; cursor: not-allowed; }
.sc-btn-execute:hover:not(:disabled) { opacity: 0.9; }
</style>
