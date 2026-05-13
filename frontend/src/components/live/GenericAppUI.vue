<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { appApi } from '@/services/api'

const props = defineProps<{
  appType: string
  executionResult: any
  schemaId?: string
}>()

const input = ref('')
const output = ref<string | null>(null)
const isRunning = ref(false)
const viewMode = ref<'preview' | 'raw'>('preview')
const generatedFiles = ref<Array<{path: string, description: string}>>([])
const loadingFiles = ref(false)

// Detect artifact type
type ArtifactType = 'html' | 'json' | 'script' | 'text'
const artifactType = computed<ArtifactType>(() => {
  const content = output.value
  if (!content) return 'text'
  const trimmed = content.trimStart()
  if (trimmed.startsWith('<!DOCTYPE html') || trimmed.startsWith('<html') || trimmed.startsWith('<HTML')) return 'html'
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try { JSON.parse(trimmed); return 'json' } catch { /* not JSON */ }
  }
  if (trimmed.startsWith('#!/') || trimmed.startsWith('#!')) return 'script'
  if (trimmed.includes('<script') || trimmed.includes('function ') || trimmed.includes('console.log')) return 'script'
  return 'text'
})

const artifactLabel = computed(() => {
  const labels: Record<ArtifactType, string> = { html: 'HTML', json: 'JSON', script: 'Script', text: 'Text' }
  return labels[artifactType.value]
})

const artifactSize = computed(() => {
  if (!output.value) return ''
  const bytes = new TextEncoder().encode(output.value).length
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
})

const artifactFileName = computed(() => {
  const ext: Record<ArtifactType, string> = { html: 'html', json: 'json', script: 'js', text: 'txt' }
  return `artifact.${ext[artifactType.value]}`
})

// Watch for execution results from WebSocket
watch(() => props.executionResult, async (result) => {
  if (result !== null && result !== undefined) {
    output.value = typeof result === 'string' ? result : JSON.stringify(result, null, 2)
    isRunning.value = false
    viewMode.value = 'preview'
    // Fetch generated files after execution completes
    if (props.schemaId) {
      await fetchGeneratedFiles()
    }
  }
})

function downloadArtifact() {
  if (!output.value) return
  const blob = new Blob([output.value], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = artifactFileName.value
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function run() {
  if (!input.value.trim() || isRunning.value) return
  isRunning.value = true
  output.value = null
  // Schema execution is triggered from StudioView — this is just a local simulation fallback
  setTimeout(() => {
    output.value = `App Type: ${props.appType}\nInput: ${input.value}\n\nRun from the top bar to execute the schema via WebSocket.`
    isRunning.value = false
  }, 800)
}

async function fetchGeneratedFiles() {
  if (!props.schemaId) return
  loadingFiles.value = true
  try {
    const files = await appApi.getGeneratedFiles(props.schemaId)
    generatedFiles.value = files
  } catch (error) {
    console.error('Failed to fetch generated files:', error)
  } finally {
    loadingFiles.value = false
  }
}
</script>

<template>
  <div class="generic-ui">
    <div class="io-panel">
      <div class="io-section input-section">
        <h3 class="io-title">Input</h3>
        <textarea
          v-model="input"
          class="io-textarea"
          placeholder="Enter input for your app..."
          rows="8"
          :disabled="isRunning"
        />
        <button
          class="run-action-btn"
          @click="run"
          :disabled="!input.trim() || isRunning"
        >
          <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <rect x="6" y="4" width="4" height="16" rx="1"/>
            <rect x="14" y="4" width="4" height="16" rx="1"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <path d="M8 5v14l11-7z"/>
          </svg>
          {{ isRunning ? 'Running...' : 'Run' }}
        </button>
      </div>
      
      <div class="io-divider" />
      
      <div class="io-section output-section">
        <div class="output-header">
          <h3 class="io-title" style="margin:0">Artifact</h3>
          <div class="artifact-toolbar" v-if="output">
            <span class="artifact-badge" :class="artifactType">{{ artifactLabel }}</span>
            <span class="artifact-size">{{ artifactSize }}</span>
            <button
              v-if="artifactType === 'html'"
              class="toolbar-btn"
              :class="{ active: viewMode === 'preview' }"
              @click="viewMode = 'preview'"
              title="Preview"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                <rect x="2" y="3" width="20" height="14" rx="2"/>
                <line x1="8" y1="21" x2="16" y2="21"/>
                <line x1="12" y1="17" x2="12" y2="21"/>
              </svg>
            </button>
            <button
              class="toolbar-btn"
              :class="{ active: viewMode === 'raw' }"
              @click="viewMode = 'raw'"
              title="Raw"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                <path d="M16 18l6-6-6-6"/>
                <path d="M8 6l-6 6 6 6"/>
              </svg>
            </button>
            <button class="toolbar-btn download-btn" @click="downloadArtifact" title="Download">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                <polyline points="7 10 12 15 17 10"/>
                <line x1="12" y1="15" x2="12" y2="3"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- No output -->
        <div v-if="!output" class="output-placeholder">
          <p>Run your app to see the artifact</p>
        </div>

        <!-- HTML preview -->
        <iframe
          v-else-if="artifactType === 'html' && viewMode === 'preview'"
          class="artifact-iframe"
          :srcdoc="output"
          sandbox="allow-scripts"
          title="Artifact preview"
        />

        <!-- Raw content (all types) -->
        <pre
          v-else
          class="output-content"
        >{{ output }}</pre>
      </div>
    </div>

    <!-- Generated Files Section -->
    <div v-if="generatedFiles.length > 0" class="generated-files-section">
      <h3>Generated Files</h3>
      <div class="file-list">
        <div v-for="file in generatedFiles" :key="file.path" class="file-item">
          <span class="file-icon">📄</span>
          <span class="file-path">{{ file.path }}</span>
          <span v-if="file.description" class="file-desc">{{ file.description }}</span>
        </div>
      </div>
    </div>
    <div v-else-if="loadingFiles" class="generated-files-section">
      <p class="loading-text">Loading generated files...</p>
    </div>
  </div>
</template>

<style scoped>
.generic-ui {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 2rem;
}

.io-panel {
  display: flex;
  width: 100%;
  max-width: 900px;
  height: 100%;
  gap: 1.5rem;
}

.io-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.io-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

.output-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
  gap: 0.5rem;
}

.artifact-toolbar {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.artifact-badge {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 0.15rem 0.4rem;
  border-radius: 3px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.artifact-badge.html { background: #e44d2618; color: #e44d26; }
.artifact-badge.json { background: #f7df1e18; color: #f7df1e; }
.artifact-badge.script { background: #539e4318; color: #539e43; }
.artifact-badge.text { background: var(--accent-bg); color: var(--accent); }

.artifact-size {
  font-size: 0.7rem;
  color: var(--text-muted);
  font-family: monospace;
}

.toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-secondary);
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
  transition: all 0.12s;
}

.toolbar-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
  border-color: var(--accent);
}

.toolbar-btn.active {
  background: var(--accent-bg);
  color: var(--accent);
  border-color: var(--accent);
}

.download-btn:hover {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.io-textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: none;
  font-family: monospace;
  line-height: 1.6;
}

.io-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.io-textarea:disabled {
  opacity: 0.6;
}

.run-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.75rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  align-self: flex-start;
  transition: background 0.15s;
}

.run-action-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.run-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.io-divider {
  width: 1px;
  background: var(--border-color);
  flex-shrink: 0;
}

.output-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
}

.artifact-iframe {
  flex: 1;
  width: 100%;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: white;
}

.output-content {
  flex: 1;
  padding: 0.75rem;
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
  font-family: monospace;
}

.generated-files-section {
  margin-top: 1.5rem;
  padding: 1rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.generated-files-section h3 {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  padding: 0.25rem 0;
}

.file-icon {
  font-size: 1rem;
  flex-shrink: 0;
}

.file-path {
  font-family: monospace;
  color: var(--text-primary);
}

.file-desc {
  color: var(--text-muted);
  font-size: 0.8rem;
}

.loading-text {
  color: var(--text-muted);
  font-size: 0.85rem;
}
</style>
