date: 2026-05-14
topic: "Quick Start — Generate Pipeline from Description + Live Tab Changes"
status: draft

## Problem Statement

Two UX gaps in the Studio workspace:

1. **Empty canvas with no guidance.** New schemas land on a blank VueFlow canvas with a block palette on the left. Users must already know to drag blocks and configure them manually. There's no "what should I do next?" moment.

2. **Live tab is noise until execution.** The "Live" tab in StudioTopBar is always visible but shows nothing useful until a pipeline has been executed. It takes up tab space and confuses new users who haven't run anything yet.

## Constraints

- Zero disruption to existing schemas and execution history
- Must reuse existing LLM prompts/logic for schema generation (avoid duplicating prompt maintenance)
- Must work within the current 3-tab Studio layout (no new top-level routes)
- Frontend-only changes preferred where possible; backend changes only for new endpoints

## Approach

**Single new backend endpoint + frontend dialog + Live tab demotion.** I'm going with this because:

1. The existing `generate-from-prompt` endpoint works but creates orphan schemas — we need an in-place variant
2. The Quick Start UX is a single self-contained dialog that's easy to add and remove
3. Demoting Live from tab to overlay is a small change that immediately improves first-run UX

**What I considered and rejected:**

- *Frontend-only using existing API* — `generate-from-prompt` creates a separate new schema in the DB, which adds noise. We'd also need to delete the generated schema after extracting its nodes, which is fragile.
- *Template gallery expansion* — templates are predefined, not AI-generated. Quick Start is specifically about dynamic generation from free-form text.

## Architecture

### Backend — `POST /api/schemas/{id}/generate-nodes`

**Request:**
```json
{
  "prompt": "Create a Sokoban game in Python with 5 levels",
  "model": "big-pickle"
}
```

**Flow:**
1. Load existing schema (for context: appType, name, existing nodes)
2. Build a system prompt from the existing `PROMPT_TO_SCHEMA_SYSTEM` constant, but constrained: ask LLM to return ONLY `nodes` and `edges` (no `name`, no `description`)
3. Call LLM, parse JSON response
4. Save the new nodes/edges onto the existing schema
5. Return updated schema

**Response:**
```json
{
  "success": true,
  "schema": { ... updated WorkflowSchema with new nodes/edges }
}
```

**Error handling:** Same as existing `generateSchemaFromPrompt` — returns `{ success: false, error: "..." }` on parse failure or empty response.

### Frontend — Components

#### `StudioTopBar.vue`

Add a "Quick Start" button between the mode tabs and the Run button:

- Sparkle icon (SVG), accent-colored (`var(--accent)`)
- Title: "Quick Start"
- `@click` emits `show-quick-start`
- Remove "Live" mode from `modes` array

Change `StudioMode` to only `'blueprint' | 'timeline'` in both `StudioTopBar.vue` and `StudioView.vue`.

#### `QuickStartDialog.vue`

New component in `frontend/src/components/studio/`:

- **Props:** `visible: boolean`, `appId: string`
- **Template:** Modal overlay with:
  - Title: "Quick Start — Describe Your App"
  - Textarea (4 rows, placeholder: "E.g. Build a Sokoban game in Python with pygame, 5 levels...")
  - Model selector dropdown (optional, fetched from settings API)
  - "Generate Pipeline" button (accent-colored, with loading spinner)
  - Results section (hidden initially, shown after generation):
    - Node count, edge count preview
    - "Add to Canvas" and "Regenerate" buttons
- **Emits:** `close`, `add-to-canvas`
- **Flow:**
  1. User types description
  2. Clicks "Generate Pipeline"
  3. Calls `schemaApi.generateNodes(appId, prompt, model)`
  4. Shows loading spinner
  5. On success: shows preview (node count, edge count) + "Add to Canvas" button
  6. "Add to Canvas" → emits `add-to-canvas` with the generated schema
  7. StudioView receives the emit, calls `schemaStore.updateSchema(response.schema)` to persist
  8. BlueprintView watch picks up the change and re-renders

#### `BlueprintView.vue`

- Add overlay section: `v-if="showExecutionOverlay"` — renders the Live execution view as an overlay within Blueprint
- `showExecutionOverlay` is provided by StudioView (true when `isRunning && activeMode !== 'timeline'`)

#### `StudioView.vue`

- `StudioMode` type: remove `'live'`
- Remove LiveView rendering from template
- Add `showExecutionOverlay` ref — true when `isRunning && activeMode === 'blueprint'`
- Add handler for Quick Start `add-to-canvas` event
- Auto-switch: when execution starts, show execution overlay in Blueprint. When execution stops, hide overlay.

### Live Tab Demotion

**What disappears:**
- "Live" tab from StudioTopBar `modes` array (no clickable tab)
- `activeMode === 'live'` rendering path in StudioView

**What stays:**
- `LiveView.vue` and all `live/` sub-components (ChatAppUI, GenericAppUI, DesignWorkspaceUI, etc.) — they get rendered as overlay within Blueprint
- `TimelineView` auto-switch (line 96-98 in StudioView) — changes to show execution overlay instead of switching to non-existent Live tab
- All execution WebSocket logic — unchanged, still works the same way

## Data Flow

```
User types description in QuickStartDialog
  ↓
Calls POST /api/schemas/{id}/generate-nodes
  ↓
Backend loads schema, calls LLM with PROMPT_TO_SCHEMA_SYSTEM
  ↓
LLM returns { nodes: [...], edges: [...] }
  ↓
Backend saves to existing schema, returns updated schema
  ↓
QuickStartDialog shows preview (N nodes, M edges)
  ↓
User clicks "Add to Canvas"
  ↓
StudioView calls schemaStore.updateSchema(updatedSchema)
  ↓
BlueprintView watch detects new nodes/edges → addNodes() + addEdges() → fitView()
```

## Error Handling

| Scenario | Handling |
|----------|----------|
| LLM returns invalid JSON | Backend returns `{ success: false, error: "Failed to parse..." }`. Dialog shows error message. |
| LLM returns empty nodes | Backend returns `{ success: false, error: "No nodes generated" }`. Dialog shows "Try a more specific description." |
| Network error | Axios interceptor catches it. Dialog shows generic error. |
| Schema not found | Backend returns 404. Dialog shows "App not found." |
| Empty prompt | Client-side validation: disable Generate button when textarea is empty. |

## Testing Strategy

- **QuickStartDialog**: Vitest — mount with visible=true, verify textarea renders, verify generate button triggers API call, verify loading state works, verify error display
- **StudioTopBar**: Verify Quick Start button renders, verify Live tab is absent
- **StudioView**: Verify execution overlay shows when isRunning, verify add-to-canvas handler calls updateSchema
- **Backend**: Unit test for new `generateNodes` method — mock LLM response, verify nodes/edges are saved to existing schema
- **Manual**: Browser test — create blank app, click Quick Start, type "Build a calculator", verify pipeline appears on canvas

## Open Questions

- Should Quick Start replace canvas contents or append? **Replace** — for now it generates a full pipeline. Append support could come later.
- Should model selector be visible or hidden behind an "Advanced" toggle? **Visible by default** — fetched from settings API, default is user's default model.
- Do we need rate limiting or token budgeting for the generation LLM call? **Not yet** — the generation is a single LLM call, typically under 1000 tokens. We can add budgeting if it becomes an issue.
