# Receive Block Improvements + Schema Properties Panel Implementation Plan

**Goal:** Refactor Receive block source types from cosmetic variants to real sourceType control. Add file, URL, and project directory modes. Add backend file reading. Add Schema Properties panel when no node is selected.

**Architecture:** Three independent streams: (A) Backend file sourceType handler in ExecutionUtilityService, (B) Frontend BlockConfigPanel source type refactor, (C) SchemaPropertiesPanel component. BlueprintView wires stream B + C together.

**Design:** `thoughts/shared/designs/2026-05-18-receive-block-improvements.md`

---

## Dependency Graph

```
Batch 1 (parallel - 3 implementers): 1.1, 1.2, 1.3  [all independent files]
Batch 2 (parallel - 4 implementers): 2.1, 2.2, 2.3, 2.4  [depend on Batch 1]
```

**Why only 2 batches:** Each task modifies exactly ONE file. Batch 1 has no cross-file dependencies. Batch 2 consists of tests + BlueprintView wiring that depend on Batch 1 files existing. The 3 core implementation files (1.1, 1.2, 1.3) plus 4 derivative files (2.1–2.4) provide maximum parallelism.

---

## Batch 1: Core Implementation (parallel — 3 implementers)

All tasks in this batch have NO inter-dependencies and run simultaneously.

### Task 1.1: Backend — Add "file" sourceType handler to ExecutionUtilityService

**File:** `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/ExecutionUtilityServiceTest.java` (Batch 2.3)
**Depends:** none

**Implementation:**
Add a new `else if ("file".equals(sourceType))` branch in `handleSourceNode()` between the existing `"project"` branch (ending at line 1198) and the `else` (text default) branch (starting at line 1199).

The file handler:
1. Reads `filePath` from `node.getData().getConfig()`
2. Resolves relative paths against schema's `targetPath` (fetched via `schemaRepository.findById(schemaId)`)
3. Enforces max file size (1MB)
4. Returns file contents as source data or descriptive error in Russian

Replace lines 1199–1207 (the final `else` block) with the file branch + updated else:

```java
        } else if ("file".equals(sourceType)) {
            String filePath = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
            if (filePath == null || filePath.isEmpty()) {
                return "Файл не указан";
            }
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Чтение файла");
                webSocketHandler.sendLog(schemaId, "info", "Чтение файла: " + filePath, node.getId());
            }
            try {
                Path resolved = Path.of(filePath);
                if (!resolved.isAbsolute()) {
                    WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
                    if (currentSchema != null && currentSchema.getTargetPath() != null
                            && !currentSchema.getTargetPath().isBlank()) {
                        resolved = Path.of(currentSchema.getTargetPath(), filePath).normalize();
                    }
                }
                long maxSize = 1024 * 1024; // 1MB
                if (Files.size(resolved) > maxSize) {
                    return "Файл слишком большой: " + resolved.getFileName();
                }
                return Files.readString(resolved, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException e) {
                return "Файл не найден: " + filePath;
            } catch (Exception e) {
                log.error("Ошибка чтения файла {}: {}", filePath, e.getMessage());
                return "Ошибка чтения файла: " + e.getMessage();
            }
        } else {
            // text mode (default)
            if (node.getData() != null && node.getData().getSourceData() != null
                    && !node.getData().getSourceData().isEmpty()) {
                return node.getData().getSourceData();
            } else {
                return "Данные из источника: " + node.getName();
            }
        }
```

**No changes needed to NodeRouter.java** — `handleSourceNode(node, schemaId)` signature stays the same. The schema is fetched inline via `schemaRepository.findById(schemaId)`.

**Verify:** `cd backend && mvn compile`
**Commit:** `feat(backend): add file sourceType handler to handleSourceNode`

---

### Task 1.2: NEW — SchemaPropertiesPanel.vue

**File:** `frontend/src/components/studio/SchemaPropertiesPanel.vue` (NEW)
**Test:** `frontend/src/components/studio/__tests__/SchemaPropertiesPanel.test.ts` (Batch 2.4)
**Depends:** none

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useSchemaStore } from '@/stores/schemaStore'
import { storeToRefs } from 'pinia'

const emit = defineEmits<{
  addNode: []
  run: []
  quickStart: []
}>()

const schemaStore = useSchemaStore()
const { currentSchema } = storeToRefs(schemaStore)

const defaultModel = computed(() => currentSchema.value?.defaultModel || '')
const targetPath = computed(() => currentSchema.value?.targetPath || '')
const schemaName = computed(() => currentSchema.value?.name || '')
const schemaDescription = computed(() => currentSchema.value?.description || '')

function updateName(value: string) {
  if (!currentSchema.value) return
  schemaStore.updateSchema({ ...currentSchema.value, name: value })
}

function updateDescription(value: string) {
  if (!currentSchema.value) return
  schemaStore.updateSchema({ ...currentSchema.value, description: value })
}
</script>

<template>
  <div class="schema-properties-panel">
    <div class="panel-header">
      <h3>Schema Properties</h3>
    </div>

    <div class="panel-body">
      <div class="config-section">
        <label class="config-label">Name</label>
        <input
          :value="schemaName"
          @input="updateName(($event.target as HTMLInputElement).value)"
          type="text"
          class="config-input"
          placeholder="Schema name"
        />
      </div>

      <div class="config-section">
        <label class="config-label">Description</label>
        <textarea
          :value="schemaDescription"
          @input="updateDescription(($event.target as HTMLTextAreaElement).value)"
          class="config-textarea"
          placeholder="Schema description"
          rows="3"
        />
      </div>

      <div class="config-section">
        <label class="config-label">Target Path</label>
        <div class="path-display">
          <span class="folder-icon">📂</span>
          <span class="path-text">{{ targetPath || '(not set)' }}</span>
        </div>
      </div>

      <div class="config-section">
        <label class="config-label">Default Model</label>
        <div class="model-display">
          <span class="model-text">{{ defaultModel || 'Auto (user default)' }}</span>
        </div>
      </div>

      <div class="config-section quick-actions">
        <label class="config-label">Quick Actions</label>
        <button class="action-btn" @click="emit('addNode')">
          ➕ Add Node
        </button>
        <button class="action-btn action-btn--primary" @click="emit('run')">
          ▶ Run
        </button>
        <button class="action-btn action-btn--accent" @click="emit('quickStart')">
          🚀 Quick Start
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.schema-properties-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 320px;
  height: 100%;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  z-index: 20;
  display: flex;
  flex-direction: column;
  animation: slideIn 0.2s ease-out;
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.panel-header h3 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

.config-section {
  margin-bottom: var(--space-5);
}

.config-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.config-input,
.config-select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
  transition: border-color var(--transition);
}

.config-input:focus,
.config-select:focus,
.config-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.config-textarea {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
  line-height: 1.5;
}

.path-display {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-family: monospace;
  color: var(--text-primary);
}

.folder-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.path-text {
  word-break: break-all;
}

.model-display {
  padding: var(--space-2) var(--space-3);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.quick-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.action-btn {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  cursor: pointer;
  transition: background var(--transition), border-color var(--transition);
  text-align: center;
}

.action-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}

.action-btn--primary {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.action-btn--primary:hover {
  opacity: 0.9;
}

.action-btn--accent {
  background: var(--accent-secondary, #00bcd4);
  color: white;
  border-color: var(--accent-secondary, #00bcd4);
}

.action-btn--accent:hover {
  opacity: 0.9;
}
</style>
```

**Verify:** `npx vue-tsc --noEmit frontend/src/components/studio/SchemaPropertiesPanel.vue`
**Commit:** `feat(frontend): add SchemaPropertiesPanel component`

---

### Task 1.3: Frontend — Refactor BlockConfigPanel.vue source types

**File:** `frontend/src/components/studio/BlockConfigPanel.vue`
**Test:** `frontend/src/components/studio/__tests__/BlockConfigPanel.test.ts` (Batch 2.2)
**Depends:** none

**Changes:**
1. Add `sourceType` ref (default: `'text'`) and source-specific refs (`sourceContent`, `filePath`, `url`, `projectPath`, `maxDepth`, `maxFiles`, `fetching`)
2. Update `showInputType` to check `blockType === 'source'` (unchanged)
3. Change the Input Type dropdown from `v-model="blockType"` to `v-model="sourceType"` with new options: `text`, `file`, `url`, `project`
4. Add conditional sections for file, URL, project source types
5. Update `saveConfig()` to write `sourceType` and source-specific fields to `config`
6. Update `loadConfig()` (the watch) to restore `sourceType` and source-specific fields

**Script changes** — Add these refs after the existing ref block (~line 26):

```typescript
// Source type refs (for source/receive blocks)
const sourceType = ref('text')
const sourceContent = ref('')
const filePath = ref('')
const url = ref('')
const projectPath = ref('')
const maxDepth = ref(4)
const maxFiles = ref(50)
const fetching = ref(false)
```

Update `showInputType` to keep existing check (it's already correct — `blockType === 'source'`).

**Template changes** — Replace the existing "Input Type" section (lines 245-253) with:

```vue
      <!-- Input Type / Source Type (Receive blocks) -->
      <div v-if="showInputType" class="config-section">
        <label class="config-label">Input Type</label>
        <select v-model="sourceType" class="config-select" @change="saveConfig">
          <option value="text">Chat / Text</option>
          <option value="file">File Reference</option>
          <option value="url">URL Fetch</option>
          <option value="project">Project Directory</option>
        </select>
      </div>

      <!-- Text mode -->
      <div v-if="showInputType && sourceType === 'text'" class="config-section">
        <label class="config-label">Source Content</label>
        <textarea
          v-model="sourceContent"
          class="config-textarea config-textarea--large"
          placeholder="Enter input for your app..."
          rows="6"
          @input="saveConfig"
        />
      </div>

      <!-- File Reference mode -->
      <div v-if="showInputType && sourceType === 'file'" class="config-section">
        <label class="config-label">File Path</label>
        <div class="path-row">
          <input
            v-model="filePath"
            type="text"
            class="config-input"
            placeholder=".ideas/002.md"
            @input="saveConfig"
          />
          <button class="icon-btn" @click="fetchUrlPreview" title="Browse (manual path)">
            📂
          </button>
        </div>
        <div v-if="sourceType === 'file' && filePath" class="config-hint">
          Path is relative to schema target directory
        </div>
      </div>

      <!-- URL Fetch mode -->
      <div v-if="showInputType && sourceType === 'url'" class="config-section">
        <label class="config-label">URL</label>
        <div class="url-row">
          <input
            v-model="url"
            type="text"
            class="config-input"
            placeholder="https://..."
            @input="saveConfig"
          />
          <button class="icon-btn" @click="fetchUrlPreview" :disabled="fetching" title="Fetch preview">
            🌐
          </button>
        </div>
      </div>

      <!-- Project Directory mode -->
      <div v-if="showInputType && sourceType === 'project'" class="config-section">
        <label class="config-label">Project Path</label>
        <div class="path-row">
          <input
            v-model="projectPath"
            type="text"
            class="config-input"
            placeholder="/path/to/project"
            @input="saveConfig"
          />
          <button class="icon-btn" @click="browseProjectDir" title="Browse">
            📂
          </button>
        </div>
        <div class="inline-fields">
          <label class="config-label-inline">
            Depth:
            <input v-model.number="maxDepth" type="number" min="1" max="10" class="num-input" @input="saveConfig" />
          </label>
          <label class="config-label-inline">
            Max files:
            <input v-model.number="maxFiles" type="number" min="1" max="200" class="num-input" @input="saveConfig" />
          </label>
        </div>
      </div>
```

**Updated `saveConfig()`** — In the node data update section (~line 120), add source type fields. After the `systemPrompt` line (~line 128):

```typescript
      // Source/receive block config
      if (blockType.value === 'source') {
        node.value.data = {
          ...node.value.data,
          label: blockLabel.value,
          config: {
            ...((node.value.data?.config as Record<string, any>) || {}),
            sourceType: sourceType.value,
            sourceData: sourceContent.value,
            filePath: filePath.value,
            url: url.value,
            projectPath: projectPath.value,
            maxDepth: maxDepth.value,
            maxFiles: maxFiles.value,
          },
        }
      }
```

Wait — the existing code already writes to `node.value.data` in a generic way. Let me think about this more carefully.

The current `saveConfig()` always writes `label`, `config.description`, `config.model`, `config.systemPrompt`. For source blocks, we also need to write `config.sourceType`, `config.sourceData`, `config.filePath`, `config.url`, `config.projectPath`, `config.maxDepth`, `config.maxFiles`.

The cleanest approach: after the existing generic save, add source-specific overrides. Let me provide a precise edit.

In the existing `saveConfig()`, after line 130 (the generic config assignment for all nodes), and before the verifier-specific section (line 132), add:

```typescript
  // Source/receive block specific config
  if (blockType.value === 'source') {
    const sourceConfigUpdates: Record<string, any> = {
      sourceType: sourceType.value,
      sourceData: sourceContent.value,
      filePath: filePath.value,
      url: url.value,
      projectPath: projectPath.value,
      maxDepth: maxDepth.value,
      maxFiles: maxFiles.value,
    }
    Object.assign(node.value.data.config || {}, sourceConfigUpdates)
  }
```

Also update the schemaStore sync section (around line 161) similarly.

**Updated `watch` for loading config** — In the watch handler (~line 93), add after the review field assignments (after line 114):

```typescript
  // Source type fields
  const srcType = (node.value.data?.config as Record<string, any>)?.sourceType as string | undefined
  sourceType.value = srcType || 'text'
  sourceContent.value = (node.value.data?.config as Record<string, any>)?.sourceData as string
    || (node.value.data?.sourceData as string) || ''
  filePath.value = (node.value.data?.config as Record<string, any>)?.filePath as string || ''
  url.value = (node.value.data?.config as Record<string, any>)?.url as string || ''
  projectPath.value = (node.value.data?.config as Record<string, any>)?.projectPath as string || ''
  maxDepth.value = (node.value.data?.config as Record<string, any>)?.maxDepth as number || 4
  maxFiles.value = (node.value.data?.config as Record<string, any>)?.maxFiles as number || 50
```

**Add new styles** at the end of the `<style>` block:

```css
.path-row {
  display: flex;
  gap: 4px;
}
.path-row .config-input {
  flex: 1;
}
.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 16px;
  transition: background var(--transition), border-color var(--transition);
  flex-shrink: 0;
}
.icon-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}
.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.url-row {
  display: flex;
  gap: 4px;
}
.url-row .config-input {
  flex: 1;
}
.inline-fields {
  display: flex;
  gap: var(--space-4);
  margin-top: var(--space-2);
}
.config-label-inline {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
}
.num-input {
  width: 60px;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  text-align: center;
}
.config-hint {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-style: italic;
}
```

**Add stub methods** for browse/fetch in the `<script>` block (before `saveConfig`):

```typescript
function browseProjectDir() {
  // For MVP, placeholder — manual path input only
  // Electron: window.electronAPI?.showOpenDialog
}

function fetchUrlPreview() {
  // For MVP, URL preview not implemented — config is saved, backend handles at execution
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): refactor BlockConfigPanel with real sourceType control`

---

## Batch 2: Wiring + Tests (parallel — 4 implementers)

All tasks in this batch depend on Batch 1 completing (the files they reference must exist).

### Task 2.1: Frontend — Wire SchemaPropertiesPanel in BlueprintView

**File:** `frontend/src/components/studio/BlueprintView.vue`
**Test:** none (unit tests covered by child components)
**Depends:** 1.2

**Changes:**

1. Import SchemaPropertiesPanel (after existing imports, ~line 12)
2. Wire event handlers for the panel's emit events
3. Render SchemaPropertiesPanel in template (after `BlockConfigPanel`)

**Script changes** — After line 11:
```typescript
import SchemaPropertiesPanel from '@/components/studio/SchemaPropertiesPanel.vue'
```

After `configPanelOpen` ref (~line 43), add:
```typescript
const schemaPanelOpen = ref(true)
```

Update `onPaneClickHandler` (~lines 154-157) — the design says it already works as-is (sets `configPanelOpen = false`), but we need `schemaPanelOpen` to be true:

```typescript
function onPaneClickHandler() {
  selectedBlockId.value = null
  configPanelOpen.value = false
  schemaPanelOpen.value = true
}
```

Update `onNodeClickHandler` (~lines 145-151) to close schema panel when a node is selected:

```typescript
function onNodeClickHandler(event: any) {
  const node = event.node
  if (node) {
    selectedBlockId.value = node.id
    configPanelOpen.value = true
    schemaPanelOpen.value = false
  }
}
```

Add handler functions for the panel's emitted events:

```typescript
function onAddNode() {
  // Focus on palette — user can drag a new block
  // For now, no-op; palette is visible
}

function onRunSchema() {
  if (schemaStore.currentSchema) {
    schemaStore.executeSchema(schemaStore.currentSchema.id)
  }
}

function onQuickStart() {
  // Will show QuickStartDialog — the inject ref handles visibility
  // For now, we can trigger a custom event or just log
  console.log('Quick start requested')
}
```

**Template changes** — Replace the config panel section (~lines 234-238) with:

```vue
    <!-- Config Panel (node selected) -->
    <BlockConfigPanel
      v-show="!showExecutionOverlay && configPanelOpen && selectedBlockId"
      :block-id="selectedBlockId || ''"
      @close="configPanelOpen = false; schemaPanelOpen = true"
    />

    <!-- Schema Properties Panel (no node selected) -->
    <SchemaPropertiesPanel
      v-show="!showExecutionOverlay && !configPanelOpen && !selectedBlockId && schemaPanelOpen"
      @add-node="onAddNode"
      @run="onRunSchema"
      @quick-start="onQuickStart"
    />
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): wire SchemaPropertiesPanel in BlueprintView`

---

### Task 2.2: NEW — BlockConfigPanel test

**File:** `frontend/src/components/studio/__tests__/BlockConfigPanel.test.ts` (NEW)
**Test:** self
**Depends:** 1.3

```typescript
// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the API module
vi.mock('@/services/api', () => ({
  settingsApi: {
    getProviders: vi.fn().mockResolvedValue([
      { name: 'ollama', available: true, baseUrl: 'http://localhost:11434', models: ['llama3'], disabledModels: [] },
    ]),
  },
  schemaApi: {
    getSchemas: vi.fn().mockResolvedValue([]),
  },
}))

// Mock the schema store
vi.mock('@/stores/schemaStore', () => ({
  useSchemaStore: vi.fn(() => ({
    currentSchema: {
      id: 'test-1',
      name: 'Test Schema',
      nodes: [],
      edges: [],
      targetPath: '/Users/test/project',
    },
    updateSchema: vi.fn(),
  })),
}))

// Mock VueFlow
vi.mock('@vue-flow/core', () => ({
  useVueFlow: vi.fn(() => ({
    nodes: [],
    addNodes: vi.fn(),
    addEdges: vi.fn(),
    onConnect: vi.fn(),
    screenToFlowCoordinate: vi.fn(),
    fitView: vi.fn(),
  })),
}))

import BlockConfigPanel from '../BlockConfigPanel.vue'

describe('BlockConfigPanel', () => {
  const defaultProps = {
    blockId: 'source-1',
  }

  beforeEach(() => {
    // Reset store mock
    const { useSchemaStore } = require('@/stores/schemaStore')
    useSchemaStore.mockClear()
  })

  it('renders the panel header', () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    expect(wrapper.text()).toContain('Configure Block')
  })

  it('renders the block name input', () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('renders the Input Type dropdown for source blocks', () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    const select = wrapper.find('select')
    // The select exists for Input Type
    const options = wrapper.findAll('option')
    const optionTexts = options.map(o => o.text())
    expect(optionTexts).toEqual(
      expect.arrayContaining(['Chat / Text', 'File Reference', 'URL Fetch', 'Project Directory'])
    )
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t "BlockConfigPanel"`
**Commit:** `test(frontend): add BlockConfigPanel source type unit tests`

---

### Task 2.3: Backend — Add file sourceType tests to ExecutionUtilityServiceTest

**File:** `backend/src/test/java/com/agent/orchestrator/service/ExecutionUtilityServiceTest.java`
**Test:** self
**Depends:** 1.1

Add these test methods before the final closing brace of the class (before line 442):

```java
    // ── handleSourceNode - file sourceType ──

    @Test
    void handleSourceNode_returnsFileNotFound_whenEmptyPath() throws Exception {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("Файл не указан", result);
    }

    @Test
    void handleSourceNode_readsAbsolutePath() throws Exception {
        // Create temp file
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-source-", ".txt");
        java.nio.file.Files.writeString(tempFile, "file content");

        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", tempFile.toString());
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("file content", result);

        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void handleSourceNode_returnsFileNotFound_whenFileMissing() {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "/tmp/nonexistent-file-12345.txt");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertTrue(result.contains("не найден") || result.contains("not found"));
    }

    @Test
    void handleSourceNode_resolvesRelativePathAgainstTargetPath() throws Exception {
        // Create temp dir with a file inside
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-schema-");
        java.nio.file.Path testFile = tempDir.resolve("subdir/test.md");
        java.nio.file.Files.createDirectories(testFile.getParent());
        java.nio.file.Files.writeString(testFile, "relative content");

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setTargetPath(tempDir.toString());
        when(schemaRepository.findById("schema-1")).thenReturn(schema);

        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "subdir/test.md");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("relative content", result);

        // Cleanup
        java.nio.file.Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
    }
```

Add the necessary imports at the top of the file if not already present:
```java
import com.agent.orchestrator.model.WorkflowSchema;
```
(Check existing imports — `WorkflowSchema` might already be imported. Looking at line 10, yes `WorkflowSchema` is imported.)

**Verify:** `cd backend && mvn test -pl . -Dtest=ExecutionUtilityServiceTest`
**Commit:** `test(backend): add file sourceType unit tests`

---

### Task 2.4: NEW — SchemaPropertiesPanel test

**File:** `frontend/src/components/studio/__tests__/SchemaPropertiesPanel.test.ts` (NEW)
**Test:** self
**Depends:** 1.2

```typescript
// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'

// Mock the schema store
vi.mock('@/stores/schemaStore', () => ({
  useSchemaStore: vi.fn(() => ({
    currentSchema: {
      id: 'test-1',
      name: 'Test Schema',
      description: 'A test schema',
      targetPath: '/Users/test/project',
      defaultModel: 'gpt-4',
      nodes: [],
      edges: [],
    },
    updateSchema: vi.fn(),
  })),
}))

import SchemaPropertiesPanel from '../SchemaPropertiesPanel.vue'

describe('SchemaPropertiesPanel', () => {
  it('renders schema name', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('Test Schema')
  })

  it('renders schema description', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('A test schema')
  })

  it('renders target path', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('/Users/test/project')
  })

  it('renders default model', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('gpt-4')
  })

  it('renders quick action buttons', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('Add Node')
    expect(wrapper.text()).toContain('Run')
    expect(wrapper.text()).toContain('Quick Start')
  })

  it('emits addNode event when Add Node clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('addNode')).toBeTruthy()
  })

  it('emits run event when Run clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    // Find button with "Run" text
    const buttons = wrapper.findAll('button')
    const runBtn = buttons.find(b => b.text().includes('Run'))
    await runBtn?.trigger('click')
    expect(wrapper.emitted('run')).toBeTruthy()
  })

  it('emits quickStart event when Quick Start clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    const buttons = wrapper.findAll('button')
    const qsBtn = buttons.find(b => b.text().includes('Quick Start'))
    await qsBtn?.trigger('click')
    expect(wrapper.emitted('quickStart')).toBeTruthy()
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t "SchemaPropertiesPanel"`
**Commit:** `test(frontend): add SchemaPropertiesPanel unit tests`

---

## Verification Summary

| Task | File | Verify Command |
|------|------|---------------|
| 1.1 | `ExecutionUtilityService.java` | `cd backend && mvn compile` |
| 1.2 | `SchemaPropertiesPanel.vue` (NEW) | `cd frontend && npx vue-tsc --noEmit src/components/studio/SchemaPropertiesPanel.vue` |
| 1.3 | `BlockConfigPanel.vue` | `cd frontend && npm run type-check` |
| 2.1 | `BlueprintView.vue` | `cd frontend && npm run type-check` |
| 2.2 | `BlockConfigPanel.test.ts` (NEW) | `cd frontend && npm run test:unit -- -t "BlockConfigPanel"` |
| 2.3 | `ExecutionUtilityServiceTest.java` | `cd backend && mvn test -Dtest=ExecutionUtilityServiceTest` |
| 2.4 | `SchemaPropertiesPanel.test.ts` (NEW) | `cd frontend && npm run test:unit -- -t "SchemaPropertiesPanel"` |

**Full verification (all):**
```bash
cd backend && mvn compile && mvn test
cd frontend && npm run type-check && npm run test:unit
```

---

## Design Decisions & Gap Fills

| Gap in Design | Decision Made |
|---------------|---------------|
| How does `handleSourceNode` get schema targetPath? | Fetched inline via `schemaRepository.findById(schemaId)` inside the `"file"` branch — no signature change needed. |
| Max file size for file reading? | 1MB (default configurable constant). |
| How does browse button work in file/URL/project? | MVP uses manual text input only (per design). Browse button is a no-op stub for future Electron integration. |
| URL preview / fetch preview? | Not implemented in BlockConfigPanel — config is saved for backend execution. |
| Schema Properties panel position? | Right-side panel, same position as BlockConfigPanel (per design decision). |
| What does "Add Node" button do? | No-op — the palette is already visible. Future: could open node quick-add. |
| Test for BlueprintView? | Not needed — changes are minimal wiring. Child components have their own tests. |
| Commit messages language? | English for code changes (project convention: English for code). |
| `NodeRouter.java` changes? | None — `handleSourceNode(node, schemaId)` signature unchanged. |
