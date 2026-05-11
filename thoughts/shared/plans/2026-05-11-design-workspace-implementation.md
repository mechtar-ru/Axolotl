# DesignWorkspaceUI Implementation Plan

**Goal:** Create `DesignWorkspaceUI.vue` — единый дизайн-воркспейс для GAME и GENERATOR app типов, заменяющий `GameAppUI.vue`.

**Design:** `thoughts/shared/designs/2026-05-11-design-workspace.md`

**Key decisions:**
- DesignWorkspaceUI — **пассивный вьювер**, не управляет execution сам, только отображает результаты
- 3 вкладки: Concept (prompt), Review (plan + critique), Output (files + download)
- Файлы скачиваются через Blob + anchor, без серверного API
- Старый `GameAppUI.vue` удаляется

---

## Dependency Graph

```
Batch 1 (parallel): 1, 2, 3 [foundation — no deps]
Batch 2 (parallel): 4, 5 [integration — depend on Batch 1]
```

---

## Batch 1: Foundation (parallel — 3 tasks)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1: Create DesignWorkspaceUI.vue + test
**File:** `frontend/src/components/live/DesignWorkspaceUI.vue`
**Test:** `frontend/src/components/live/DesignWorkspaceUI.test.ts`
**Depends:** none

**Implementation (new file `DesignWorkspaceUI.vue`):**
- 3-tab layout: Concept / Review / Output
- Props: `appType: 'GAME' | 'GENERATOR'`, `executionResult: any`
- State: `activeTab`, `conceptPrompt`, `plan`, `critiquePrompt`, `files`, `phase`
- Concept tab: textarea + "Generate Draft" button (placeholder — execution triggered from Blueprint)
- Review tab: rendered markdown plan + critique textarea + "Refine" button
- Output tab: file list with download buttons + "Download All" button
- Download function: `downloadFile(filename, content, type)` using Blob + anchor

**Test (`DesignWorkspaceUI.test.ts`):**
- Renders with correct appType prop
- Shows Concept tab by default
- Concept tab has textarea and generate button
- Review tab shows plan when executionResult contains plan
- Output tab shows files with download buttons
- downloadFile creates Blob

**Verify:** `cd frontend && npm run test:unit -- --run src/components/live/DesignWorkspaceUI.test.ts`
**Commit:** `feat(frontend): add DesignWorkspaceUI — unified design workspace for generative app types`

---

### Task 2: Add DesignWorkspaceFile interface to types
**File:** `frontend/src/types/index.ts`
**Test:** none (type-only change)
**Depends:** none

**Implementation:**
Add `DesignWorkspaceFile` interface after line 47 (after SourceItem):
```typescript
export interface DesignWorkspaceFile {
  name: string
  content: string
  type: string // MIME type
  size?: number
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): add DesignWorkspaceFile interface for file download support`

---

### Task 3: Remove old GameAppUI.vue + test
**Files:** `frontend/src/components/live/GameAppUI.vue`, `frontend/src/components/live/GameAppUI.test.ts`
**Depends:** none

**Implementation:**
Delete both files:
- `frontend/src/components/live/GameAppUI.vue`
- `frontend/src/components/live/GameAppUI.test.ts`

**Verify:** `cd frontend && npm run type-check` (will check that no remaining imports reference GameAppUI — LiveView.vue import will be removed in Task 5)
**Commit:** `clean(frontend): remove old GameAppUI component (replaced by DesignWorkspaceUI)`

---

## Batch 2: Integration (parallel — 2 tasks)

### Task 4: Update LiveView.vue routing
**File:** `frontend/src/components/studio/LiveView.vue`
**Depends:** Task 1 (needs DesignWorkspaceUI.vue to exist), Task 3 (GameAppUI removed)

**Implementation:**
- Remove `import GameAppUI from '@/components/live/GameAppUI.vue'`
- Add `import DesignWorkspaceUI from '@/components/live/DesignWorkspaceUI.vue'`
- Update routing:
  ```vue
  <ChatAppUI v-if="appType === 'CHAT'" />
  <DocAnalyzerAppUI v-else-if="appType === 'ANALYZER'" />
  <DesignWorkspaceUI v-else-if="appType === 'GAME' || appType === 'GENERATOR'" :app-type="appType" :execution-result="executionResult" />
  <GenericAppUI v-else :app-type="appType" :execution-result="executionResult" />
  ```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): route GAME and GENERATOR to DesignWorkspaceUI in LiveView`

---

## Summary of All Changes

| # | File | Action | Batch |
|---|------|--------|-------|
| 1 | `frontend/src/components/live/DesignWorkspaceUI.vue` | NEW | 1 |
| 1 | `frontend/src/components/live/DesignWorkspaceUI.test.ts` | NEW | 1 |
| 2 | `frontend/src/types/index.ts` | EDIT (add interface) | 1 |
| 3 | `frontend/src/components/live/GameAppUI.vue` | DELETE | 1 |
| 3 | `frontend/src/components/live/GameAppUI.test.ts` | DELETE | 1 |
| 4 | `frontend/src/components/studio/LiveView.vue` | EDIT (routing) | 2 |
