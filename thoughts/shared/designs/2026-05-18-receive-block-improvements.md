date: 2026-05-18
topic: "Receive Block Improvements + Schema Properties Panel"
status: validated

## Problem Statement

The Receive block (Source node) has a cosmetic-only "Input Type" dropdown that doesn't control the actual input mechanism. There's no way to reference files by path, no URL fetch surfaced in the UI, and no project directory reader. Users must copy-paste large documents (e.g., `.ideas/002.md`, 5KB) into a textarea, bloating the schema JSON.

Additionally, clicking the empty canvas closes the config panel with no feedback — schema-level properties (name, target path, default model) have no quick-access panel.

## Constraints

- Backend `handleSourceNode()` must accept 4 source types: `text`, `file`, `url`, `project`
- The frontend `SourceNode.vue` has a full implementation of all 5 modes (text, memory, file, url, project) — it's unused by the current canvas but can serve as reference
- `BlockConfigPanel.vue` must stay consistent — use `v-if` sections per block type (same pattern as verifier/review sections)
- File paths should resolve relative to schema's `targetPath`
- Schema Properties panel shows when no node is selected (inverted logic of config panel)

## Approach

Two parallel work streams:

### Stream A: Receive Block Source Types
- Refactor the "Input Type" dropdown from cosmetic `blockType` variants to a real `sourceType` control stored in `config.sourceType`
- Add conditional input fields per source type (File Reference, URL Fetch, Project Directory)
- Add `"file"` handler on backend to read file from disk
- Surface existing `"url"` and `"project"` handlers in the UI

### Stream B: Schema Properties Panel
- Replace the current empty-stack on `onPaneClickHandler()` with a Schema Properties panel
- Show schema name, description, targetPath, defaultModel
- Show current project directory (targetPath) as a labeled field
- Quick action buttons: Add Node, Run, Quick Start

## Architecture

```
BlueprintView.vue
  ├── BlockPalette (existing)
  ├── VueFlow canvas (existing)
  ├── BlockConfigPanel (existing, enhanced for source types)
  │   ├── Chat/Text → textarea with source content (existing)
  │   ├── File Reference → path input + browse button (NEW)
  │   ├── URL Fetch → URL input + fetch preview (NEW)
  │   └── Project Directory → path input + browse + depth/max files (NEW)
  ├── SchemaPropertiesPanel (NEW, replaces empty state on pane click)
  │   ├── Schema name (editable)
  │   ├── Target path (displayed, links to filesystem)
  │   ├── Default model selector
  │   └── Quick action buttons
  └── LiveView / Execution Overlay (existing)
```

## Components

### 1. BlockConfigPanel.vue — Source type refactor

**Changes:**
- Add `sourceType` ref (default: `'text'`)
- Change Input Type dropdown from `v-model="blockType"` to `v-model="sourceType"`, mapping to `config.sourceType`
- Replace current 4 options with: `text`, `file`, `url`, `project`
- Add conditional sections:

```vue
<!-- Text mode -->
<div v-if="sourceType === 'text'" class="config-section">
  <label>Source Content</label>
  <textarea v-model="sourceContent" placeholder="Enter input for your app..." />
</div>

<!-- File Reference mode -->
<div v-if="sourceType === 'file'" class="config-section">
  <label>File Path</label>
  <div class="path-row">
    <input v-model="filePath" placeholder=".ideas/002.md" />
    <button @click="browseFile">📂</button>
  </div>
</div>

<!-- URL Fetch mode -->
<div v-if="sourceType === 'url'" class="config-section">
  <label>URL</label>
  <div class="url-row">
    <input v-model="url" placeholder="https://..." />
    <button @click="fetchUrlPreview" :disabled="fetching">🌐</button>
  </div>
</div>

<!-- Project Directory mode -->
<div v-if="sourceType === 'project'" class="config-section">
  <label>Project Path</label>
  <div class="path-row">
    <input v-model="projectPath" placeholder="/path/to/project" />
    <button @click="browseProjectDir">📂</button>
  </div>
  <div class="inline-fields">
    <label>Depth: <input v-model.number="maxDepth" type="number" min="1" max="10" /></label>
    <label>Max files: <input v-model.number="maxFiles" type="number" min="1" max="200" /></label>
  </div>
</div>
```

- `saveConfig()` must write sourceType and all source-specific fields to `config`
- `loadConfig()` must restore sourceType and source-specific fields from config

### 2. Backend — File source type handler

**ExecutionUtilityService.java**, add before the `"url"` branch:

```java
} else if ("file".equals(sourceType)) {
    String filePath = node.getData() != null && node.getData().getConfig() != null
            ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
    if (filePath.isEmpty()) return "Файл не указан";
    // Resolve relative to schema's targetPath
    Path resolved = Path.of(filePath);
    if (!resolved.isAbsolute()) {
        String schemaId = ...; // passed from handleSourceNode
        String targetPath = ...; // resolve from schema
        if (targetPath != null) resolved = Path.of(targetPath, filePath);
    }
    return Files.readString(resolved, StandardCharsets.UTF_8);
}
```

**Signature change**: `handleSourceNode` needs access to schema targetPath. Pass `WorkflowSchema` instead of `schemaId`, or pass `String targetPath`.

### 3. SchemaPropertiesPanel.vue (NEW)

- Rendered in BlueprintView when `!configPanelOpen && !selectedBlockId`
- Shows in the same position as BlockConfigPanel (right-side panel or as an overlay below the palette)
- Fields:
  - **Name**: text input bound to `schemaStore.currentSchema.name`
  - **Description**: textarea bound to schema description
  - **Target Path**: display-only field showing `schema.targetPath` with a folder icon (read-only for now)
  - **Default Model**: model selector dropdown (reusing existing provider model list logic)
- Quick actions:
  - **Add Node**: emits event that opens the block palette for drag
  - **Run**: triggers schema execution (same as the Run button)
  - **Quick Start**: opens QuickStartDialog

- `@pane-click="onPaneClickHandler"` in BlueprintView.vue now sets `selectedBlockId = null` but keeps a `schemaPanelOpen = true` so the panel stays visible.

### 4. BlueprintView.vue — minimal wiring

- Add `SchemaPropertiesPanel` import and registration
- Change `onPaneClickHandler`:
  ```ts
  function onPaneClickHandler() {
    selectedBlockId.value = null
    configPanelOpen.value = false
    // Schema properties panel shows by default when nothing is selected
  }
  ```
- Template: render SchemaPropertiesPanel when `!configPanelOpen && !selectedBlockId`

## Data Flow

### File Reference execution flow:
1. User selects "File Reference" mode in Receive config
2. User types `.ideas/002.md` in the file path input
3. `saveConfig()` writes `{ sourceType: "file", filePath: ".ideas/002.md" }` to node config
4. Schema is saved to backend via schemaStore
5. On execution:
   - `handleSourceNode()` reads `sourceType = "file"`
   - Resolves `.ideas/002.md` against schema's targetPath
   - Reads file content with `Files.readString()`
   - Returns content as source data to downstream nodes

### URL Fetch execution flow:
1. User selects "URL Fetch" mode, enters URL
2. Config stores URL; at execution, `handleSourceNode()` calls `fetchUrlContent(url)`

### Project Directory execution flow:
1. User selects "Project Directory" mode, enters path + depth + max files
2. At execution, `handleSourceNode()` calls `readProjectContext(path, config)`

## Error Handling

- **File not found**: Backend returns descriptive error message in Russian ("Файл не найден: ..."). Node status → FAILED.
- **File too large**: Configurable max file size (default 1MB) enforced server-side.
- **URL fetch failure**: Same as existing — returns error message, node status → FAILED.
- **Invalid project path**: Returns error, node → FAILED.
- **Frontend**: All source type fields validate on blur — invalid paths show inline error.

## Testing Strategy

### Unit tests
- `BlockConfigPanel`: test sourceType switching shows/hides correct fields
- `SchemaPropertiesPanel`: test schema name/model binding
- `ExecutionUtilityService`: test `sourceType="file"` with absolute path, relative path, missing file

### E2E tests
- Create schema with Receive block → set File Reference → verify schema persists with correct config
- Run pipeline with File Reference input → verify node completes with file content

## Open Questions

- File picker: native `<input type="file">` in browser can't return a persistent file path. The browse button should use a simulated file tree or accept an absolute path typed manually. For MVP: text input only, no native file picker. The browse button opens a text input suggesting the project tree.
- Schema Properties panel position: right side panel (same as BlockConfigPanel) or top bar? Decision: right side, same position, replaces BlockConfigPanel when no node is selected.
- Memory type: The existing `"memory"` sourceType is supported in backend but not surfaced in the current UI. Skip for now — add when MemPalace is more widely used.
