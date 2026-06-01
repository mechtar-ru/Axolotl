# EIOS Implementation Plan

## Goal
Make Axolotl capable of building the EIOS (Emotional Intelligence Operating System) app — and any complex multi-session app — reliably, without manual intervention.

## Root Causes (from EIOS GAP_ANALYSIS.md + Session 2 manual fixes)

| # | Problem | Impact | Session Evidence |
|---|---------|--------|------------------|
| P0 | Model returns empty response ("No choices"), stage marked `completed` instead of `failed` | Silent data loss, pipeline continues with empty context | think-1 completed with error, verify-1 stuck on same empty response |
| P0 | No model fallback chain when primary fails | Single point of failure; whole pipeline blocks on one model's rate limit | Nemotron-3 rate limited → no Session 2 |
| P0 | API error not classified — `No choices`, `429`, `5xx` all crash with same `RuntimeException` | No retry, no fallback, opaque error | CustomLlmProvider logs "No choices in response", stage stays `running` forever |
| P1 | Cross-session context missing: Session 2 doesn't know what Session 1 built | Agent regenerates from scratch instead of modifying | Session 2 had no file tree context in system prompt |
| P2 | requireDiffReview boolean not persisted through pipeline update PUT | Feature flag has no effect | Session 2 had requireDiffReview=true but no diff review triggered |
| P2 | Token tracking broken for OpenRouter: totalTokens=0 for all CustomLlmProvider calls | No cost visibility | All EIOS runs show 0 tokens |
| P3 | requireDiffReview triggers only on agent `file_write` to existing files — no guarantee agent modifies existing files | Feature depends on LLM behavior, not pipeline guarantee | Agent may create new files instead of editing existing ones |

## Implementation Plan

### Phase 1: Model Resilience (P0, P1) — ~5 days

#### 1.1 Model Fallback Chain

**Backend** — `PipelineService.java`, `LlmService.java`, `NodeRouter.java`

```java
// Stage config (new fields)
class StageConfig {
    String model;                    // primary model
    List<String> fallbackModels;     // ordered fallback chain
    int maxRetries;                  // per-model retries (default: 3)
    boolean skipModelOnFailure;      // skip to next fallback on non-retryable error (default: true)
}
```

**Implementation:**

1. **Add `fallbackModels` to `Stage.java`** — `List<String>` field, persisted in Neo4j as `StageConfig`
2. **Modify `PipelineService.resolveStageModel()`** — return primary model first; expose `resolveStageModelWithFallback()` returning `Iterator<String>`
3. **Modify `NodeRouter.executeNode()`** — wrap LLM call in retry loop:
   - For each model in (primary + fallbacks):
     - Retry up to `maxRetries` times with exponential backoff (1s, 2s, 4s, 8s)
     - On `No choices`, `429`, `5xx`, empty response → log warning, try next fallback
     - On `4xx` (auth, bad request) → fail immediately
4. **Add `POST /api/schemas/{id}/stage/{stageId}/fallback-models` endpoint** — configure fallback chain via API
5. **Default fallback chain in `SettingsService.initDefaults()`**:
   ```yaml
   defaultFallbackModels:
     - openrouter:nvidia/nemotron-3-super-120b-a12b:free
     - @cf/deepseek-v4-flash-free    # fallback #1
     - deepseek-v4-flash-free         # fallback #2 (Zen API)
   ```

**Frontend** — `BlockConfigPanel.vue`

1. Add "Fallback Models" section below model selector
2. Multi-model selector with drag-reorder
3. Show current active model during execution in TimelineEntry

#### 1.2 API Error Classification & Retry

**Backend** — `CustomLlmProvider.java`, `LlmService.java`

1. **Classify errors:**
   - `EMPTY_RESPONSE` — empty choices, null content (retryable)
   - `RATE_LIMITED` — HTTP 429 (retryable, longer backoff)
   - `SERVER_ERROR` — HTTP 5xx (retryable)
   - `AUTH_ERROR` — HTTP 401/403 (non-retryable, fail fast)
   - `INVALID_REQUEST` — HTTP 400 (non-retryable)
2. **Add `LlmError` enum** with `isRetryable()` and `getBackoffMs()`
3. **Modify `CustomLlmProvider.chat()`** — parse error response, return `LlmResult` with error type instead of throwing
4. **Modify `NodeRouter`** — handle `LlmResult.error` with retry/fallback logic
5. **Expose error in WebSocket events** — send `model_error` event so frontend can display:
   ```json
   {
     "type": "model_error",
     "model": "openrouter:nvidia/nemotron-3...",
     "error": "EMPTY_RESPONSE",
     "retryCount": 2,
     "fallbackTo": "@cf/deepseek-v4-flash-free"
   }
   ```

#### 1.3 Stage Failure Propagation

**Backend** — `PipelineService.java`

1. **Fix `runPipelineStages()`** — if a stage LLM call exhausts all fallbacks + retries:
   - Set stage status to `failed` (not `completed`)
   - Stop subsequent stages
   - Send `pipeline_failed` WebSocket event with error detail
2. **Add retry button for failed stages** — `POST /pipeline/retry` re-runs only failed stages (already partially implemented, needs validation)

### Phase 2: Cross-Session Context (P1) — ~3 days

#### 2.1 Session History Injection

**Backend** — `ProjectContextBuilder.java`, `PipelineService.java`

1. **After each stage completion**, persist a compact session summary in Neo4j:
   ```json
   {
     "sessionId": "ecbcb7fb...",
     "previousSessions": ["run1", "run2"],
     "generatedFiles": ["lib/screens/home_screen.dart", ...],
     "stages": [
       {"id": "think-1", "status": "completed", "outputSummary": {...}}
     ]
   }
   ```
2. **Modify `ProjectContextBuilder.buildProjectContext()`** — load last N session summaries into agent system prompt as structured context:
   ```
   ## Previous Sessions
   Session 1 (2 hours ago):
   - Generated files: main.dart, home_screen.dart, ... (1651 lines)
   - What was done: Scaffold + Data Layer (emotion_entry model, database_service)
   
   Session 2 (5 minutes ago):
   - Added: Suggested Action card in home_screen.dart
   - Added: Decompress screen (decompress_screen.dart)
   - Status: completed
   ```
3. **Add `POST /api/schemas/{id}/sessions`** — list session history for a schema
4. **Limit context** — inject only last 3 sessions, last 5 files per session (token budget: ~1000 tokens)

**Frontend** — `TimelineView.vue`

1. Add "Session Summary" tab that shows compact per-session context
2. Allow copying session context for manual reuse

### Phase 3: requireDiffReview Fix (P2) — ~2 days

#### 3.1 Boolean Serialization Fix

**Backend** — `PipelineService.java`, `SchemaService.java`

1. **Trace why `requireDiffReview` doesn't persist through `PUT /api/schemas/{id}`**:
   - Check `SchemaService.updateSchema()` — does it merge nested `stageConfig` fields correctly?
   - Check `PipelineService.syncStageConfigToNode()` — does it read `requireDiffReview` from stage?
2. **Fix merge logic** — ensure nested stage config fields are deep-merged, not overwritten

#### 3.2 Guarantee Agent Modifies Existing Files

**Backend** — `AgentNodeStrategy.java`, `ToolExecutor.java`

1. **When `requireDiffReview=true`**, inject into agent system prompt:
   ```
   CRITICAL: You MUST modify existing files (file_write on existing paths).
   Do NOT create new files unless absolutely necessary.
   The pipeline will pause for human diff review after you finish.
   ```
2. **After agent completes**, scan `ExecutionStateManager.fileChanges`:
   - If no existing files were modified (only new files created), warn in tool output:
     ```
     [WARN] requireDiffReview is enabled but no existing files were modified.
     The pipeline will still pause for review.
     ```
3. **Persist file change list** in Neo4j `NodeExecution.outputSummary` as `changedFiles`:
   ```json
   {
     "changedFiles": [
       {"path": "lib/screens/home_screen.dart", "change": "modified", "sizeDelta": 93},
       {"path": "lib/screens/decompress_screen.dart", "change": "created", "sizeDelta": 141}
     ],
     "outputSummary": "..."
   }
   ```

### Phase 4: Token Tracking for CustomLlmProvider (P2) — ~1 day

#### 4.1 Parse OpenRouter Token Usage

**Backend** — `CustomLlmProvider.java`

1. OpenRouter returns usage in SSE data or JSON response body:
   ```json
   {
     "choices": [...],
     "usage": {
       "prompt_tokens": 450,
       "completion_tokens": 1200,
       "total_tokens": 1650
     }
   }
   ```
2. **Parse `usage` field** in `CustomLlmProvider.chat()`:
   - Extract `total_tokens` from response JSON
   - Convert to `LlmUsage` object
3. **Thread through `NodeRouter`** — ensure `LlmUsage` flows to `NodeExecution.tokensUsed` (follows the pattern from WS4b/WS4c implementation)

## Effort Summary

| Phase | Days | Priority | Dependencies |
|-------|------|----------|--------------|
| 1.1 Model Fallback Chain | 3 | P0 | — |
| 1.2 API Error Classification & Retry | 1 | P0 | 1.1 (partial) |
| 1.3 Stage Failure Propagation | 1 | P0 | 1.1, 1.2 |
| 2 Cross-Session Context | 3 | P1 | — |
| 3.1 requireDiffReview Fix | 1 | P2 | — |
| 3.2 Guarantee Agent Modifies Existing | 1 | P2 | 3.1 |
| 4 Token Tracking for CustomLlmProvider | 1 | P2 | — |

**Total: ~11 days** (P0: 5d, P1: 3d, P2: 3d)

## Architecture Changes

### New/Modified Files

```
backend/src/main/java/com/agent/orchestrator/
├── llm/
│   ├── LlmError.java                  (NEW) error types with retryability
│   ├── LlmResult.java                 (NEW) result wrapper with error
│   └── CustomLlmProvider.java         (MOD) parse usage, classify errors
├── service/
│   ├── PipelineService.java           (MOD) fallback chain, failure propagation
│   ├── NodeRouter.java                (MOD) retry/fallback loop
│   ├── ProjectContextBuilder.java     (MOD) session history injection
│   └── LlmService.java               (MOD) LlmUsage accumulation
├── model/
│   └── Stage.java                     (MOD) fallbackModels field
├── controller/
│   └── AgentController.java           (MOD) fallback endpoint, sessions endpoint
└── strategy/
    └── AgentNodeStrategy.java         (MOD) requireDiffReview prompt injection

frontend/src/
├── components/
│   ├── BlockConfigPanel.vue           (MOD) fallback model selector
│   └── TimelineEntry.vue             (MOD) session summary display
├── stores/
│   └── schemaStore.ts                 (MOD) fallbackModels patch
└── types/
    └── pipeline.ts                    (MOD) fallbackModels, session types
```

## Success Criteria

1. **Nemotron-3 rate limited** → pipeline auto-falls back to `deepseek-v4-flash-free` → continues without manual intervention
2. **Session 2 knows** what Session 1 generated → agent modifies existing files instead of creating duplicates
3. **requireDiffReview** actually pauses pipeline showing files changed
4. **Token tracking** shows actual token counts for OpenRouter calls
5. **Empty API response** marks stage as `failed` (not `completed`) → user sees error → can retry

## Rollback

- Each phase independently revertible via git revert
- Fallback chain is opt-in (only applies if `fallbackModels` configured)
- Session history injection is additive (extra context, doesn't change existing behavior)
- Token tracking is read-only (no behavior change)
