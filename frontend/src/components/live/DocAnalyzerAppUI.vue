<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'

const isDragging = ref(false)
const isProcessing = ref(false)
const progress = ref(0)
const result = ref<string | null>(null)
const fileName = ref('')

let processInterval: ReturnType<typeof setInterval> | null = null

function onDragOver(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function onDragLeave() {
  isDragging.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  
  const file = e.dataTransfer?.files[0]
  if (file) {
    processFile(file)
  }
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) {
    processFile(file)
  }
}

function processFile(file: File) {
  fileName.value = file.name
  isProcessing.value = true
  progress.value = 0
  
  // Clear any previous interval before starting new one
  if (processInterval) clearInterval(processInterval)

  // Simulate processing with progress
  processInterval = setInterval(() => {
    progress.value += 10
    if (progress.value >= 100) {
      if (processInterval) clearInterval(processInterval)
      processInterval = null
      isProcessing.value = false
      result.value = `Analysis complete for "${file.name}".\n\nFile size: ${(file.size / 1024).toFixed(1)} KB\nType: ${file.type || 'unknown'}\n\nThis is where the AI analysis results will appear once WebSocket is connected.`
    }
  }, 200)
}

onBeforeUnmount(() => {
  if (processInterval) {
    clearInterval(processInterval)
    processInterval = null
  }
})

const acceptedTypes = '.txt,.md,.pdf,.csv,.json,.xml,.html,.py,.js,.ts,.java'
</script>

<template>
  <div class="doc-analyzer">
    <div v-if="!isProcessing && !result" class="dropzone-container">
      <div
        :class="['dropzone', { dragging: isDragging }]"
        @dragover="onDragOver"
        @dragleave="onDragLeave"
        @drop="onDrop"
      >
        <div class="dropzone-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="48" height="48">
            <path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <p class="dropzone-text">Drag & drop a file here, or <span class="browse-link">browse</span></p>
        <p class="dropzone-hint">Supported: TXT, MD, PDF, CSV, JSON, code files</p>
        <input
          type="file"
          :accept="acceptedTypes"
          class="file-input"
          @change="onFileSelect"
        />
      </div>
    </div>
    
    <div v-if="isProcessing" class="processing-container">
      <div class="file-name">{{ fileName }}</div>
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: `${progress}%` }" />
      </div>
      <div class="progress-text">Analyzing... {{ progress }}%</div>
    </div>
    
    <div v-if="result" class="results-container">
      <div class="results-header">
        <h3>Analysis Results</h3>
        <button class="new-btn" @click="result = null; progress = 0">
          <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
            <path fill-rule="evenodd" d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z" clip-rule="evenodd"/>
          </svg>
          Analyze Another
        </button>
      </div>
      <pre class="result-content">{{ result }}</pre>
    </div>
  </div>
</template>

<style scoped>
.doc-analyzer {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 2rem;
}

.dropzone-container {
  width: 100%;
  max-width: 500px;
}

.dropzone {
  border: 2px dashed var(--border-color);
  border-radius: 16px;
  padding: 3rem 2rem;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
}

.dropzone:hover,
.dropzone.dragging {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.dropzone-icon {
  color: var(--text-muted);
  margin-bottom: 1rem;
}

.dropzone.dragging .dropzone-icon {
  color: var(--accent);
}

.dropzone-text {
  font-size: 1rem;
  color: var(--text-primary);
  margin: 0 0 0.5rem 0;
}

.browse-link {
  color: var(--accent);
  font-weight: 600;
  text-decoration: underline;
}

.dropzone-hint {
  font-size: 0.8rem;
  color: var(--text-muted);
  margin: 0;
}

.file-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.processing-container {
  text-align: center;
  width: 100%;
  max-width: 400px;
}

.file-name {
  font-size: 0.9rem;
  color: var(--text-primary);
  margin-bottom: 1rem;
  font-weight: 500;
}

.progress-bar {
  height: 6px;
  background: var(--bg-hover);
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 0.75rem;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: 3px;
  transition: width 0.2s;
}

.progress-text {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.results-container {
  width: 100%;
  max-width: 600px;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.results-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.results-header h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary);
}

.new-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: background 0.15s;
}

.new-btn:hover {
  background: var(--bg-hover);
}

.result-content {
  flex: 1;
  padding: 1rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
  color: var(--text-primary);
  margin: 0;
  font-family: inherit;
}
</style>
