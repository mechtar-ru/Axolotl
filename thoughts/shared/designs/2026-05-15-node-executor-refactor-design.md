---
date: 2026-05-15
topic: "NodeExecutor → NodeRouter Refactoring"
status: validated
---

## Problem Statement

`NodeExecutor.java` is 2913 lines of a single class — a violation of Single Responsibility Principle. It contains **three distinct responsibilities** mixed together:

1. **Routing** — dispatching node execution to the right handler based on node type
2. **Execution logic** — 13+ node type handlers (`executeAgentNode`, `executeVerifierNode`, etc.)
3. **Utilities** — tool call parsing, command sanitization, URL validation, context building

This makes the class:
- Hard to reason about (scroll past 2000 lines to find one method)
- Hard to test (must mock 9 injected services for any test)
- Hard to evolve (adding a new node type requires touching the monolith)
- A merge magnet (everyone working on execution touches this file)

## Constraints

- **Backward compatibility** — `SchemaService` and other consumers must not change their calls
- **Test compatibility** — existing `NodeExecutorTest` must pass with minimal changes
- **Injection compatibility** — Spring DI wiring must continue to work without config changes
- **Phased approach** — no giant PR; this is one conceptual change with clear before/after

## Approach

Extract `NodeRouter` as a `@Component` that owns all private helper methods and the execution dispatch. `NodeExecutor` becomes a thin facade (~50 lines) that delegates to `NodeRouter`.

**Why this approach, not alternatives:**

- *Alternative: extract by node type* (one class per node type) — too many files, would need restructuring of shared utilities like `parseToolCalls`, `buildContextBlock`. Better to do one extract, then optionally split further later.
- *Alternative: keep in place, add linter* — doesn't solve the problem, just documents it.
- *Alternative: extract utilities only* — leaves the core dispatch and 13 handlers in NodeExecutor, missing the main pain point.

**Key design decision:** NodeRouter is `package-private` scoped where possible. Only `executeNode()` and test accessors need to be public. NodeRouter gets all 9 injected services as constructor parameters (same as NodeExecutor has now), plus `@Value("${axolotl.sandbox.allowedWriteDirs:.}")`.

## Architecture

```
SchemaService
     |
     v
NodeExecutor (thin facade, ~50 lines)
     |
     v
NodeRouter (@Component, ~2600 lines)
     |
     +-- executeAgentNode(...)
     +-- executeToolAgentNode(...)
     +-- simulateAgentNode(...)
     +-- analyzeAgentNode(...)
     +-- executeOutputNode(...)
     +-- executeSummaryReportNode(...)
     +-- executeCommandNode(...)
     +-- executeFileWriteNode(...)
     +-- executeSubagentNode(...)
     +-- executeSchemaBuilderNode(...)
     +-- executeVerifierNode(...)
     +-- executeReviewNode(...)
     +-- evaluateCondition(...)
     +-- (all private helpers: parseToolCalls, buildContextBlock, etc.)
```

`ExecutionStateManager` stays separate (already extracted, 74 lines).

## Components

### NodeExecutor (facade, stays in same file)

What **remains** in `NodeExecutor.java`:
- `private final` fields (9 services + stateManager)
- Constructor with `@Autowired`
- `@PostConstruct init()`
- `executeNode(...)` — thin delegation to `router.executeNode(...)`
- 5 accessor delegations: `getNodeResults()`, `getConditionResults()`, `getOutputFileRegistry()`, `getGeneratedFilesRegistry()`, `setCurrentRunId()`
- All `public static final` string constants (SCHEMA_BUILDER_SYSTEM_PROMPT, ARCHITECT_ANALYST_PROMPT, prompts, etc.)
- Test accessors that delegate to router (lines 2902-2913)

### NodeRouter (new file)

What **moves** to `NodeRouter.java`:
- All execution handlers (lines 130-1500 area)
- All helper methods (lines 1500-2890 area)
- `BLOCKED_COMMAND_PATTERNS` constant
- `MAX_CONTEXT_CHARS` constant
- All private methods become package-private or public as needed

### ExecutionStateManager (already extracted)

Stays as-is. No changes needed.

## Data Flow

No change to runtime data flow. The same methods execute in the same order. Only the class boundary moves:

```
Before: SchemaService → NodeExecutor (does everything)
After:  SchemaService → NodeExecutor (facade) → NodeRouter (dispatch + handlers)
```

## Error Handling

No change. All exception handling stays with the methods — `executeNode()` in NodeRouter throws the same exceptions it did when it was part of NodeExecutor.

## Testing Strategy

1. **Compile check** — `mvn compile` must pass in `backend-next/`
2. **Existing tests** — `NodeExecutorTest` must pass; likely needs `NodeRouter` bean in context
3. **Integration test** — `ExecutionResilienceFlowIntegrationTest` and `ExecutionResumeIntegrationTest` must pass
4. **Spot check** — verify `SchemaService` still calls `nodeExecutor.executeNode(...)` correctly

## Task Breakdown for Planner

1. Create `NodeRouter.java` with all fields, constructor, and all private methods moved from NodeExecutor
2. Modify `NodeExecutor.java` — replace private methods with `NodeRouter` field + delegation
3. Verify compilation with `mvn compile`
4. Run existing tests
5. Sync back to main dirs

## Open Questions

- Test accessors (`sanitizeCommandPublic`, etc.): expose them on `NodeRouter` as `public`, leave delegating wrappers on `NodeExecutor` — verified approach
- `MAX_CONTEXT_CHARS`: keep in `NodeRouter` as `static final`. If NodeExecutor facade needs it, reference via `NodeRouter.MAX_CONTEXT_CHARS`
- `BLOCKED_COMMAND_PATTERNS`: change from `private static final` to `public static final` in NodeRouter
