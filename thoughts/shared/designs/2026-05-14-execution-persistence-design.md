date: 2026-05-14
topic: "Execution Persistence + Path Editing"
status: draft

## Problem Statement

Three gaps prevent apps from working reliably:

1. **Target directory never created.** When a new app is created (no conflict), `AppController.createApp()` never calls `Files.createDirectories()`. The first `file_write` tool call fails because the directory doesn't exist, forcing the LLM to fall back to `/Users/evgenijtihomirov/`.

2. **Execution results are in-memory only.** `NodeExecutor.executeNode()` mutates node statuses and results on the in-memory `Node` object, but `executeWorkflow()` never calls `schemaRepository.save(schema)`. After execution, `GET /api/schemas/{id}` returns a fresh read from Neo4j with no results and `status: None`.

3. **Execution history lost on restart.** `ExecutionRecord` objects are stored in `SchemaService.executionHistory` — a `Collections.synchronizedList()` with max 100 entries. All history is lost on application restart, and there's no time-based cleanup.

## Constraints

- Schema data is stored as a JSON blob in Neo4j (`s.data`). We keep single-source-of-truth pattern.
- Execution records should be queryable by schemaId and ordered by recency.
- Old records (>14 days) must be automatically cleaned up.
- Path editing must work on existing schemas, not just at creation time.
- The `file_write` tool's security sandbox (`ToolExecutor.validateSandboxPath`) confines writes to `targetPath` — this must not break.

## Approach (chosen)

Three independent changes, same files, no new databases:

**Schema persistence:** After `executeWorkflow()` completes, call `schemaRepository.save(schema)`. The schema object already has mutated node statuses (`COMPLETED`/`FAILED`) and results (`node.getData().result`). This is a one-line change with immediate impact.

**Execution history:** Move from in-memory `synchronizedList` to Neo4j nodes. Create `(:ExecutionRecord {schemaId, timestamp, data})` nodes via existing `Neo4jSchemaRepository`. Add `@Scheduled` daily cleanup for records >14 days.

**Path creation:** Add `Files.createDirectories()` in `createApp()` for fresh apps. New `PUT /api/app/{id}/target-path` endpoint for post-creation path editing. Frontend gets an editable path field.

**Rejected options:**
- Inline history on WorkflowSchema blob → unbounded growth, poor load perf
- SQLite for exec records → already Neo4j-first, avoid second engine

## Architecture

### Change 1: Directory creation on app creation + editable path

**AppController.java:**
- Add `Files.createDirectories(path)` when `targetPath` is set and directory doesn't exist (both fresh and CONTINUE cases)
- New `PUT /api/app/{id}/target-path`:
  - Request body: `{ targetPath: string }`
  - Creates the directory: `Files.createDirectories(Path.of(newTargetPath))`
  - Updates `schema.targetPath` and saves to Neo4j
  - Returns updated AppModel

**Frontend (AppDashboardView.vue):**
- Add editable path field next to the existing compact path display
- "Edit" button → inline input → "Save" calls `PUT /api/app/{id}/target-path`
- Shows success toast, updates display

**api.ts:**
- Add `updateTargetPath(appId: string, targetPath: string): Promise<AppInfo>`

### Change 2: Persist execution results to Neo4j

**SchemaService.executeWorkflow():**
- After wave execution loop, before `recordExecution()`:
  ```java
  schemaRepository.save(schema);
  ```
- This saves the schema with current node statuses and `node.getData().result` values
- The `NodeExecutor.executeNode()` already calls `node.setStatus(COMPLETED)` and `node.getData().setResult(result)` — no additional changes needed in NodeExecutor

**Impact:**
- `GET /api/schemas/{id}` now returns the schema with populated node results and statuses from the last execution
- The existing `GET /api/schemas/{id}/history` endpoint continues working as before
- On the frontend, the LiveView and TimelineView will show data even after page reload (since the schema is persisted)

### Change 3: ExecutionRecords in Neo4j with 2-week TTL

**Neo4jSchemaRepository:**
- Add three methods:
  - `saveExecutionRecord(ExecutionRecord)` — creates `(:ExecutionRecord {id, schemaId, data, timestamp})` via Cypher
  - `getExecutionRecords(schemaId, limit)` — `MATCH (r:ExecutionRecord {schemaId}) RETURN r.data ORDER BY r.timestamp DESC LIMIT limit`
  - `deleteExecutionRecordsOlderThan(cutoffTimestamp)` — `MATCH (r:ExecutionRecord) WHERE r.timestamp < cutoff DETACH DELETE r`

**ExecutionRecord.java:**
- Add fields: `schemaId` (String), `timestamp` (long, epoch millis), `data` (String, JSON summary)
- Keep existing fields: `status`, `totalTimeMs`, `totalNodes`, `completedNodes`, `totalTokens`, `estimatedCost`, `nodeResults`
- The `data` field is a JSON-serialized version of all execution metadata for quick retrieval

**SchemaService.recordExecution():**
- Change from `executionHistory.add(record)` to `neo4jSchemaRepository.saveExecutionRecord(record)`
- Keep in-memory history as fallback/cache (optional, for WebSocket real-time readers)
- `getExecutionHistory()`: change to query Neo4j, fall back to in-memory list

**New: ExecutionLogCleanupService.java:**
- `@Service` with `@Scheduled(cron = "0 0 3 * * *")` (daily 3AM)
- Computes `cutoff = System.currentTimeMillis() - 14 * 24 * 60 * 60 * 1000L`
- Calls `neo4jSchemaRepository.deleteExecutionRecordsOlderThan(cutoff)`
- Logs count of deleted records

## Data Flow (After Changes)

```
POST /api/app (fresh creation)
  → path = targetPathFor(name)
  → Files.createDirectories(path)                    ← NEW
  → schemaRepository.save(schema)

POST /api/app (OVERWRITE)
  → delete directory
  → Files.createDirectories(path)                    ← already exists

POST /api/app (CONTINUE)
  → Files.createDirectories(path)                    ← NEW (was no-op)

PUT /api/app/{id}/target-path
  → Files.createDirectories(newPath)                 ← NEW
  → schema.setTargetPath(newPath)
  → schemaRepository.save(schema)

POST /api/schemas/{id}/execute
  → executeWorkflow()
    → wave execution (node results written to Node objects)  ← already works
    → schemaRepository.save(schema)                          ← NEW
    → neo4jRepository.saveExecutionRecord(record)            ← NEW

GET /api/schemas/{id}
  → returns schema WITH node results and statuses            ← NEW (was empty)

GET /api/schemas/{id}/history
  → reads from Neo4j ExecutionRecord nodes                   ← CHANGED

@Scheduled daily 3AM
  → DETACH DELETE ExecutionRecord WHERE timestamp < cutoff  ← NEW
```

## ExecutionRecord Neo4j Node Schema

```
(:ExecutionRecord {
  id: "uuid",
  schemaId: "327bc886-...",
  timestamp: 1744567890123,        // epoch millis
  data: "{                          // JSON string
    \"status\": \"completed\",
    \"totalTimeMs\": 136849,
    \"totalNodes\": 3,
    \"completedNodes\": 3,
    \"totalTokens\": 11554,
    \"estimatedCost\": 0.023,
    \"nodeResults\": {
      \"receive-1\": {\"status\":\"completed\",\"durationMs\":17,\"resultLen\":42},
      \"think-1\": {\"status\":\"completed\",\"durationMs\":135000,\"resultLen\":11554},
      \"act-1\": {\"status\":\"completed\",\"durationMs\":5,\"resultLen\":1500}
    }
  }"
})
```

The `data` field stores a compact JSON summary (not full result text, which lives on the schema nodes). Node results on the schema are the complete text; execution records store only metadata + result lengths.

## Error Handling

- **Directory creation fails**: `createApp()` already catches exceptions and returns 500. `PUT target-path` returns 400 if path is invalid, 500 if mkdir fails.
- **Neo4j save fails after execution**: The execution itself succeeded (files written, LLM called). Failure to save metadata is logged but not fatal. The result is degradated — data is lost but the app output files exist.
- **Cleanup fails silently**: Scheduled task logs errors but doesn't retry. Next day's run will catch remaining records.
- **GET /history fails**: Fall back to in-memory list if it has entries, otherwise return empty. Log the error.

## Testing Strategy

- **AppControllerTest**: Test createApp creates directory for fresh app; test PUT target-path updates path and creates directory
- **SchemaServiceTest**: Test that executeWorkflow saves schema to repository; test that execution records are saved to Neo4j
- **Neo4jSchemaRepositoryTest**: Test save/get/delete of ExecutionRecord nodes; test TTL deletion
- **ExecutionLogCleanupServiceTest**: Test that records older than 14 days are deleted, records newer than 14 days are kept
- **Integration**: Create app → execute → GET schema → verify node results are populated → wait for TTL (or mock clock) → verify cleanup

## Open Questions

- Should the frontend path editor be in AppDashboardView's info panel or in a modal? I'm leaning toward inline editing in the info panel (edit icon → input field → save button) to keep it simple.
- Should `PUT /api/app/{id}/target-path` also move existing generated files to the new path? No — YAGNI for now. The new path starts fresh. User can manually copy files.
- Do we need to validate the target path (no symlinks, no escaping outside basePath)? Already handled by `ToolExecutor.validateSandboxPath()` — but for the PUT endpoint, we should validate the path is under the allowed base.
