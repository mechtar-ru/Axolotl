# Multi-Session App Development — Implementation Plan

Based on: `thoughts/shared/designs/2026-05-13-multi-session-app-dev-design.md`

## Dependencies

```
Step 1 (Model: WorkflowSchema.targetPath)      ← no deps
Step 2 (Model: Task.generatedFiles)             ← no deps
Step 3 (AppController: conflict detection)      ← Step 1
Step 4 (PlanService: create/complete Task)      ← Step 2
Step 5 (SchemaService: Plan hooks)              ← Step 1, Step 4
Step 6 (ProjectContextBuilder: new class)        ← no deps
Step 7 (NodeExecutor: context injection)         ← Step 6
Step 8 (ToolExecutor: sandbox)                  ← Step 1
Step 9 (Frontend: Generated Apps section)        ← Step 1
Step 10 (Frontend: Conflict dialog)              ← Step 3
Step 11 (Frontend: File results display)         ← Step 2
```

---

## Step 1: Add `targetPath` to WorkflowSchema

**File:** `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java`

**What to add:**
- New field `private String targetPath;` (nullable, null means no app mode)
- Getter `getTargetPath()` / setter `setTargetPath(String)`
- Also add a new field `private String targetPathConflictAction;` (nullable, "CONTINUE" | "OVERWRITE" | "CHANGE_PATH")

**Key logic:** Simple POJO change. targetPath is set during app creation when `appType != CUSTOM`.

**Testing:** Unit test — create WorkflowSchema, set/get targetPath, verify serialization.

---

## Step 2: Add `generatedFiles` to Task

**File:** `backend/src/main/java/com/agent/orchestrator/model/Task.java`

**What to add:**
- New inner class `GeneratedFile`:
  ```java
  public static class GeneratedFile {
      private String path;
      private String description;
      // constructors, getters, setters
  }
  ```
- New field `private List<GeneratedFile> generatedFiles = new ArrayList<>();`
- Getter/setter

**Key logic:** Simple POJO change. No special behavior.

**Testing:** Unit test — add GeneratedFile to Task, verify JSON serialization.

---

## Step 3: Conflict detection in AppController

**File:** `backend/src/main/java/com/agent/orchestrator/controller/AppController.java`

**What to modify:**
- `createApp()` method: after getting schema name, compute `targetPath = /Users/evgenijtihomirov/git/Axolotl/{name}/`
- Check if directory exists → if yes AND appType != CUSTOM, set `targetPathConflictAction` on schema but allow creation (client chooses in next call)
- New endpoint `POST /api/app/{id}/resolve-path-conflict`:
  ```json
  { "action": "CONTINUE" | "OVERWRITE" | "CHANGE_PATH", "newPath": "..." }
  ```
  - CONTINUE: leave as-is
  - OVERWRITE: delete directory + recreate
  - CHANGE_PATH: update schema.targetPath

- Store `targetPath` on the created schema

**Key logic:**
```java
String targetPath = "/Users/evgenijtihomirov/git/Axolotl/" + schema.getName() + "/";
schema.setTargetPath(targetPath);
boolean dirExists = Files.exists(Path.of(targetPath));
// Return dirExists in response so client shows conflict dialog
```

**Testing:** Unit test — create app with existing dir, verify conflict detected. Test resolve endpoint.

---

## Step 4: PlanService — create/complete Task for execution

**File:** `backend/src/main/java/com/agent/orchestrator/service/PlanService.java`

**What to add:**

**`createTaskForExecution(String workspaceId, String schemaId, String schemaName)`**:
- Calls `getPlan(workspaceId)` to get or create the Plan
- Creates a new Task:
  ```java
  Task task = new Task(schemaName);
  task.setStatus(TaskStatus.IN_PROGRESS);
  task.setSchemaId(schemaId);
  ```
- Adds to plan, saves, returns Task

**`completeTaskForExecution(String schemaId, String targetPath, List<Task.GeneratedFile> generatedFiles)`**:
- Finds the Plan containing the active IN_PROGRESS Task for this schemaId
- Sets `task.setStatus(TaskStatus.DONE)`
- Sets `task.setGeneratedFiles(generatedFiles)`
- Saves plan

**`scanGeneratedFiles(String targetPath)`** — helper to scan a directory recursively:
```java
List<Task.GeneratedFile> scanGeneratedFiles(String targetPath) {
    try (Stream<Path> paths = Files.walk(Path.of(targetPath))) {
        return paths
            .filter(Files::isRegularFile)
            .map(p -> new Task.GeneratedFile(
                targetPath.relativize(p).toString(),
                "" // description filled by agent output
            ))
            .toList();
    }
}
```

**Testing:** Unit test — create task via createTaskForExecution, verify task in plan. Test scanGeneratedFiles.

---

## Step 5: SchemaService — hook into execute/complete flow

**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`

**What to modify:**

**Inject PlanService** — add as constructor parameter (already in NodeExecutor but SchemaService doesn't have it).

**In `executeWorkflow()` — before execution loop, add:**
```java
if (schema.getTargetPath() != null) {
    planService.createTaskForExecution(schema.getWorkspaceId(), schema.getId(), schema.getName());
}
```

**In `executeWorkflow()` — after successful completion (in the `if (!cancelFlag.get())` block, around line 393-408), add:**
```java
if (schema.getTargetPath() != null) {
    // Collect descriptions from agent output (node results)
    List<Task.GeneratedFile> files = planService.scanGeneratedFiles(schema.getTargetPath());
    planService.completeTaskForExecution(schema.getId(), schema.getTargetPath(), files);
}
```

**Import:** Add `import com.agent.orchestrator.model.Task;`

**Testing:** Integration test — create schema with targetPath, execute full cycle, verify Plan task was created and completed.

---

## Step 6: Create ProjectContextBuilder

**New file:** `backend/src/main/java/com/agent/orchestrator/service/ProjectContextBuilder.java`

**Responsibilities:**
- Scan a target directory and produce a formatted tree view
- Read Plan history for completed tasks
- Format into a context string for systemPrompt injection

```java
@Service
public class ProjectContextBuilder {
    
    private final PlanService planService;
    
    public String buildContext(String targetPath, String workspaceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current project state (target: ").append(targetPath).append("):\n\n");
        // File tree
        appendFileTree(sb, Path.of(targetPath), "");
        // Session history
        Plan plan = planService.getPlan(workspaceId);
        List<Task> completedTasks = plan.getTasks().stream()
            .filter(t -> t.getStatus() == TaskStatus.DONE)
            .toList();
        if (!completedTasks.isEmpty()) {
            sb.append("\nPrevious sessions completed:\n");
            for (int i = 0; i < completedTasks.size(); i++) {
                Task t = completedTasks.get(i);
                sb.append("  [").append(i+1).append("] \"").append(t.getTitle()).append("\"");
                if (t.getGeneratedFiles() != null && !t.getGeneratedFiles().isEmpty()) {
                    sb.append(" → ");
                    sb.append(t.getGeneratedFiles().stream()
                        .map(Task.GeneratedFile::getPath)
                        .collect(java.util.stream.Collectors.joining(", ")));
                }
                sb.append("\n");
            }
        }
        // Truncate if too long (>1000 tokens ≈ 4000 chars)
        if (sb.length() > 4000) {
            sb.setLength(4000);
            sb.append("\n[... truncated]");
        }
        return sb.toString();
    }
    
    private void appendFileTree(StringBuilder sb, Path dir, String prefix) { ... }
}
```

**Key logic:** Recursive file tree formatting. Handle truncation.

**Testing:** Unit test — create temp directory with files, verify tree output. Test with empty dir, deep nesting.

---

## Step 7: Project context injection in NodeExecutor

**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`

**What to modify:**

**Inject ProjectContextBuilder** — add as constructor parameter.

**In `executeNode()` or the agent node execution path** (where system prompt is built, around the `String n = ... systemPrompt` lines):

Between planning context injection and the start of the system prompt building, add:

```java
// Inject project context for multi-session apps
if (currentSchema != null && currentSchema.getTargetPath() != null) {
    WorkflowSchema schema = schemaRepository.findById(schemaId);
    if (schema != null && schema.getTargetPath() != null) {
        String projectContext = projectContextBuilder.buildContext(
            schema.getTargetPath(), schema.getWorkspaceId());
        if (effectiveSystem != null) {
            effectiveSystem = projectContext + "\n\n" + effectiveSystem;
        } else {
            effectiveSystem = projectContext;
        }
    }
}
```

**Testing:** Integration test — create schema with targetPath, run agent, verify project context appears in system prompt.

---

## Step 8: ToolExecutor sandbox for targetPath

**File:** `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java`

**What to modify:**

**Add optional sandbox parameter** to the execute flow. Since ToolExecutor doesn't currently receive schema context, we need a way to pass the targetPath constraint.

**Option A (simpler):** Add a thread-local or method parameter to `handleFileWrite`:
```java
// Add a configurable field
private String sandboxAllowedPath; // null if no sandbox

public void setSandboxPath(String path) {
    this.sandboxAllowedPath = path;
}

private ToolResult handleFileWrite(Map<String, Object> params, ToolPermission permission) {
    String path = (String) params.get("path");
    if (path == null) return ToolResult.error("Missing path parameter");
    
    // Sandbox check
    if (sandboxAllowedPath != null) {
        Path targetPath = Path.of(path).normalize();
        Path allowedBase = Path.of(sandboxAllowedPath).normalize();
        if (!targetPath.startsWith(allowedBase)) {
            return ToolResult.error("BLOCKED: file_write outside target path: " + path 
                + ". Allowed: " + sandboxAllowedPath);
        }
    }
    
    try {
        Files.writeString(Path.of(path), content);
        return ToolResult.ok("File written: " + path);
    } catch (IOException e) {
        return ToolResult.error("Failed to write file: " + e.getMessage());
    }
}
```

**How sandbox is set:** In `SchemaService.executeWorkflow()`, before executing nodes:
```java
if (schema.getTargetPath() != null) {
    toolExecutor.setSandboxPath(schema.getTargetPath());
}
```

**Testing:** Unit test — try writing to path inside sandbox (should pass) and outside (should be BLOCKED).

---

## Step 9: Dashboard — Generated Apps section

**File:** `frontend/src/views/DashboardView.vue`

**What to modify:**

Add a new section **"My Generated Apps"** between "Featured Game" and "Templates":

```vue
<section class="generated-apps-section" v-if="generatedApps.length > 0">
  <h2>📦 My Generated Apps</h2>
  <div class="apps-grid">
    <div v-for="app in generatedApps" :key="app.id" class="app-card generated-app-card" @click="continueApp(app)">
      <div class="app-card-icon">{{ app.appType === 'GAME' ? '🎮' : '⚙️' }}</div>
      <div class="app-card-content">
        <h3>{{ app.name }}</h3>
        <p class="app-card-path">{{ app.targetPath }}</p>
        <p class="app-card-progress">{{ app.sessionsCompleted }} sessions</p>
      </div>
      <button class="btn-secondary" @click.stop="continueApp(app)">Continue</button>
    </div>
  </div>
</section>
```

**Also need:** A computed `generatedApps`:
```ts
const generatedApps = computed(() => 
  schemaStore.schemas.filter(s => s.targetPath != null)
)
```

**Add `targetPath` to the frontend types** in `frontend/src/types/index.ts`.

**Testing:** Visual check — create schema with GAME type, see it in new section.

---

## Step 10: Conflict resolution dialog (UI)

**File:** `frontend/src/views/DashboardView.vue` (extend the existing modal)

**What to modify:**

When `createFromTemplate` is called and the app type is GAME/GENERATOR/ANALYZER/EMAIL:

1. Call `POST /api/app` to create schema
2. If response indicates directory exists (`dirExists: true`), show a conflict modal:
   - Options: CONTINUE, OVERWRITE, CHANGE_PATH
   - If CHANGE_PATH: show text input for alternative path
3. On user selection: `POST /api/app/{id}/resolve-path-conflict { action, newPath? }`

**Alternatively:** Add a `checkPathConflict` endpoint called before `createApp`.

**Testing:** Manual — create Sokoban twice, verify conflict dialog appears on second attempt.

---

## Step 11: Execution results — file list display

**File:** This goes in LiveView — specifically `GenericAppUI.vue` or create a new section in `LiveView.vue` (or wherever execution results are shown post-execution).

**Actual location:** The execution results are managed in `useExecutionState.ts` and displayed through LiveView. The generated files come from the Plan API.

**What to add:**

After a schema finishes execution, fetch the plan's completed task and display generated files:

```vue
<div v-if="generatedFiles.length > 0" class="generated-files">
  <h3>📁 Generated Files</h3>
  <div class="file-list">
    <div v-for="file in generatedFiles" :key="file.path" class="file-item">
      <span class="file-status">✓</span>
      <span class="file-path">{{ file.path }}</span>
      <span class="file-desc">{{ file.description }}</span>
    </div>
  </div>
</div>
```

**Data source:** After execution completes, call `GET /api/plan?workspaceId={workspaceId}&format=tasks_only` and find the task with matching schemaId.

**Alternatively:** Add a new endpoint `GET /api/app/{id}/generated-files` for simplicity.

**Testing:** Verify that after execution, file list appears.

---

## Step 12: Frontend types update

**File:** `frontend/src/types/index.ts`

**What to add:**
```ts
export interface WorkflowSchema {
  // ... existing fields
  targetPath?: string
  targetPathConflictAction?: string
}
```

---

## Summary

| Step | File(s) | Est. effort | Depends on |
|------|---------|-------------|------------|
| 1 | WorkflowSchema.java | 10 min | — |
| 2 | Task.java | 15 min | — |
| 3 | AppController.java | 30 min | Step 1 |
| 4 | PlanService.java | 45 min | Step 2 |
| 5 | SchemaService.java | 30 min | Steps 1, 4 |
| 6 | ProjectContextBuilder.java (new) | 30 min | ~ |
| 7 | NodeExecutor.java | 20 min | Steps 6, 1 |
| 8 | ToolExecutor.java | 20 min | Step 1 |
| 9 | DashboardView.vue | 30 min | Step 1 |
| 10 | DashboardView.vue (conflict) | 30 min | Step 3 |
| 11 | LiveView / GenericAppUI | 30 min | Steps 4, 2 |
| 12 | types/index.ts | 5 min | Steps 1, 2 |

**Total: ~4-5 hours**
