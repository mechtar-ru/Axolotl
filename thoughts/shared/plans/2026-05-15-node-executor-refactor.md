# Plan: NodeExecutor → NodeRouter Refactor

**Date:** 2026-05-15
**Status:** draft
**Based on design:** `thoughts/shared/designs/2026-05-15-node-executor-refactor-design.md`

## Overview

Extract ~2700 lines of routing logic from `NodeExecutor.java` (2913 lines) into a new `NodeRouter.java` class. After refactor, `NodeExecutor` becomes ~200 lines: constructor, stateManager delegations, @PostConstruct, `executeNode()` delegation, 9 test accessor delegations, and static constants.

## Files Modified

| File | Action |
|------|--------|
| `backend-next/src/main/java/.../service/NodeRouter.java` | **CREATE** — new class (target: ~2200 lines) |
| `backend-next/src/main/java/.../service/NodeExecutor.java` | **MODIFY** — remove fields + methods, refactor constructor, add delegations (target: ~200 lines) |

After verification in `backend-next/`, sync to `backend/` via `scripts/sync-from-test.sh`.

## Pre-flight: What Stays on NodeExecutor

These must REMAIN unchanged for backward compatibility (SchemaService + tests reference them):

### Public API methods (6)
- `getNodeResults()` — delegates to `stateManager.getNodeResults()`
- `getConditionResults()` — delegates to `stateManager.getConditionResults()`
- `getOutputFileRegistry()` — delegates to `stateManager.getOutputFileRegistry()`
- `getGeneratedFilesRegistry()` — delegates to `stateManager.getGeneratedFilesRegistry()`
- `setCurrentRunId(String, String)` — delegates to `stateManager.setCurrentRunId(...)`
- `executeNode(Node, String, AtomicBoolean, ExecutionMode, String)` — becomes 1-line delegation to `router.executeNode(...)`

### Public static final constants (5)
- `ARCHITECT_ANALYST_PROMPT` (line 1320)
- `FEATURE_DESIGNER_PROMPT` (line 1333)
- `TASK_BREAKDOWN_PROMPT` (line 1349)
- `PLANNING_WORKFLOW_USER_PROMPT` (line 1374)

### Test accessor methods (9) — stay as 1-line delegations to `router.*`
- `sanitizeCommandPublic(String)` → calls `router.sanitizeCommand(command)`
- `validateUrlPublic(String)` → calls `router.validateUrl(url)`
- `isPathAllowedPublic(String)` → calls `router.isPathAllowed(path)`
- `evaluateConditionPublic(String, Map)` → calls `router.evaluateCondition(expr, ctx)`
- `interpolateVariablesPublic(String, WorkflowSchema, Map)` → calls `router.interpolateVariables(...)`
- `buildContextBlockPublic(Map)` → calls `router.buildContextBlock(preds)`
- `writeOutputPublic(String, String, String, String)` → calls `router.writeOutput(...)`
- `sleepWithCancelPublic(long, AtomicBoolean)` → calls `router.sleepWithCancel(millis, flag)`
- `executeOutputNodePublic(Node, String, ExecutionMode)` → calls `router.executeOutputNode(...)`

### Fields
- `stateManager` (line 64) — only field that stays; needed for public API delegations + passing to NodeRouter

---

## Phase 1: Create NodeRouter.java

**Path:** `backend-next/src/main/java/com/agent/orchestrator/service/NodeRouter.java`

### 1.1 Package and imports

Same package as NodeExecutor. Copy all imports from NodeExecutor.java that the moved methods use. The minimal set includes: `LlmService`, `ExecutionWebSocketHandler`, `MemPalaceClient`, `ToolExecutor`, `TransformService`, `Neo4jSchemaRepository`, `PlanService`, `ProjectContextBuilder`, `ExecutionRepository`, `ExecutionStateManager`, `Node`, `Edge`, `WorkflowSchema`, `ExecutionMode`, `NodeExecution`, `Plan`, `Priority`, `Tool`, `ToolPermission`, `Tool.ToolResult`, `ObjectMapper`, `Path`, `Files`, `HttpClient`, `HttpRequest`, `HttpResponse`, `URI`, `InetAddress`, `Duration`, `Instant`, `ConcurrentHashMap`, `AtomicBoolean`, `Logger`, `LoggerFactory`, `Pattern`, etc.

### 1.2 Class declaration

```java
public class NodeRouter {
    private static final Logger log = LoggerFactory.getLogger(NodeRouter.class);
```

NOT `@Service` — this is a POJO created by NodeExecutor internally.

### 1.3 Fields (lines 55-63, 66-67 from NodeExecutor)

9 service fields that move from NodeExecutor:
- `LlmService llmService`
- `ExecutionWebSocketHandler webSocketHandler`
- `MemPalaceClient memPalaceClient`
- `ToolExecutor toolExecutor`
- `TransformService transformService`
- `Neo4jSchemaRepository schemaRepository`
- `PlanService planService`
- `ProjectContextBuilder projectContextBuilder`
- `ExecutionRepository executionRepository`

Plus:
- `List<String> allowedWriteDirs` (mutable, set via `@PostConstruct`)

And internal constants:
- `MAX_CONTEXT_CHARS = 4000` (line 69)
- `MAX_SUBAGENT_DEPTH = 5` (line 70)
- `BLOCKED_COMMAND_PATTERNS` (line 1609) — moves as-is
- `SCHEMA_BUILDER_SYSTEM_PROMPT` (line 1271) — moves as-is (only used by `executeSchemaBuilderNode`)

### 1.4 Constructor

```java
public NodeRouter(
    LlmService llmService,
    ExecutionWebSocketHandler webSocketHandler,
    MemPalaceClient memPalaceClient,
    ToolExecutor toolExecutor,
    TransformService transformService,
    Neo4jSchemaRepository schemaRepository,
    PlanService planService,
    ProjectContextBuilder projectContextBuilder,
    ExecutionRepository executionRepository,
    List<String> allowedWriteDirs
) {
    this.llmService = llmService;
    this.webSocketHandler = webSocketHandler;
    this.memPalaceClient = memPalaceClient;
    this.toolExecutor = toolExecutor;
    this.transformService = transformService;
    this.schemaRepository = schemaRepository;
    this.planService = planService;
    this.projectContextBuilder = projectContextBuilder;
    this.executionRepository = executionRepository;
    this.allowedWriteDirs = allowedWriteDirs;  // may be null; set via setWriteDirs() later
}
```

### 1.5 `setWriteDirs(List<String>)` setter

```java
public void setWriteDirs(List<String> dirs) {
    this.allowedWriteDirs = dirs;
}
```

Called by NodeExecutor's `@PostConstruct init()` after @Value injection.

### 1.6 `init()` method

```java
public void init() {
    toolExecutor.setWebSocketHandler(webSocketHandler);
    toolExecutor.setLlmService(llmService);
}
```

Called by NodeExecutor's `@PostConstruct init()`.

### 1.7 Methods moved (complete list with signatures)

#### A. Main dispatcher (with stateManager param)
1. `executeNode(Node, String, AtomicBoolean, ExecutionMode, String, ExecutionStateManager)` — full body from lines 124-562 of NodeExecutor; handles agent/output/command/filewrite/source/condition/transform/loop/human/guardrail/memory/fallback/subagent/schemabuilder/verifier/review node types

#### B. Node-type handler methods (private)
2. `executeAgentNode(Node, String, String, ExecutionStateManager)` — line 563 + stateManager param
3. `executeToolAgentNode(Node, String, String, ExecutionStateManager)` — line 652 + stateManager param
4. `simulateAgentNode(Node, String)` — line 802 (NO stateManager needed)
5. `analyzeAgentNode(Node, String)` — line 817 (NO stateManager needed)
6. `executeOutputNode(Node, String, ExecutionMode, ExecutionStateManager)` — line 834 + stateManager param
7. `executeSummaryReportNode(Node, String, Map, String, ExecutionStateManager)` — line 894 + stateManager param
8. `executeCommandNode(Node, String)` — line 1110 (NO stateManager needed)
9. `executeFileWriteNode(Node, String)` — line 1183 (NO stateManager needed)
10. `executeSubagentNode(Node, String, AtomicBoolean, ExecutionMode)` — line 1234 (NO stateManager needed)
11. `executeSchemaBuilderNode(Node, String, String)` — line 1382 (NO stateManager needed)
12. `executeVerifierNode(Node, String, String, ExecutionStateManager)` — line 2258 + stateManager param
13. `executeReviewNode(Node, String, String, ExecutionStateManager)` — line 2527 + stateManager param

#### C. Utility methods (package-private on original)
14. `evaluateCondition(String, Map)` — line 1588
15. `sanitizeCommand(String)` — line 1613
16. `validateUrl(String)` — line 1628
17. `isPathAllowed(String)` — line 1651
18. `fetchUrlContent(String)` — line 1665
19. `readProjectContext(String, Map)` — line 1690
20. `writeOutput(String, String, String, String)` — line 1775
21. `buildToolDefinitions(List<String>)` — line 1798
22. `buildToolInstructions(List<String>)` — line 1814
23. `buildMessagesForToolCall(List<Node.Message>)` — line 1832
24. `executeToolCall(String, Map, Node, String)` — line 2081
25. `sendUserApprovalRequest(String, String, int, int)` — line 2104
26. `sleepWithCancel(long, AtomicBoolean)` — line 2114
27. `collectPredecessorResults(WorkflowSchema, String, ExecutionStateManager)` — line 2127 + stateManager param
28. `buildContextBlock(Map)` — line 2158
29. `interpolateVariables(String, WorkflowSchema, Map)` — line 2182
30. `resolveModel(String, String, String, String)` — line 2211
31. `extractGeneratedFiles(String)` — line 2225
32. `findMatchingBracket(String, int)` — line 1937
33. `extractTopLevelObjects(String)` — line 1978
34. `extractToolCallWithRegex(String)` — line 2004
35. `extractArgumentsWithRegex(String)` — line 2047

### 1.8 Visibility considerations

On NodeExecutor, the utility methods (C) were **package-private** (no access modifier). On NodeRouter they can be **public** since NodeRouter has no subclasses in other packages. The test accessors on NodeExecutor delegate to them. Alternatively keep them package-private for encapsulation — the test accessors on NodeExecutor already provide test access.

**Recommendation:** Make all methods on NodeRouter **public** for simplicity. The encapsulation boundary is at NodeExecutor's public API.

### 1.9 stateManager parameter strategy

8 methods need `ExecutionStateManager`. These receive it as their **last parameter**. The pattern:

```java
// On NodeExecutor (before refactor) — uses field
private String executeAgentNode(Node node, String schemaId, String resolvedModel) {
    stateManager.getGeneratedFilesRegistry().put(...)  // field access
}

// On NodeRouter (after refactor) — receives param
public String executeAgentNode(Node node, String schemaId, String resolvedModel, ExecutionStateManager stateManager) {
    stateManager.getGeneratedFilesRegistry().put(...)  // parameter access
}
```

All stateManager references within the moved methods change from `stateManager` (field) to the parameter.

### 1.10 Note on `ObjectMapper`

NodeExecutor creates `new ObjectMapper()` in multiple places (e.g., line 2248 inside `extractGeneratedFiles`). These local instantiations move with their methods. No shared `ObjectMapper` field is needed.

---

## Phase 2: Modify NodeExecutor.java

### 2.1 Remove moved fields (lines 55-63, 66-67)

Delete:
```java
private final LlmService llmService;
private final ExecutionWebSocketHandler webSocketHandler;
private final MemPalaceClient memPalaceClient;
private final ToolExecutor toolExecutor;
private final TransformService transformService;
private final Neo4j4SchemaRepository schemaRepository;
private final PlanService planService;
private final ProjectContextBuilder projectContextBuilder;
private final ExecutionRepository executionRepository;

@Value("${axolotl.sandbox.allowedWriteDirs:.}")
private java.util.List<String> allowedWriteDirs;

private static final int MAX_CONTEXT_CHARS = 4000;
private static final int MAX_SUBAGENT_DEPTH = 5;
```

### 2.2 Keep `stateManager` field (line 64)

```java
private final ExecutionStateManager stateManager;
```

### 2.3 Add `router` field

```java
private final NodeRouter router;
```

### 2.4 Refactor constructor (lines 72-92)

Replace the 10-assignment body with:

```java
public NodeExecutor(LlmService llmService,
                    ExecutionWebSocketHandler webSocketHandler,
                    MemPalaceClient memPalaceClient,
                    ToolExecutor toolExecutor,
                    TransformService transformService,
                    Neo4j4SchemaRepository schemaRepository,
                    PlanService planService,
                    ProjectContextBuilder projectContextBuilder,
                    ExecutionRepository executionRepository,
                    ExecutionStateManager stateManager) {
    this.stateManager = stateManager;
    this.router = new NodeRouter(
        llmService, webSocketHandler, memPalaceClient,
        toolExecutor, transformService, schemaRepository,
        planService, projectContextBuilder, executionRepository,
        null  // allowedWriteDirs — set in @PostConstruct
    );
}
```

The constructor signature stays **identical** — backward compatible with all tests and Spring DI.

### 2.5 Refactor `@PostConstruct init()` (lines 94-98)

Replace with:

```java
@PostConstruct
void init() {
    router.setWriteDirs(allowedWriteDirs);
    router.init();
}
```

This sets the @Value-injected `allowedWriteDirs` on the router (now safe to access), then calls router's init which wires toolExecutor.

### 2.6 Remove `@Value` import

Since `allowedWriteDirs` is still a field (it's used in the @PostConstruct), the `@Value` annotation and field stay. Wait — actually, `allowedWriteDirs` stays on NodeExecutor because it's injected here and passed to NodeRouter in init(). So the field stays:

```java
@Value("${axolotl.sandbox.allowedWriteDirs:.}")
private java.util.List<String> allowedWriteDirs;
```

### 2.7 Refactor `executeNode()` (line 124)

Replace the entire body (lines 124-562) with:

```java
public void executeNode(Node node, String schemaId, AtomicBoolean cancelFlag,
                        ExecutionMode mode, String resolvedModel) {
    router.executeNode(node, schemaId, cancelFlag, mode, resolvedModel, stateManager);
}
```

### 2.8 Delete all moved methods

Delete lines 563-2913 (the entire body from `executeAgentNode` through `executeReviewNode` and all utility methods).

### 2.9 Regenerate test accessor methods (formerly lines 2904-2912)

These stay at the end of the file but now delegate to `router` instead of calling private methods directly:

```java
// ── test accessors (delegate to router) ──

String sanitizeCommandPublic(String command) {
    return router.sanitizeCommand(command);
}

void validateUrlPublic(String url) {
    router.validateUrl(url);
}

boolean isPathAllowedPublic(String path) {
    return router.isPathAllowed(path);
}

boolean evaluateConditionPublic(String expr, java.util.Map<String, Object> ctx) {
    return router.evaluateCondition(expr, ctx);
}

String interpolateVariablesPublic(String text, WorkflowSchema schema, java.util.Map<String, Object> preds) {
    return router.interpolateVariables(text, schema, preds);
}

String buildContextBlockPublic(java.util.Map<String, Object> preds) {
    return router.buildContextBlock(preds);
}

String writeOutputPublic(String outputType, String filePath, String fileFormat, String content) {
    return router.writeOutput(outputType, filePath, fileFormat, content);
}

boolean sleepWithCancelPublic(long millis, java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
    return router.sleepWithCancel(millis, cancelFlag);
}

String executeOutputNodePublic(Node node, String schemaId, ExecutionMode mode) {
    return router.executeOutputNode(node, schemaId, mode, stateManager);
}
```

Note: `executeOutputNodePublic` is the only test accessor that needs `stateManager` — it passes it through to `router.executeOutputNode()`.

### 2.10 Remove unused imports

After deleting all moved methods, the following imports become unused and should be removed from NodeExecutor:
- `com.fasterxml.jackson.core.JsonParser`
- `com.fasterxml.jackson.databind.JsonNode`
- `com.fasterxml.jackson.databind.ObjectMapper`
- `org.graalvm.polyglot.Context`
- `org.graalvm.polyglot.HostAccess`
- `java.net.InetAddress`
- `java.net.URI`
- `java.net.http.HttpClient`
- `java.net.http.HttpRequest`
- `java.net.http.HttpResponse`
- `java.nio.file.Files`
- `java.nio.file.Path`
- `java.time.Duration`
- `java.time.Instant`
- `java.util.ArrayList`
- `java.util.HashMap`
- `java.util.HashSet`
- `java.util.regex.Matcher`
- `java.util.regex.Pattern`
- (most of these, actually — check each against what stays)

**Keep imports:** `ExecutionStateManager`, `Node`, `ExecutionMode`, `AtomicBoolean`, `WorkflowSchema`, `Map`, `List`, `Logger`, `LoggerFactory`, `@Value`, `@Service`, `@PostConstruct`.

---

## Phase 3: SchemaService Check — No Changes Needed

Verified: SchemaService only references:
- `nodeExecutor.executeNode(...)` — unchanged API
- `nodeExecutor.setCurrentRunId(...)` — unchanged API
- `nodeExecutor.getNodeResults()` — unchanged API
- `nodeExecutor.getConditionResults()` — unchanged API
- `nodeExecutor.getOutputFileRegistry()` — unchanged API
- `nodeExecutor.getGeneratedFilesRegistry()` — unchanged API
- `NodeExecutor.ARCHITECT_ANALYST_PROMPT` — unchanged constant access

No SchemaService changes required.

---

## Phase 4: Test Compatibility Analysis

### Test constructor behavior

All 3 test files (NodeExecutorTest, NodeExecutorPersistenceTest, NodeExecutorResilienceTest) construct:
```java
new NodeExecutor(llmService, webSocketHandler, memPalaceClient,
    toolExecutor, transformService, schemaRepository, planService,
    projectContextBuilder, executionRepository)
```

**Important: The current code has a 10-param constructor (includes ExecutionStateManager).** The tests pass 9 params without stateManager. These tests currently **do not compile** against the current NodeExecutor.java. This is a pre-existing issue.

After the refactor, the constructor still takes 10 params. Two approaches to fix this:

**Chosen approach:** Add a **second constructor** on NodeExecutor for backward compatibility:

```java
// New 9-param constructor for test backward compatibility
public NodeExecutor(LlmService llmService,
                    ExecutionWebSocketHandler webSocketHandler,
                    MemPalaceClient memPalaceClient,
                    ToolExecutor toolExecutor,
                    TransformService transformService,
                    Neo4j4SchemaRepository schemaRepository,
                    PlanService planService,
                    ProjectContextBuilder projectContextBuilder,
                    ExecutionRepository executionRepository) {
    this(llmService, webSocketHandler, memPalaceClient, toolExecutor,
         transformService, schemaRepository, planService, projectContextBuilder,
         executionRepository, new ExecutionStateManager());
}
```

This delegates to the 10-param constructor with a new `ExecutionStateManager()`. The tests use an internal stateManager rather than a shared one, which is fine for unit tests.

**Note:** The `NodeExecutorResilienceTest` at line 56 calls `nodeExecutor.setCurrentRunId(...)` which delegates to `stateManager.setCurrentRunId(...)`. With the new `ExecutionStateManager()` created internally, this will work (no NPE).

### Test accessor method verification

All 9 test accessor methods delegate through NodeExecutor → NodeRouter. The tests call:
- `nodeExecutor.sanitizeCommandPublic(...)` → `router.sanitizeCommand(...)` ✓
- `nodeExecutor.validateUrlPublic(...)` → `router.validateUrl(...)` ✓
- `nodeExecutor.isPathAllowedPublic(...)` → `router.isPathAllowed(...)` ✓
- `nodeExecutor.evaluateConditionPublic(...)` → `router.evaluateCondition(...)` ✓
- `nodeExecutor.interpolateVariablesPublic(...)` → `router.interpolateVariables(...)` ✓
- `nodeExecutor.buildContextBlockPublic(...)` → `router.buildContextBlock(...)` ✓
- `nodeExecutor.writeOutputPublic(...)` → `router.writeOutput(...)` ✓
- `nodeExecutor.sleepWithCancelPublic(...)` → `router.sleepWithCancel(...)` ✓
- `nodeExecutor.executeOutputNodePublic(...)` → `router.executeOutputNode(...)` ✓

All `*Public` methods exist on NodeExecutor with identical signatures. All pass through to router. ✓

---

## Phase 5: Execution Order (Step-by-Step)

### Step 1: Create NodeRouter.java

Write the complete new file at:
`backend-next/src/main/java/com/agent/orchestrator/service/NodeRouter.java`

Contents:
- Package + imports (copy needed imports from NodeExecutor)
- Class declaration
- 9 service fields + allowedWriteDirs + constants
- Constructor (10 params: 9 services + allowedWriteDirs)
- `setWriteDirs()` setter
- `init()` method
- All 35 methods listed in Phase 1.7, with `stateManager` added to the 8 methods that need it

### Step 2: Modify NodeExecutor.java

Apply in order:
1. Remove moved field declarations (9 services, allowedWriteDirs — wait, keep allowedWriteDirs, remove 9 services)
2. Add `NodeRouter router` field
3. Refactor constructor body to create NodeRouter
4. Refactor `@PostConstruct init()` to call `router.setWriteDirs()` + `router.init()`
5. Replace `executeNode()` body with delegation
6. Delete lines 563-2903 (all moved methods)
7. Regenerate test accessor methods at end of file
8. Clean unused imports

### Step 3: Compile

```bash
cd backend-next && mvn compile
```

Fix any compilation errors (likely import-related).

### Step 4: Write a NodeRouter unit test (optional, recommended)

Add `backend-next/src/test/java/.../service/NodeRouterTest.java` with basic sanity checks (not required by scope but recommended).

### Step 5: Sync to backend

```bash
scripts/sync-from-test.sh
```

### Step 6: Compile backend

```bash
cd backend && mvn compile
```

### Step 7: Run tests

```bash
cd backend && mvn test
```

### Step 8: Start backend and verify

```bash
cd backend && mvn spring-boot:run
```

Hit a known endpoint to verify the app starts without DI errors.

---

## Risks and Mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Test constructor mismatch (9 vs 10 params) | **Certain** (pre-existing) | Add 9-param constructor that creates ExecutionStateManager internally |
| Spring DI wiring breaks | Low | Constructor signature unchanged; NodeRouter not a Spring bean |
| `allowedWriteDirs` null in NodeRouter during construction | Medium | Lazy setter pattern: constructor passes null, @PostConstruct sets it |
| Missing import after cleanup | Medium | Let compiler errors guide: add missing imports until `mvn compile` passes |
| StateManager null in test internal instance | Low | ExecutionStateManager has no required constructor params (no-arg constructor exists) |
| Test that calls `executeNode` expects state to be in NodeExecutor's maps | Low | `executeNode` already writes to `stateManager` maps; refactored method still receives and writes to the same stateManager instance |
| Thread safety of stateManager param passing | None | stateManager is a reference to the same object; no copying involved |

## Size Estimate

| Component | Estimated Lines |
|-----------|----------------|
| NodeRouter.java (total) | ~2200 lines |
| NodeExecutor.java after refactor | ~200 lines |
| Lines deleted from NodeExecutor | ~2700 |
| Lines added (NodeRouter) | ~2200 |
| Lines added (NodeExecutor modifications) | ~50 |

Net reduction: ~450 lines (due to consolidation of section headers, duplicate comments, blank lines, and removed field declarations + their constructor assignments).
