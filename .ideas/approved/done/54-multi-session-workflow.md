# Plan 54: Multi-Session Workflow

**Goal**: Running the same schema multiple times = iterative development.
Each run is a "session" that builds on previous sessions.
Agent sees existing files + session history and continues rather than restarting.

## Architecture

```
Schema (targetPath: /project/eios24/)
  ├── Session 1 → writes lib/main.dart, ...
  ├── Session 2 → reads existing, adds screens/
  └── Session 3 → reads existing, adds services/
```

Each session = one `POST /execute` call. State flows through three layers:
1. **Disk** — files from previous sessions exist in targetPath
2. **Neo4j** — ExecutionRun records with generatedFiles + Plan tasks with session numbering
3. **Agent prompt** — injected "Previous sessions" context telling the model what was built before

## Batches

### Batch 1 — Persist generatedFiles to ExecutionRun (HIGH)

Problem: `ExecutionRun.generatedFiles` always null. Files are tracked in-memory only.

**Changes:**
1. `SchemaExecutionService.java` — after pipeline completes, collect generated files from `getGeneratedFiles(schemaId)`, flatten to `List<String>`, save to `ExecutionRun.setGeneratedFiles()` via `executionRepository.updateGeneratedFiles()`
2. `ExecutionRepository.java` — add `updateGeneratedFiles(runId, files)` Cypher: `MATCH (r:ExecutionRun {id: $id}) SET r.generatedFiles = $files`
3. `ProjectContextBuilder.java` — already reads `run.getGeneratedFiles()` → will now show real data

### Batch 2 — Auto-create workspace per schema (MEDIUM)

Problem: QuickStart schemas have no workspaceId. PlanService needs workspace.

**Changes:**
1. `AppController.java` — after `POST /api/app` creates schema, if `workspaceId` is null, generate UUID, set it via `schemaService.updateSchema()`
2. Propagate workspaceId to `WorkflowSchema.workspaceId` field

### Batch 3 — Auto-create plan tasks on execution (MEDIUM)

Problem: No plan tasks → ProjectContextBuilder shows empty session history.

**Changes:**
1. `SchemaExecutionService.java` — on execute, find or create Plan for workspace, add task "Session {N}: {schemaDescription}" in PLANNING status
2. When execution completes → mark task DONE with generated files list
3. Increment session counter per schema

### Batch 4 — Session-aware agent prompt (MEDIUM)

Problem: Agent doesn't know which session it's in or what was built before.

**Changes:**
1. `ProjectContextBuilder.java` — add session number to output ("Session 2 of 3")
2. `AgentNodeStrategy.java` — when previous run data exists, inject "PREVIOUS SESSIONS" block in system prompt with file list + session output summary
3. Include instruction: "Read existing files, do NOT recreate them. Build on what exists."

### Batch 5 — UI "New Session" button (LOW)

Problem: No way to trigger next session from UI.

**Changes:**
1. `TimelineView.vue` — after completed run, show "New Session" button
2. Calls `POST /api/schemas/{id}/execute` same as initial run
3. `DashboardView.vue` — show session count per app

## Test plan

- `ProjectContextBuilderTest` — update to verify generatedFiles appear in context
- `SchemaExecutionServiceTest` — verify generatedFiles persisted on completion
- `AppControllerTest` — verify workspace auto-creation
- `PlanServiceTest` — verify session tasks auto-created
- Manual: run eios schema twice, verify second run sees first run's files

## Success criteria

1. Run schema → files generated → ExecutionRun.generatedFiles populated → visible via API
2. Run same schema again → ProjectContextBuilder shows "Previous sessions" with file list
3. Agent prompt says "Session 2: continue from previous output"
4. UI shows session count + "New Session" button
