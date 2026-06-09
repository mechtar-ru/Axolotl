# 52 — Token Budget Optional + Disabled by Default

## Problem
Token budget (ContextAssembler) was always applied with default 8000 tokens.
This truncated context unnecessarily for most use cases — especially complex
multi-file code generation where every token of context is valuable.

The eios Flutter app run showed this: agent generated only 3 files because
context was budgeted and truncated before the agent could produce more output.

## Solution
Token budget is now opt-in. Disabled (0/null) = unlimited context.
Enabled (>0) = existing priority-based truncation behavior.

## Changes

### 1. ContextAssembler.java ✅
- `DEFAULT_BUDGET_TOKENS`: 8000 → 0
- `assemble()`: when `totalBudget <= 0`, returns ALL blocks concatenated in
  original list order without priority sorting or truncation. Total token count
  still computed for observability.

### 2. AgentNodeStrategy.java ✅
- Added `resolveBudget(Node)` helper: checks `data.contextBudgetTokens` bean
  field first, then `config.contextBudgetTokens` from config map (set by
  frontend), falls back to 0 (disabled).
- Both `executeAgentNode()` and `executeToolAgentNode()` use this helper.

### 3. ContextAssemblerTest.java ✅
- `disabledBudgetReturnsAllBlocksInOrder()` — 0 budget returns all blocks
  without truncation or priority reordering.
- `disabledBudgetWithNullBlocks()` — null blocks still return empty.

### 4. BlockConfigPanel.vue ✅
- Added `contextBudgetTokens` number input after timeout, in model section.
- Default 0, min 0, max 128000, step 100.
- Label: "Context Budget (tokens)" with hint "0 = unlimited".
- Saved to `config.contextBudgetTokens` in all 3 saveConfig paths.

## Files Changed
- `backend/src/main/java/com/agent/orchestrator/context/ContextAssembler.java`
- `backend/src/main/java/com/agent/orchestrator/service/AgentNodeStrategy.java`
- `backend/src/test/java/com/agent/orchestrator/context/ContextAssemblerTest.java`
- `frontend/src/components/studio/BlockConfigPanel.vue`

## Test Results
- ContextAssemblerTest: 10/10 pass
- AgentNodeStrategyTest: 12/12 pass
- Frontend vue-tsc: clean
- Backend compile: clean

## Status
✅ Implementation complete
