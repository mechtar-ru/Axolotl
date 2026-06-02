# Axolotl Premortem Fixes Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Fix the 5 highest-impact structural vulnerabilities identified by the Premortem analysis — per-node timeout, structured WebSocket error protocol, execution state reconciler, ExecutionUtilityService split, and PipelineService split.

**Architecture:** All fixes are in `backend/src/main/java/com/agent/orchestrator/`. Each fix is independent except #3 (reconciler) depends on #0 (stateManager improvements). Tasks within each fix are ordered for test-first development.

**Tech Stack:** Java 21, Spring Boot 3.2, JUnit 5 + Mockito, Neo4j.

---

## PRE-FIX: Verify current state

Run existing tests to establish baseline:

```bash
cd /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend
mvn test -pl . -Dtest="NodeRouterTest,ExecutionUtilityServiceTest,PipelineServiceTest,ExecutionWebSocketHandlerTest" -DfailIfNoTests=false 2>&1 | tail -20
```

Expected: all pass. If any fail, note the failure for triage.

---

## FIX 1: Per-node timeout in NodeRouter

**Objective:** Wrap each node dispatch in `Future.get(timeout)` so a hanging LLM call doesn't block the thread pool indefinitely.

**Severity:** High. Without this, a hung LLM call blocks the executor thread for `HTTP_CLIENT_TIMEOUT` (30-90s) × `(autoRetry+1)` with no mid-cancel.

**Files:**
- Modify: `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java`
- Modify: `backend/src/test/java/com/agent/orchestrator/service/NodeRouterTest.java`
- Check: `backend/src/main/java/com/agent/orchestrator/model/Node.java` (whether NodeData has timeoutSeconds)

### Task 1.1: Verify timeoutSeconds field exists in Node model

**Objective:** Confirm the model field that stores per-node timeout already exists.

**Files:**
- READ: `backend/src/main/java/com/agent/orchestrator/model/Node.java` — search for `timeoutSeconds` or `timeout`

**Step 1: Search the model**

```bash
cd /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend
grep -n "timeout" src/main/java/com/agent/orchestrator/model/Node.java
```

If `timeoutSeconds` already exists in `NodeData`, proceed to Task 1.2.
If not, create it: add `private int timeoutSeconds = 60;` + getter/setter to `NodeData`.

**Step 2: Check config defaults**

Search for `getConfig().get("timeout")` usage patterns. The command node already reads `timeout` from config. Ensure consistency — the NodeData field should be the canonical source.

### Task 1.2: Add timeout execution wrapper to NodeRouter.executeNode

**Objective:** Wrap the inner retry-loop in a `CompletableFuture` with `get(timeoutSeconds, TimeUnit.SECONDS)`. When timeout fires, cancel the future and mark the node FAILED with a CLEAR error message.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/service/NodeRouter.java`

**Step 1: Add imports**

Add to the existing import block:
```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

**Step 2: Add helper method getTimeoutSeconds**

Add after `getAutoRetryCount()`:

```java
/**
 * Read timeoutSeconds from node config (default 60).
 */
int getTimeoutSeconds(Node node) {
    if (node.getData() == null) return 60;
    if (node.getData().getTimeoutSeconds() != null) {
        return Math.max(1, node.getData().getTimeoutSeconds());
    }
    // fallback to config map for backward compat
    if (node.getData().getConfig() != null) {
        Object val = node.getData().getConfig().get("timeoutSeconds");
        if (val instanceof Number) {
            return Math.max(1, ((Number) val).intValue());
        }
    }
    return 60;
}
```

**Step 3: Wrap the dispatch in a timeout**

Replace the retry-loop block (lines 118-216 in current NodeRouter.java) with a timeout wrapper:

```java
int timeoutSecs = getTimeoutSeconds(node);

try {
    // Wrap retry loop in CompletableFuture for timeout control
    String result = CompletableFuture.supplyAsync(() -> {
        try {
            // Existing retry loop goes here (lines 118-215 unchanged)
            for (int attempt = 1; attempt <= Math.max(1, autoRetry + 1); attempt++) {
                try {
                    // existing switch statement...
                    // (copy verbatim from current code)
                    break;
                } catch (Exception execEx) {
                    if (attempt <= autoRetry && isTransientError(execEx)) {
                        int waitMs = 5000 * attempt;
                        log.warn("Transient error on attempt {}/{} for node {}: {}. Retrying in {}ms",
                                attempt, autoRetry, node.getId(), execEx.getMessage(), waitMs);
                        if (webSocketHandler != null) {
                            webSocketHandler.sendLog(schemaId, "warning",
                                    "Retry " + attempt + "/" + autoRetry + " after: " + execEx.getMessage(), node.getId());
                        }
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    } else {
                        throw execEx;
                    }
                }
            }
            return result; // from the dispatch switch
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }, nodeExecutor.getExecutorService()).get(timeoutSecs, TimeUnit.SECONDS);
    
    // ... existing post-dispatch code (lines 218-256) ...
    
} catch (TimeoutException te) {
    log.error("Timeout ({}s) executing node {}: {}", timeoutSecs, node.getId(), node.getName());
    node.setStatus(Node.NodeStatus.FAILED);
    if (webSocketHandler != null) {
        webSocketHandler.sendError(schemaId, node.getId(),
                "Node execution timed out after " + timeoutSecs + "s");
        webSocketHandler.sendLog(schemaId, "error",
                "Timeout: " + node.getName() + " exceeded " + timeoutSecs + "s", node.getId());
    }
    // Persist timeout as failure to Neo4j
    if (nodeExecutionId != null) {
        try {
            executionRepository.updateNodeExecution(
                    nodeExecutionId, "failed", null, 0L, 0L, 0, "Timeout: " + timeoutSecs + "s");
        } catch (Exception ex) {
            log.debug("Failed to persist timeout error: {}", ex.getMessage());
        }
    }
} catch (Exception e) {
    // Existing outer catch (lines 258-274)
    ...
}
```

**Key detail:** `CompletableFuture.supplyAsync` needs an `ExecutorService`. NodeExecutor already manages a thread pool. Add a public accessor `getExecutorService()` to NodeExecutor if not already exposed.

**Step 4: Add getExecutorService() to NodeExecutor**

In `NodeExecutor.java`, add:
```java
public ExecutorService getExecutorService() {
    return executor;
}
```
(Verify the field name — it's likely `private final ExecutorService executor = ...` in NodeExecutor.)

### Task 1.3: Write tests

**Files:**
- Modify: `src/test/java/com/agent/orchestrator/service/NodeRouterTest.java`

**Test 1: Normal execution completes within timeout**
- Mock: `nodeExecutor.executeAgentNode()` returns instantly with a result
- Call: `nodeRouter.executeNode(agentNode, schemaId, cancelFlag, runMode, model)`
- Assert: node status is COMPLETED, result is stored in stateManager

**Test 2: Timeout fires on hanging node**
- Mock: `nodeExecutor.executeAgentNode()` hangs for 10+ seconds (use `Thread.sleep(10000)` or latch)
- Set: `node.data.timeoutSeconds = 1`
- Call: `nodeRouter.executeNode(agentNode, schemaId, cancelFlag, runMode, model)`
- Assert: node status is FAILED, `webSocketHandler.sendError()` was called with timeout message

**Test 3: Timeout respects cancelFlag before timeout fires**
- Mock: `nodeExecutor.executeAgentNode()` hangs
- Call: `nodeRouter.executeNode(...)` in one thread, set `cancelFlag=true` after 100ms
- Assert: node status is FAILED, execution completes before timeout

---

## FIX 2: Structured WebSocket error categories

**Objective:** Add machine-readable `errorCategory` (`TIMEOUT`, `LLM_ERROR`, `TOOL_ERROR`, `VALIDATION_ERROR`, `INTERNAL_ERROR`) to WebSocket log and error messages so the UI can categorize errors without string parsing.

**Severity:** High. Currently the UI must parse free-text error messages to categorize failures.

**Files:**
- Modify: `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java`
- Modify: `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` (sendError callers)
- Modify: `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java` (sendLog callers with errors)
- Modify: `backend/src/test/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandlerTest.java`

### Task 2.1: Add error categories to WebSocket handler

**Objective:** Create an ErrorCategory enum, add category field to `sendError` and `sendLog`, add a new `sendStructuredError` method.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java`

**Step 1: Create ErrorCategory enum**

At the top of ExecutionWebSocketHandler (or in its own file `ErrorCategory.java` in the same package):

```java
public enum ErrorCategory {
    NONE(""),
    TIMEOUT("timeout"),
    LLM_ERROR("llm_error"),
    TOOL_ERROR("tool_error"),
    VALIDATION_ERROR("validation_error"),
    INTERNAL_ERROR("internal_error"),
    AUTH_ERROR("auth_error"),
    PERMISSION_ERROR("permission_error");

    private final String code;

    ErrorCategory(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ErrorCategory fromException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("timed out")) return TIMEOUT;
        if (msg.contains("rate limit") || msg.contains("429") || msg.contains("502") || msg.contains("503")) return LLM_ERROR;
        if (msg.contains("permission") || msg.contains("denied") || msg.contains("blocked")) return PERMISSION_ERROR;
        if (msg.contains("validation") || msg.contains("invalid")) return VALIDATION_ERROR;
        return INTERNAL_ERROR;
    }

    public static ErrorCategory fromToolResult(boolean success, String errorMessage) {
        if (success) return NONE;
        if (errorMessage == null) return TOOL_ERROR;
        String msg = errorMessage.toLowerCase();
        if (msg.contains("permission") || msg.contains("blocked") || msg.contains("not in allowed")) return PERMISSION_ERROR;
        if (msg.contains("timeout")) return TIMEOUT;
        return TOOL_ERROR;
    }
}
```

**Step 2: Add overloaded sendError with category**

```java
public void sendError(String schemaId, String nodeId, String error) {
    sendError(schemaId, nodeId, error, ErrorCategory.INTERNAL_ERROR);
}

public void sendError(String schemaId, String nodeId, String error, ErrorCategory category) {
    Map<String, Object> msg = baseMsg("error", schemaId);
    msg.put("nodeId", nodeId);
    msg.put("error", error);
    msg.put("errorCategory", category.getCode());
    sendMessage(schemaId, toJson(msg));
    log.error("Ошибка [{}/{}] [{}]: {}", schemaId, nodeId, category.getCode(), error);
}
```

**Step 3: Add category parameter to sendLog**

```java
public void sendLog(String schemaId, String level, String message, String nodeId) {
    sendLog(schemaId, level, message, nodeId, ErrorCategory.NONE);
}

public void sendLog(String schemaId, String level, String message, String nodeId, ErrorCategory category) {
    Map<String, Object> msg = baseMsg("log", schemaId);
    msg.put("level", level);
    msg.put("message", message);
    msg.put("nodeId", nodeId != null ? nodeId : "");
    msg.put("timestamp", System.currentTimeMillis());
    msg.put("errorCategory", category.getCode());
    sendMessage(schemaId, toJson(msg));
    log.debug("Лог [{}][{}] [{}]: {}{}", schemaId, level, category.getCode(), message,
            nodeId != null ? " (узел: " + nodeId + ")" : "");
}
```

### Task 2.2: Wire categories into NodeRouter error paths

**Objective:** Every `sendError` and `sendLog("error", ...)` call in NodeRouter passes the correct ErrorCategory.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/service/NodeRouter.java`

**Changes:**

| Line/Area | Current | Replace with |
|-----------|---------|-------------|
| Line 262 (outer catch) | `sendError(schemaId, nodeId, e.getMessage())` | `sendError(schemaId, nodeId, e.getMessage(), ErrorCategory.fromException(e))` |
| Line 208 (retry log) | `sendLog(schemaId, "warning", ...)` | `sendLog(schemaId, "warning", ..., nodeId, ErrorCategory.LLM_ERROR)` |
| Line 108 (start log) | `sendLog(schemaId, "info", ...)` | No change (NONE) |
| Timeout catch (new in Fix 1) | `sendError(...)` + `sendLog("error", ...)` | `sendError(..., ErrorCategory.TIMEOUT)` + `sendLog(..., ErrorCategory.TIMEOUT)` |

### Task 2.3: Wire categories into ExecutionUtilityService error paths

**Objective:** Every `sendLog("error")` call in ExecutionUtilityService uses the correct category.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`

**Changes:**

| Method | Current | Replace with |
|--------|---------|-------------|
| Command sanitization fail (line ~1117) | `sendLog(schemaId, "error", ...)` | `sendLog(schemaId, "error", ..., nodeId, ErrorCategory.PERMISSION_ERROR)` |
| File write path denied (line ~1190) | `sendLog(schemaId, "error", ...)` | `sendLog(schemaId, "error", ..., nodeId, ErrorCategory.PERMISSION_ERROR)` |
| Summary report write fail (line ~1088) | `sendLog(schemaId, "error", ...)` | `sendLog(schemaId, "error", ..., nodeId, ErrorCategory.INTERNAL_ERROR)` |

### Task 2.4: Write tests

**Files:**
- Modify: `src/test/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandlerTest.java`

**Test 1: sendError with category includes category field**
- Call: `handler.sendError("s1", "n1", "error msg", ErrorCategory.TIMEOUT)`
- Assert: JSON message contains `"errorCategory": "timeout"`

**Test 2: sendError without category defaults to INTERNAL_ERROR**
- Call: `handler.sendError("s1", "n1", "error msg")`
- Assert: JSON message contains `"errorCategory": "internal_error"`

**Test 3: sendLog with category includes category field**
- Call: `handler.sendLog("s1", "error", "msg", "n1", ErrorCategory.LLM_ERROR)`
- Assert: JSON message contains `"errorCategory": "llm_error"`

**Test 4: Backward compatibility — null category is allowed**
- Call: `handler.sendLog("s1", "error", "msg", "n1", null)`
- Assert: JSON message contains `"errorCategory": ""` or doesn't throw NPE

---

## FIX 3: Execution State Reconciler

**Objective:** Add a reconciler component that, on startup and periodically, finds execution runs that are in "running" state in Neo4j but have no in-memory state (crash recovery), and marks them as `RECONCILED_FAILED` with appropriate error reports.

**Severity:** Medium-High. Without this, a server crash during execution permanently orphans in-progress runs in Neo4j ("running" status forever).

**Files:**
- Create: `backend/src/main/java/com/agent/orchestrator/service/ExecutionStateReconciler.java`
- Create: `backend/src/test/java/com/agent/orchestrator/service/ExecutionStateReconcilerTest.java`
- Read: `backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java`

### Task 3.1: Create ExecutionStateReconciler

**Objective:** Scan for orphaned executions and mark them as reconciled-failed.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/ExecutionStateReconciler.java`

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reconciler that detects orphaned execution runs — runs that are in "running"
 * status in Neo4j but whose in-memory state is absent (crash recovery scenario).
 * Marks them as RECONCILED_FAILED with a clear error message.
 */
@Component
public class ExecutionStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateReconciler.class);

    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final ExecutionWebSocketHandler webSocketHandler;

    /** Grace period: an execution must be "running" for at least this long before we reconcile it. */
    private static final long RECONCILE_GRACE_PERIOD_SECONDS = 300; // 5 minutes

    public ExecutionStateReconciler(ExecutionRepository executionRepository,
                                    ExecutionStateManager stateManager,
                                    ExecutionWebSocketHandler webSocketHandler) {
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.webSocketHandler = webSocketHandler;
    }

    @PostConstruct
    public void init() {
        log.info("ExecutionStateReconciler initialized");
        // Run once on startup after a short delay
        reconcileOrphanedRuns();
    }

    /**
     * Scheduled reconciliation — runs every 5 minutes.
     */
    @Scheduled(fixedRateString = "${axolotl.reconciler.interval:300000}")
    public void scheduledReconcile() {
        reconcileOrphanedRuns();
    }

    /**
     * Find all runs with status="running" older than RECONCILE_GRACE_PERIOD
     * that have no in-memory state. Mark them as RECONCILED_FAILED.
     */
    public void reconcileOrphanedRuns() {
        try {
            List<ExecutionRun> runningRuns = executionRepository.findByStatus("running");
            if (runningRuns == null || runningRuns.isEmpty()) {
                return;
            }

            Instant cutoff = Instant.now().minus(RECONCILE_GRACE_PERIOD_SECONDS, ChronoUnit.SECONDS);

            for (ExecutionRun run : runningRuns) {
                // Skip if the run still has active in-memory state
                String schemaId = run.getSchemaId();
                if (schemaId != null && stateManager.getCurrentRunId(schemaId) != null) {
                    continue;
                }

                // Parse startedAt — if null or younger than grace period, skip
                Instant startedAt;
                try {
                    startedAt = Instant.parse(run.getStartedAt());
                } catch (Exception e) {
                    startedAt = Instant.now(); // treat as current, will be caught next cycle
                }

                if (startedAt.isAfter(cutoff)) {
                    continue; // too recent, give it time
                }

                // This run is orphaned — mark it
                log.warn("Reconciling orphaned execution run: {} for schema {}, started at {}",
                        run.getId(), run.getSchemaId(), run.getStartedAt());

                executionRepository.updateRunStatus(run.getId(), "RECONCILED_FAILED");
                executionRepository.updateRunError(run.getId(),
                        "Execution was orphaned (server restart). Node results are unavailable.");

                // Notify any connected WebSocket for this schema
                if (schemaId != null && webSocketHandler != null) {
                    webSocketHandler.sendError(schemaId, "system",
                            "Execution reconciled: server restart detected. Previous run marked as failed.",
                            com.agent.orchestrator.websocket.ErrorCategory.INTERNAL_ERROR);

                    webSocketHandler.sendLog(schemaId, "error",
                            "Execution run " + run.getId() + " was orphaned by restart and has been reconciled as failed.",
                            null,
                            com.agent.orchestrator.websocket.ErrorCategory.INTERNAL_ERROR);

                    webSocketHandler.sendComplete(schemaId, 0, 0);
                }

                // Mark all node executions for this run as RECONCILED_FAILED
                try {
                    List<NodeExecution> nodes = executionRepository.getNodeExecutionsByRun(run.getId());
                    if (nodes != null) {
                        for (NodeExecution nodeExec : nodes) {
                            if ("running".equals(nodeExec.getStatus()) || "pending".equals(nodeExec.getStatus())) {
                                executionRepository.updateNodeExecution(
                                        nodeExec.getId(), "RECONCILED_FAILED", null,
                                        nodeExec.getTokensUsed(), 0L, 0,
                                        "Reconciled: server restart occurred during execution");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to reconcile node executions for run {}: {}", run.getId(), e.getMessage());
                }
            }

            if (!runningRuns.isEmpty()) {
                log.info("Reconciled {} orphaned execution run(s)", runningRuns.size());
            }

        } catch (Exception e) {
            log.error("Error during execution state reconciliation: {}", e.getMessage(), e);
        }
    }
}
```

### Task 3.2: Add findByStatus and updateRunError to ExecutionRepository

**Objective:** Ensure the repository has the methods the reconciler needs.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java`

**Step 1: Find the ExecutionRepository and add methods**

Search for existing query methods:
```bash
grep -n "findBy\|updateRun\|getNodeExecution" backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java
```

Add if missing:

```java
List<ExecutionRun> findByStatus(String status);

@Query("MATCH (r:ExecutionRun {id: $runId}) SET r.status = $status, r.error = $error, r.updatedAt = timestamp()")
void updateRunError(@Param("runId") String runId, @Param("error") String error);
```

Adjust the query annotations to match the existing repository pattern (Spring Data Neo4j vs raw driver).

### Task 3.3: Add axolotl.reconciler.interval config property

**Files:**
- Modify: `backend/src/main/resources/application.yml` (or application.properties)

Add:
```yaml
axolotl:
  reconciler:
    interval: 300000  # 5 minutes in ms
```

### Task 3.4: Write tests

**Files:**
- Create: `src/test/java/com/agent/orchestrator/service/ExecutionStateReconcilerTest.java`

**Test 1: No running runs — reconciler does nothing**
- Mock: `executionRepository.findByStatus("running")` returns empty list
- Call: `reconciler.reconcileOrphanedRuns()`
- Assert: no updates were made

**Test 2: Running run with active in-memory state — skip**
- Mock: `findByStatus` returns 1 run, `stateManager.getCurrentRunId` returns non-null
- Call: `reconciler.reconcileOrphanedRuns()`
- Assert: no updates were made (run is still active)

**Test 3: Running run older than grace period with no in-memory state — mark RECONCILED_FAILED**
- Mock: `findByStatus` returns 1 run with `startedAt = Instant.now().minus(10, MINUTES)`, `stateManager.getCurrentRunId` returns null
- Call: `reconciler.reconcileOrphanedRuns()`
- Assert: `updateRunStatus(id, "RECONCILED_FAILED")` was called

**Test 4: Running run younger than grace period — skip**
- Mock: `findByStatus` returns 1 run with `startedAt = Instant.now().minus(1, MINUTES)`, no in-memory state
- Call: `reconciler.reconcileOrphanedRuns()`
- Assert: no updates were made (within grace period)

---

## FIX 4: Split ExecutionUtilityService

**Objective:** Break the 1,537-line monolith into focused helper services by domain.

**Severity:** Medium. Reduces change blast radius and makes testing tractable.

**Files:**
- Create: `backend/src/main/java/com/agent/orchestrator/service/NodeCommandExecutor.java`
- Create: `backend/src/main/java/com/agent/orchestrator/service/NodeSourceHandler.java`
- Create: `backend/src/main/java/com/agent/orchestrator/service/NodeFileWriter.java`
- Create: `backend/src/main/java/com/agent/orchestrator/service/ToolCallParser.java`
- Modify: `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java` (thin delegating wrapper)
- Modify: `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` (inject new services)

### Task 4.1: Extract ToolCallParser

**Objective:** Move all tool-call parsing methods from ExecutionUtilityService to a dedicated `ToolCallParser` service.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/ToolCallParser.java`
- Modify: `src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`

**Step 1: Create ToolCallParser**

Move these methods from `ExecutionUtilityService`:
- `parseToolCalls(String response)` → `ToolCallParser.parse(String response)`
- `findMatchingBracket(String json, int startIdx)` → same (private)
- `extractTopLevelObjects(String jsonArray)` → same (private)
- `extractToolCallWithRegex(String text)` → same (private)
- `extractArgumentsWithRegex(String argsJson)` → same (private)

The new class is a plain `@Service` with no dependencies.

**Step 2: Replace in ExecutionUtilityService**

Keep original methods as thin delegates:
```java
public List<Map<String, Object>> parseToolCalls(String response) {
    return toolCallParser.parse(response);
}
```

### Task 4.2: Extract NodeCommandExecutor

**Objective:** Move command execution methods to `NodeCommandExecutor`.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/NodeCommandExecutor.java`
- Modify: `src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`

**Step 1: Create NodeCommandExecutor**

Move from `ExecutionUtilityService`:
- `executeCommandNode(Node node, String schemaId)` — with all its dependencies (webSocketHandler, BLOCKED_COMMAND_PATTERNS, sanitizeCommand)
- `sanitizeCommand(String command)` 
- `readBuildInstructions(Path dir, StringBuilder md)` — from PipelineService? No, this is in ExecutionUtilityService as part of buildReadmeDoc.

Wait, actually `executeCommandNode` is at line ~1097 in ExecutionUtilityService and so are `sanitizeCommand`, `BLOCKED_COMMAND_PATTERNS`. Move all of them.

**Step 2: Replace in ExecutionUtilityService**

```java
public String executeCommandNode(Node node, String schemaId) {
    return nodeCommandExecutor.execute(node, schemaId);
}
```

### Task 4.3: Extract NodeSourceHandler

**Objective:** Move source node handling to `NodeSourceHandler`.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/NodeSourceHandler.java`
- Modify: `src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`

**Step 1: Create NodeSourceHandler**

Move from `ExecutionUtilityService`:
- `handleSourceNode(Node node, String schemaId)`
- `fetchUrlContent(String url)`
- `validateUrl(String url)`
- `readProjectContext(String projectPath, Map<String, Object> config)`
- `resolveSourceData(WorkflowSchema schema)`

**Step 2: Replace in ExecutionUtilityService**

Thin delegate.

### Task 4.4: Extract NodeFileWriter

**Objective:** Move file write methods to `NodeFileWriter`.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/NodeFileWriter.java`
- Modify: `src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java`

**Step 1: Create NodeFileWriter**

Move from `ExecutionUtilityService`:
- `executeFileWriteNode(Node node, String schemaId)`
- `writeOutput(String outputType, String filePath, String fileFormat, String content)`
- `isPathAllowed(String filePath)`
- `extractGeneratedFiles(String response)` (actually this is tool-helper related, keep in utility or move here)

**Step 2: Replace in ExecutionUtilityService**

Thin delegate.

### Task 4.5: Update NodeRouter to inject new services

**Objective:** NodeRouter currently depends on `ExecutionUtilityService`. After the split, inject the new focused services where needed.

**Files:**
- Modify: `src/main/java/com/agent/orchestrator/service/NodeRouter.java`

Inject `NodeCommandExecutor` and `NodeSourceHandler` directly into NodeRouter and call them directly instead of going through `ExecutionUtilityService`:

```java
private final NodeCommandExecutor commandExecutor;
private final NodeSourceHandler sourceHandler;
private final NodeFileWriter fileWriter;
```

Update switch cases:
- `case "command": result = commandExecutor.executeCommandNode(node, schemaId);`
- `case "source": result = sourceHandler.handleSourceNode(node, schemaId);`
- `case "filewrite": result = fileWriter.executeFileWriteNode(node, schemaId);`

### Task 4.6: Write tests for extracted services

Each extracted service should have a corresponding test file:
- `src/test/java/com/agent/orchestrator/service/ToolCallParserTest.java`
- `src/test/java/com/agent/orchestrator/service/NodeCommandExecutorTest.java`
- `src/test/java/com/agent/orchestrator/service/NodeSourceHandlerTest.java`
- `src/test/java/com/agent/orchestrator/service/NodeFileWriterTest.java`

Each test should:
1. Test 3+ representative paths (happy, error, edge)
2. Use the existing test patterns (Mockito, same annotations)
3. Verify the delegation still works through ExecutionUtilityService

---

## FIX 5: PipelineService split

**Objective:** Break the 1,427-line PipelineService into focused services by responsibility: pipeline building, pipeline execution, pipeline status, and diff/approval.

**Severity:** Low-Medium. PipelineService has lower coupling than ExecutionUtilityService (2-3 importers) so the risk is mostly internal complexity.

**Files:**
- Create: `backend/src/main/java/com/agent/orchestrator/service/PipelineBuilder.java`
- Create: `backend/src/main/java/com/agent/orchestrator/service/PipelineStatusManager.java`
- Create: `backend/src/main/java/com/agent/orchestrator/service/DiffService.java`
- Modify: `backend/src/main/java/com/agent/orchestrator/service/PipelineService.java` (thin orchestrator)

### Task 5.1: Extract PipelineBuilder

**Objective:** Move `buildPipelineNodes()` to `PipelineBuilder`.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/PipelineBuilder.java`
- Modify: `src/main/java/com/agent/orchestrator/service/PipelineService.java`

**Step 1: Identify methods to extract**
- `buildPipelineNodes(String schemaId)` — line ~67
- `initializeRunStageStatus(ExecutionRun run, List<Stage> stages)` — wherever it is

### Task 5.2: Extract PipelineStatusManager

**Objective:** Move status tracking and stage results to `PipelineStatusManager`.

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/PipelineStatusManager.java`
- Modify: `src/main/java/com/agent/orchestrator/service/PipelineService.java`

**Step 1: Identify methods to extract**
- Status-related maps (`runningPipelines`, `cancelFlags`, `stageResults`, `pipelineResumeState`)
- `getPipelineStatus(String schemaId)`
- `cancelPipeline(String schemaId)`
- `clearStaleApprovals(String schemaId)`

### Task 5.3: Extract DiffService

**Files:**
- Create: `src/main/java/com/agent/orchestrator/service/DiffService.java`
- Modify: `src/main/java/com/agent/orchestrator/service/PipelineService.java`

**Step 1: Identify methods to extract**
- `approveDiff(...)` or any diff-related methods
- Any approval workflow methods

### Task 5.4: Write tests

Each extracted service gets a test file:
- `src/test/java/com/agent/orchestrator/service/PipelineBuilderTest.java`
- `src/test/java/com/agent/orchestrator/service/PipelineStatusManagerTest.java`
- `src/test/java/com/agent/orchestrator/service/DiffServiceTest.java`

---

## FIX 6: Remove dead code

**Objective:** Clean up dead/unused code found during the premortem.

**Severity:** Trivial.

**Files:**
- Modify: `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java`

### Task 6.1: Remove unused `transientOnly` variable

**Step 1: Delete line 116**

```java
// Delete: boolean transientOnly = true;
```

### Task 6.2: Verify `isTransientError` is called correctly

The check at line 203 already calls `isTransientError` before retrying. Confirm no regression.

---

## EXECUTION ORDER

| Order | Fix | Effort | Risk | Impact |
|-------|-----|--------|------|--------|
| 1 | FIX 6: Remove dead code | 5 min | None | Cleanliness |
| 2 | FIX 1: Per-node timeout | 2-3 hours | Low (isolated to NodeRouter) | High |
| 3 | FIX 2: WebSocket categories | 1-2 hours | Low (backward-compatible overloads) | Medium |
| 4 | FIX 3: State reconciler | 2-3 hours | Low (reads only, writes only orphaned runs) | Medium-High |
| 5 | FIX 4: Split ExecutionUtilityService | 4-6 hours | Medium (signature changes, NodeRouter wiring) | Medium |
| 6 | FIX 5: Split PipelineService | 3-4 hours | Medium (same) | Low-Medium |

**Total:** ~13-18 hours of implementation + 4-6 hours of testing.

---

## VERIFICATION

After all fixes:

```bash
cd /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend

# Run all tests
mvn test 2>&1 | tail -30

# Run specific affected tests
mvn test -Dtest="NodeRouterTest,ExecutionUtilityServiceTest,PipelineServiceTest,ExecutionWebSocketHandlerTest,ExecutionStateReconcilerTest" 2>&1 | tail -20

# Build
mvn compile -q 2>&1
```

Expected: All tests pass, build succeeds with no warnings.
