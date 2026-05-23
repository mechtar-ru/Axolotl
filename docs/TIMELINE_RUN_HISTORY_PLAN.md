# Timeline → Run History Implementation Plan

## Goal
Replace the ephemeral live-event TimelineView with a persistent run history view that loads `ExecutionRun` records from Neo4j and allows per-run drill-down to `NodeExecution` results.

## Design Principles
1. **Schema = project**, each run = a development session
2. **History is persistent** — survives page reload, shows all past runs
3. **Live events append** — during execution, WS events stream into the currently running run
4. **Premortem risks mitigated** — see `PRE-297` below

---

## What to Build

### Backend (2 endpoints exist, 1 new)

```
GET  /api/schemas/{id}/runs              ← exists, returns ExecutionRun[] (newest first)
GET  /api/schemas/{id}/runs/{runId}/nodes ← exists, returns NodeExecution[]
NEW  POST /api/schemas/{id}/cleanup-runs  ← marks stale resuming→paused, deletes orphan node executions
```

The `cleanup-runs` endpoint:
- Finds all runs for a schema with `status='resuming'`
- Sets them to `status='paused'`
- Returns count of released runs
- Path: `AgentController.java`, method `releaseStuckRuns(@PathVariable String schemaId)`
- No frontend auto-call — explicit button only

### Frontend: TimelineView.vue Rewrite

**Props:**
```ts
props: {
  schemaId: string
}
```

**State (composables or local):**
```ts
const runs = ref<ExecutionRunSummary[]>([])      // latest 10
const selectedRunId = ref<string | null>(null)    // expanded run
const selectedRunNodes = ref<NodeExecution[]>([]) // cached node details
const loading = ref(false)
const expandedRunId = ref<string | null>(null)
const liveEvents = ref<StepEvent[]>([])           // WS stream buffer
const hasMore = ref(false)                        // pagination flag
```

**Types:**
```ts
interface ExecutionRunSummary {
  id: string
  schemaId: string
  status: 'running' | 'completed' | 'failed' | 'paused' | 'resuming'
  mode: 'EXECUTE' | 'PIPELINE'
  totalTokens: number
  estimatedCost: number
  error?: string
  startedAt: string
  completedAt?: string
  resumesFrom?: string
}

interface NodeExecution {
  nodeId: string
  nodeType: string
  nodeName?: string
  status: string
  durationMs: number
  tokensUsed: number
  outputSummary?: string
  filesWritten?: string[]
  error?: string
  // not needed on frontend: id, runId, configHash, timestamps, etc.
}
```

**Layout (3 zones vertical stack):**

```
┌──────────────────────────────────────────┐
│  Header                                  │
│  "Run History" + count + [Release Stale] │
├──────────────────────────────────────────┤
│  Run List (scrollable)                   │
│  ┌────────────────────────────────────┐  │
│  │ Run card #6a4e2297 (completed)     │  │
│  │ 09:25 · Node flow dots · 0 tok     │  │
│  │ ▼ expanded:                        │  │
│  │   receive-1  ● skipped             │  │
│  │   review-1   ● skipped             │  │
│  │   think-1    ● completed  "It se…" │  │
│  │   verify-1   ● completed  "FAIL…"  │  │
│  │   act-1      ● completed  "FAIL…"  │  │
│  └────────────────────────────────────┘  │
│  ┌────────────────────────────────────┐  │
│  │ Run card #c41b5e7a (paused)        │  │
│  │ 09:16 · Resume button              │  │
│  └────────────────────────────────────┘  │
│  [Show more…] if hasMore                 │
├──────────────────────────────────────────┤
│  Live Events Bar (only during execution) │
│  ● think-1: generating code…             │
└──────────────────────────────────────────┘
```

### Run Card (collapsed state)

```
[status-dot] [mode] [startedAt] [duration] [tokens] [error-tooltip]
[Node flow dots: ● ● ● ● ● ]
[Action buttons: Resume | Re-run | Delete]
```

### Run Card (expanded state)

Shows NodeExecution rows:

```
Node rows:
  [status-icon] [nodeId] [type-badge] [duration] [tokens]
  [output-preview: first 200 chars + "Show more"]
  [filesWritten if present]
  [error if present, red]
```

## Data Flow

### On Mount
1. `onMounted` → fetch `GET /runs` → filter out `resuming` → set `runs`
2. Auto-expand the latest `running` or `completed` run
3. Register WS listener for `*` events → push into `liveEvents`

### On Click (expand)
1. Set `expandedRunId`
2. Fetch `GET /runs/{runId}/nodes` → set `selectedRunNodes`
3. Show node rows inline

### During Execution (WS events)
1. WS sends `progress`, `log`, `result` events
2. Append to `liveEvents` array
3. If a run with `status=running` exists, append under its node

### On "Load More"
1. Fetch next page: `GET /runs?offset=10&limit=10`
2. Append to `runs`

---

## Files to Create/Modify

### Modified:
1. **`frontend/src/components/studio/TimelineView.vue`** — full rewrite (166 lines → ~350 lines)
2. **`frontend/src/components/studio/TimelineEntry.vue`** — adjust to accept `NodeExecution` data
3. **`backend/.../controller/AgentController.java`** — add `POST /schemas/{id}/cleanup-runs`

### New (none — reuse existing types, composables)

### Deleted (none)

---

## Premortem Mitigations (from PRE-297)

| Risk | Mitigation |
|------|------------|
| 29 runs = slow mount | Limit to latest 10, lazy-load node details only on expand |
| WS race with Neo4j | Optimistic in-memory entry on WS "started", don't refetch during execution |
| Resume on wrong paused run | Only show Resume on the **latest** paused run. Older paused runs shown with "superseded by run #X" |
| Accidental delete = data loss | Confirm dialog, prevent delete if child runs reference it via resumesFrom |
| 0 tokens looks broken | Show `—` when both tokens=0 and cost=0, tooltip: "Local models don't report token usage" |
| Cleanup side-effect | Don't auto-cleanup on mount. Only on explicit [Release Stale Runs] button click |
| Live events DOM explosion | `liveEvents` capped at 100 entries, FIFO eviction |

---

## Testing Checklist

- [ ] Empty schema (no runs) → shows clean empty state with CTA
- [ ] Schema with 1 completed run → shows 1 card, expandable to nodes
- [ ] Schema with 29 runs → shows latest 10 + "Show more"
- [ ] Schema with paused run → shows Resume button only on latest
- [ ] During execution → live events appear, no API refresh
- [ ] After execution completes → refresh shows new run at top
- [ ] Release Stale Runs button → turns resuming→paused, refreshes list
- [ ] Re-run button → calls execute API, new run appears
- [ ] Run with resumesFrom → shows "continued from #abc" label
- [ ] Tokens=0 run → shows `—` instead of "0 tokens"
- [ ] Error run → shows red error pill
- [ ] `running` status during active execution → live events stream into it

---

## Non-Goals (explicit)

- No edit/delete of individual node outputs from UI
- No comparison between runs (diff view)
- No run metrics/statistics (avg duration, pass rate)
- No run search/filter by status — future improvement
