# Plan 56: Multi-Session EIOS Development Fixes

> Goal: Enable iterative multi-session Flutter code generation for EIOS
> Current state: EIOS schema exists but generatedFiles registry is always empty,
> workspaceId is null, and the schema uses doc-agent (not code generation).
>
> After extensive codebase investigation, the critical finding is that the
> generatedFiles registry is **always empty for all schemas** — the entire
> multi-session persistence layer (updateRunGeneratedFiles → Neo4j →
> ProjectContextBuilder) is dead code. This plan fixes it at the root.

---

## Batch 1: Fix generatedFiles from tool call results (CRITICAL)

**Problem**: `ToolExecutionService.extractGeneratedFiles()` parses the LLM's text
response looking for `"generatedFiles"` JSON in the last 500 characters. No system
prompt tells the LLM to output this JSON. Result: the generatedFilesRegistry is
`{}` for every single run across all schemas. All 19 EIOS runs have `genFiles=[]`
in Neo4j.

**Root cause**: Wrong data source. The LLM text response is not a reliable source
for generated file metadata. The actual tool execution results — which DO track
every file_write — are the correct source.

**Fix**: Replace `ToolExecutionService.extractGeneratedFiles()` with a new method
that collects generated files from `ExecutionStateManager.getFileChanges()`. This
registry is populated by `ToolHandlerService.handleFileWrite()` → `recordFileChange()`
for every file_write operation, regardless of model behavior.

### Changes

**a) `AgentNodeStrategy.java`** (lines 193-198 and 479-484)
Replace `extractGeneratedFiles(result)` calls with a new method that reads from
the file changes registry:

```java
// Current (dead code):
Map<String, Object> extracted = toolExecutionService.extractGeneratedFiles(result);

// New:
List<Map<String, String>> files = collectWrittenFiles(schemaId, node.getId());
Map<String, Object> extracted = files.isEmpty() ? null : Map.of("generatedFiles", files);
```

**b) New method in `AgentNodeStrategy.java`**
```java
private List<Map<String, String>> collectWrittenFiles(String schemaId, String nodeId) {
    Map<String, String> changes = stateManager.getFileChanges(schemaId, nodeId);
    List<Map<String, String>> files = new ArrayList<>();
    for (Map.Entry<String, String> entry : changes.entrySet()) {
        files.add(Map.of("path", entry.getKey(), "action", entry.getValue()));
    }
    return files;
}
```

**c) Optional: Remove dead `extractGeneratedFiles()` from `ToolExecutionService.java`**
Delete lines 146-175 (the extractGeneratedFiles method) since it's never
successful in practice.

### Effect
- All runs will have `generatedFiles` populated in Neo4j
- `ProjectContextBuilder` will show real generated files across sessions
- Pipeline plan tasks will have actual file lists
- Multi-session "You are continuing development" prompts will work

### Effort: ~30 min | Risk: Low

---

## Batch 2: Fix resolvePath() workspaceId (MEDIUM)

**Problem**: `AppController.resolvePath()` (used by Dashboard conflict resolution)
doesn't auto-generate workspaceId. `createApp()` handles this correctly (line 57-60)
but `resolvePath()` passes null directly:

```java
// Line 201: passes null as-is
schema.setWorkspaceId(workspaceId);
```

**Fix**: Add the same UUID auto-generation as in `createApp()`:

```java
if (workspaceId == null || workspaceId.isBlank()) {
    workspaceId = UUID.randomUUID().toString();
    log.info("Auto-generated workspaceId={} for schema '{}'", workspaceId, name);
}
schema.setWorkspaceId(workspaceId);
```

**Note**: QuickStart already uses `createApp()` which auto-generates workspaceId.
This fix only matters for the Dashboard → "Continue / Overwrite" flow. Still worth
fixing to avoid trapping new users.

### Effort: 1 line insertion | Risk: None

---

## Batch 3: Create fresh EIOS schema via QuickStart (OPERATIONAL)

After Batch 1-2, create a new Flutter code-gen schema:

1. Open QuickStart dialog in Studio
2. Schema name: `eios-app` (not `eios` — target dir `eios/` already has docs)
3. Project Type: FLUTTER
4. Preset: App Creation
5. Pipeline template: App Creation (9 stages: receive → review → agent → verify → output)
6. Create → schema gets auto-generated workspaceId
7. Execute pipeline → generatedFiles persisted to Neo4j ✓
8. New Session → dialog shows previous sessions' file list ✓

### If user wants to reuse the `eios/` target directory:
- After creating the schema, manually edit `targetPath` to `eios` via API
- Or: clean `eios/` docs (backup if needed) and create schema with name `eios`
  - QuickStart blocks this with "Directory already exists" error
  - Workaround: create with name `eios-app`, then API-update targetPath to `eios`
- Either way, Batch 1 fix ensures generated files are tracked regardless

### Effort: ~5 min | Risk: None

---

## Batch 4: FLUTTER agent prompt — add Report step (LOW, optional)

**Problem**: The FLUTTER workflow prompt (lines 261-268) doesn't include a
"Report generated files" step. With Batch 1 fix this becomes cosmetic, but it's
still good practice for the LLM to summarize its output.

**Fix**: Add step 6 to the FLUTTER workflow:

```text
5. Verify with build_app
6. Report: output JSON with generated files list:
   {"generatedFiles": [{"path": "lib/main.dart", "description": "..."}, ...]}
```

### Effort: ~5 min | Risk: None

---

## Summary

| Batch | Fix | Effort | Priority | Effect |
|-------|-----|--------|----------|--------|
| 1 | generatedFiles from tool calls (not LLM text) | 30 min | CRITICAL | Fixes all multi-session persistence |
| 2 | resolvePath() workspaceId auto-gen | 1 line | MEDIUM | Dashboard schemas get workspaceId |
| 3 | Create fresh EIOS schema | 5 min | OPERATIONAL | Start code generation |
| 4 | FLUTTER prompt Report step | 5 min | LOW | Belt-and-suspenders |

After all 4 batches: iterative multi-session Flutter development works end-to-end:
1. Agent writes files → `recordFileChange()` tracks them
2. AgentNodeStrategy collects files from registry
3. PipelineStageExecutionService persists to Neo4j
4. Next session's agent sees file list via ProjectContextBuilder
5. Plan tasks show session progress with file lists

---

## Appendix: Why the current code doesn't work

The `extractGeneratedFiles()` method in `ToolExecutionService.java` looks for
`"generatedFiles"` in the LLM's final text response:

```java
String tail = response.substring(Math.max(0, response.length() - 500));
int idx = tail.lastIndexOf("\"generatedFiles\"");
```

No system prompt in the codebase instructs models to output this JSON key.
The doc-agent prompt uses different keys (`updatedDocs`, `createdDocs`). The
code-agent prompt has no JSON output instruction at all. So `extractGeneratedFiles()`
returns null for every execution — a silent failure.

Meanwhile, `ToolHandlerService.handleFileWrite()` correctly calls
`stateManager.recordFileChange(schemaId, nodeId, path, action)` for every
file_write — this data exists reliably but is never read by the generatedFiles
extraction path.

Verified: all 19 EIOS runs have `genFiles=[]` in Neo4j. The registry is `{}`
for all schemas across the entire codebase.
