# 53 — Magic Context Integration: RAG-Based Context Retrieval

**Status: IMPLEMENTED**

## Problem
Every node execution dumps ALL predecessor outputs verbatim into the system
prompt via `collectPredecessorResults()` + `buildContextBlock()`. This is the
"full context push" — no curation, no signal filtering, no relevance scoring.

For a 9-stage pipeline, the agent receives every prior node's full output,
often exceeding 20K-50K tokens of irrelevant context.

## Solution
Replace flat predecessor concatenation with **RAG-retrieved context from
Magic Context**. Auto-index node outputs on completion, auto-retrieve relevant
memories on node start, and slot the results into ContextAssembler as a MEDIUM
priority block.

```
Node completes → ctx_memory(output) →  Magic Context (Bun/SQLite)
                                          ↑
Node starts   → ctx_search(query)   →  RAG-retrieved memories
                                          ↓
                                   ContextAssembler
                                   [CRITICAL: system + user prompt]
                                   [HIGH: plan steps, tools, diff review]
                                   [MEDIUM: MC RAG results] or [flat predecessors]
                                   [LOW: project context]
                                   [EXPERIMENTAL: mempalace]
```

ContextAssembler stays — its priority tiers, token budget, truncation, and
observability are still valuable. Only the flat predecessor block is replaced.

## Changes

### Phase 1 — MagicContextIndexer ✅
`backend/src/main/java/com/agent/orchestrator/service/MagicContextIndexer.java`

New service: indexes node outputs into Magic Context after each node completes.
- Uses `ToolExecutor` (not `PluginBridge` directly) to call `ctx_memory`
- Runs asynchronously on a dedicated daemon thread pool
- Checks `isAvailable()` — only indexes when MC plugin is loaded
- Truncates outputs > 10K chars to avoid bloat
- Tags content with `[schema:X][node:Y][type:Z][name:W]` prefix for search

### Phase 2 — MagicContextRetriever ✅
`backend/src/main/java/com/agent/orchestrator/service/MagicContextRetriever.java`

New service: retrieves relevant context from Magic Context via RAG search.
- Uses `ToolExecutor` to call `ctx_search` with query + schema scope
- Formats results as readable text block (plain text or JSON array parsing)
- Returns empty string when MC unavailable → triggers flat fallback
- Convenience overload with default 5 results

### Phase 3 — AgentNodeStrategy Wiring ✅

Both `executeAgentNode()` and `executeToolAgentNode()`:
1. **Before assembly**: call `mcRetriever.retrieveRelevantContext(query, schemaId)`
2. **Replace predecessor block**: if MC results non-empty → add as MEDIUM
   ContextBlock "mcContext"; else → fall back to flat `predecessorResults` block
3. **Indexing moved to centralized location** (see Phase 4)

### Phase 4 — Centralized Indexing in NodeRouter ✅

Instead of wiring into 5 strategy constructors (Review, Verifier, Draft,
SchemaBuilder), indexing is done **centrally in `NodeRouter.executeNode()`**
after result is stored (line ~260). This covers ALL node types in one place.

- Indexer called after `node.getData().setResult(result)`
- Guards: `result != null && !result.isBlank() && mcIndexer.isAvailable()`
- AgentNodeStrategy indexer calls removed (deduplication)

### Phase 5 — Graceful Fallback ✅

No separate `axolotl.context.magic-context.enabled` feature flag needed. The
existing plugin system config (`axolotl.plugins.enabled`) handles it:

- `isAvailable()` checks: `pluginManager.isEnabled() && toolExecutor.getTool("ctx_*") != null`
- When MC unavailable → retriever returns "" → strategy falls back to flat block
- When MC available → RAG retrieval replaces flat block automatically
- Fully self-discovering, no config changes needed

## Design Decisions vs Original Plan

| Aspect | Plan | Actual | Reason |
|--------|------|--------|--------|
| Bridge access | PluginBridge directly | ToolExecutor | Reuses existing tool dispatch, decouples from Bridge internals |
| Indexing location | Per-strategy | Centralized in NodeRouter | Covers ALL node types without constructor changes |
| Feature flag | Separate config | Self-discovering via tool presence | Plugin system already handles enable/disable |
| Non-agent strategies | Replace predecessor | Keep predecessors (they're the INPUT) | Review/Verifier/Draft use content as data, not context |

## Files Created
- `MagicContextIndexer.java` + `MagicContextIndexerTest.java` (9 tests)
- `MagicContextRetriever.java` + `MagicContextRetrieverTest.java` (9 tests)

## Files Modified
- `AgentNodeStrategy.java` — MC retrieval before assembly + replace flat block
- `NodeRouter.java` — centralized output indexing
- `AgentNodeStrategyTest.java` — added mcIndexer/mcRetriever mocks
- `NodeRouterTest.java` — added mcIndexer mock

## Test Results
- 387 tests run, 0 failures, 0 errors, 1 skipped
- Backend compile: clean
- Frontend vue-tsc: clean

## Flow Summary

```java
// Before (always):
[CRITICAL] system prompt + user prompt
[HIGH] plan steps + tools + diff review
[MEDIUM] ALL predecessor outputs verbatim (20K-50K tokens)
[LOW] project context (file tree)
[EXPERIMENTAL] mempalace

// After (MC available):
[CRITICAL] system prompt + user prompt
[HIGH] plan steps + tools + diff review
[MEDIUM] RAG-retrieved relevant memories from MC (signal, not noise)
[LOW] project context (file tree)
[EXPERIMENTAL] mempalace

// After (MC unavailable — fallback):
[CRITICAL] system prompt + user prompt
[HIGH] plan steps + tools + diff review
[MEDIUM] flat predecessor outputs (same as before)
[LOW] project context (file tree)
[EXPERIMENTAL] mempalace
```
