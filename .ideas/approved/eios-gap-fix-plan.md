# Axolotl Gap Fix Plan — EIOS Workflow

## Scope

Close the 6 gaps that prevented fully autonomous EIOS development via Axolotl.

## Gaps & Fixes

### P0-1: Verify `file_write` actually changed the file

**Problem**: Agent calls `file_write` but the write may fail silently, write the same content, or be hallucinated. No verification.

**Fix**: After each `file_write`:
1. Re-read the file from disk
2. Compare new content to old content (compute md5 hash before write)
3. If unchanged → emit warning in tool output
4. Include `[WRITE VERIFIED]` or `[WRITE FAILED]` status in tool call result

**File**: `ToolExecutor.java` — `handleFileWriteWithSandbox()`

**Effort**: 1h

---

### P0-2: Structured `tools` JSON in LLM API request

**Problem**: Tool definitions are text-only (`## available_tools` in system prompt). Smaller models (qwen2.5-coder:7b) can't parse text-format tool definitions into function calls. OpenAI-compatible API supports `tools` JSON array — we should use it.

**Fix**: In `FormattedChatRequestBuilder` (or equivalent OpenAI-compatible request builder):
1. Convert `List<Tool>` to JSON `tools` array in the request body
2. Each tool = `{"type": "function", "function": {"name":..., "description":..., "parameters": {...}}}`
3. Only add when tools are non-empty AND provider is OpenAI-compatible

**Files**: 
- `backend/src/main/java/com/agent/orchestrator/llm/OpenAiChatClient.java` 
- `backend/src/main/java/com/agent/orchestrator/llm/FormattedChatRequestBuilder.java` (or equivalent)

**Effort**: 3h (requires understanding the request serialization path)

---

### P0-3: Propagate `timeoutSeconds` from NodeData to stage execution

**Problem**: `PipelineService` and `SchemaService.executeWorkflow()` use hardcoded default timeout, ignoring `NodeData.timeoutSeconds`.

**Fix**: 
1. `PipelineService.runPipelineStages()` — read `timeoutSeconds` from current stage's node config, pass to `CompletableFuture.orTimeout()`
2. `SchemaService.executeWorkflow()` — same for non-pipeline execution
3. `NodeRouter.executeNode()` — use `timeoutSeconds` from config, fallback to `SettingsService` default if not set

**Files**:
- `backend/src/main/java/com/agent/orchestrator/service/PipelineService.java`
- `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java`

**Effort**: 2h

---

### P1-4: Rate-limit → fallback chain reliability

**Problem**: OpenRouter rate limits return empty `choices`. CustomLlmProvider's `parseResponse` may throw or return empty string, but fallback doesn't always activate.

**Fix**: The LlmService.chat() already checks for null/blank/"Error:" prefix (fixed in previous session). But we need:
1. Add `HTTP 429` detection in `retryWithBackoff()` for `CustomLlmProvider` (already done for Zen provider)
2. Verify that `RuntimeException` from `parseResponse` is NOT caught before reaching LlmService's fallback check

**Files**:
- `backend/src/main/java/com/agent/orchestrator/llm/CustomLlmProvider.java`
- `backend/src/main/java/com/agent/orchestrator/llm/LlmService.java`

**Effort**: 1h (audit + small fix)

---

### P1-5: Pipeline context carries source data between stages

**Problem**: Pipeline mode stage outputs only pass previous stage's result text, not the source files loaded by source/receive nodes. Agent in stage 2+ can't see the original project files.

**Fix**: In `PipelineService.runPipelineStages()`:
1. Collect all source node outputs into a `Map<String, Object>` persistent context
2. Pass this context to each stage's `resolveInputMappings()` 
3. AgentNodeStrategy receives source data from context (not just previous stage output)

Alternative simpler fix:
- Store the source data at the `ExecutionRun` level (as JSON in `stageOutputs` keyed with `__source__`)
- AgentNodeStrategy reads it before execution and injects into system prompt

**Files**:
- `backend/src/main/java/com/agent/orchestrator/service/PipelineService.java`
- `backend/src/main/java/com/agent/orchestrator/service/AgentNodeStrategy.java`

**Effort**: 3h

---

### P2-6: `expectedToolCall` config validation

**Problem**: Agent returns descriptive text instead of calling the required tool (e.g., returns text instead of `build_app`). No validation catches this.

**Fix**: Add `expectedToolCall` field to node config (optional):
1. In `NodeData` model, add `expectedToolCall: String` field
2. In `NodeRouter.executeNode()`, after execution, check if the expected tool was in `toolCalls`
3. If not → fail node with `"Expected tool call: ${expectedToolCall} but agent called: ${actualCalls}"`

**Files**:
- `backend/src/main/java/com/agent/orchestrator/model/NodeData.java`
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java`

**Effort**: 1h

---

## Implementation Order

1. **P0-3** (timeoutSeconds) — needed immediately to prevent stuck runs
2. **P0-1** (file_write verification) — catches agent hallucinations
3. **P0-2** (structured tools API) — enables small models to call tools
4. **P2-6** (expectedToolCall) — validation layer
5. **P1-4** (rate-limit fallback audit) — belt-and-suspenders
6. **P1-5** (pipeline context) — most complex, saved for last

## Verification

For each fix:
- `mvn compile` clean
- `mvn test` — all existing tests pass
- If applicable: run a test schema end-to-end
