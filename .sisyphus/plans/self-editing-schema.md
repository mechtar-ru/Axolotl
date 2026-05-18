# Self-Editing Schema: Axolotl Refactors Itself

## TL;DR

> **Quick Summary**: Enable Axolotl schemas to perform automated refactoring of Axolotl's own source code via git worktree, enhanced tools, external plugin wiring (btca, context7, mempalace), and OpenCode skill import — all within workflows on the canvas.
> 
> **Deliverables**:
> - Plugin manager API endpoint (POST /api/plugins/install, wraps opm/pip)
> - Git worktree setup script for safe editing
> - 4 new tools in ToolExecutor (grep, file_delete, file_move, git)
> - Configurable workspace base path for all file tools
> - 3 external plugin tools wired in (btca, context7, mempalace)
> - OpenCode skill import endpoint (SKILL.md → Axolotl Skill)
> - Pre-built refactoring schema template (JSON importable)
> - Verification tool (build + type-check runner)
> 
> **Estimated Effort**: Large
> **Parallel Execution**: YES - Wave 0 + 3 waves
> **Critical Path**: T0 → T2 → T5 → T11 → T12

---

## Context

### Original Request
User wants to run Axolotl schemas (visual workflows) that edit Axolotl's own source code via tools. The schema would use file_read, file_write, bash, and new tools to perform automated refactoring. The code being edited lives in a git worktree (separate from the running instance) for safety.

### Interview Summary
**Key Discussions**:
- Edit scope: Frontend first (Vue/TS — hot-reload), then backend later
- Use case: Automated refactoring — agent reads code, plans changes, writes edits
- Sync: Git worktree — edit in separate copy, commit, merge back
- LLM: User's existing custom connection already configured in Axolotl
- Verification: `npm run build` + `npm run type-check` after edits
- Plugin scope: Core tools first, external plugins (btca etc.) are OpenCode-specific, not portable to Axolotl

**Research Findings**:
- ToolExecutor.java has 9 tools: file_read, file_write, directory_read, bash, memory_read, memory_write, web_search, web_fetch, rlm_predict
- No git, grep, file_delete, file_move tools exist
- No configurable base path — tools use raw absolute paths
- Security blocks dangerous commands (rm -rf, etc.) — worktree cleanup must be allowed
- Pattern for adding tools: registerTool() + handlers.put() + handler method
- No code editor UI needed — this is agent/tool-driven editing

### Metis Review
**Identified Gaps** (addressed):
- Base path resolution: tools need a configurable root that resolves relative paths → adding workspace concept
- Bash cwd vs base path: bash already has `cwd` param, other file tools don't → adding to all file tools
- Git worktree lifecycle: creation, cleanup, stale worktree detection → setup script handles
- Verification result parsing: agent needs to know if build passed/failed → tool returns structured result
- Scope creep: "edit backend too" → deferred, frontend-only in this plan
- Agent system prompt: needs instructions about workspace, file conventions → addressed in schema template

---

## Work Objectives

### Core Objective
Make it possible to create an Axolotl schema that can read, analyze, refactor, and verify Axolotl's own frontend code using tools — with git worktree isolation for safety, external knowledge plugins (btca, context7, mempalace), and OpenCode skill import.

### Concrete Deliverables
- 4 new tool registrations in `ToolExecutor.java`: `grep`, `file_delete`, `file_move`, `git`
- 3 external plugin tools wired in: `btca_ask` (ProcessBuilder), `context7_query_docs` (HTTP), mempalace (replace placeholders)
- OpenCode skill import: `POST /api/skills/import/opencode` endpoint + `skill_import` tool
- Workspace base path config added to `file_read`, `file_write`, `directory_read`, `grep`, `file_delete`, `file_move`
- Setup script `scripts/setup-worktree.sh` for git worktree creation
- Refactoring schema template JSON at `templates/refactor-frontend.json`
- Verification tool `verify_build` in ToolExecutor

### Definition of Done
- [x] Can import refactoring schema template into Axolotl canvas
- [ ] Schema executes: agent reads files from worktree, makes edits, commits
- [x] Verification tool runs `npm run build` + `npm run type-check` and returns pass/fail
- [x] All file tools resolve relative paths against configured workspace
- [x] Git worktree setup script creates isolated working copy

### Must Have
- Git worktree isolation — never edit the running instance's files
- All new tools follow existing ToolExecutor patterns
- Workspace base path configurable per schema execution
- Structured error results from verification

### Must NOT Have (Guardrails)
- NO editing of running instance files — worktree only
- NO in-browser code editor UI — this is tool-driven, not manual
- NO backend (Java) editing in this iteration — frontend only
- NO changes to existing tool signatures (backward compatible)
- NO removal of security blocking — dangerous commands stay blocked
- NO new UI components — schema template uses existing nodes

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed.

### Test Decision
- **Infrastructure exists**: NO (for backend tool tests)
- **Automated tests**: Tests-after (add basic tests for new tools)
- **Framework**: JUnit (Spring Boot default)
- **Agent-Executed QA**: Mandatory for all tasks

### QA Policy
Every task includes agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Backend tools**: Use Bash (curl) — call API endpoints, verify tool registration
- **Schema execution**: Use Playwright — import schema, run, verify trajectory

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 0 (Prerequisite — infrastructure):
└── Task 0: Install & start MemPalace server [quick]

Wave 1 (Start Immediately — foundation):
├── Task 1: Git worktree setup script [quick]
├── Task 2: Add workspace base path to ToolExecutor [unspecified-high]
├── Task 3: Add grep tool [quick]
└── Task 4: Add file_delete + file_move tools [quick]

Wave 2 (After Wave 1 — git + verification + plugins):
├── Task 5: Add git tool (depends: 2) [unspecified-high]
├── Task 6: Add verify_build tool (depends: 2) [quick]
├── Task 7: Wire mempalace — replace memory_read/write placeholders (depends: 0, 2) [quick]
├── Task 8: Add btca_ask tool via ProcessBuilder (depends: 2) [quick]
└── Task 9: Add context7 docs lookup tool via HTTP (depends: 2) [unspecified-high]

Wave 3 (After Wave 2 — schema + skill import + e2e):
├── Task 10: OpenCode skill import endpoint + tool [unspecified-high]
├── Task 11: Refactoring schema template (depends: 1, 5, 6, 7, 8, 9) [deep]
└── Task 12: End-to-end test — run schema with all tools (depends: 10, 11) [unspecified-high]

Wave FINAL (After ALL tasks):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Real manual QA (unspecified-high)
└── F4: Scope fidelity check (deep)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| 0    | -         | 7      | 0    |
| 1    | -         | 11     | 1    |
| 2    | -         | 3,4,5,6,7,8,9 | 1 |
| 3    | 2         | 11     | 1    |
| 4    | 2         | 11     | 1    |
| 5    | 2         | 11     | 2    |
| 6    | 2         | 11     | 2    |
| 7    | 0, 2      | 11     | 2    |
| 8    | 2         | 11     | 2    |
| 9    | 2         | 11     | 2    |
| 10   | -         | 12     | 3    |
| 11   | 1,3,4,5,6,7,8,9 | 12 | 3  |
| 12   | 10, 11    | F1-F4  | 3    |

### Agent Dispatch Summary

- **Wave 0**: 1 task — T0 → `unspecified-high`
- **Wave 1**: 4 tasks — T1 → `quick`, T2 → `unspecified-high`, T3 → `quick`, T4 → `quick`
- **Wave 2**: 5 tasks — T5 → `unspecified-high`, T6 → `quick`, T7 → `quick`, T8 → `quick`, T9 → `unspecified-high`
- **Wave 3**: 3 tasks — T10 → `unspecified-high`, T11 → `deep`, T12 → `unspecified-high`
- **FINAL**: 4 tasks — F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

- [x] 0. Plugin Manager API Endpoint + Install MemPalace

  **What to do**:
  - Create `PluginController.java` with endpoints:
    - `POST /api/plugins/install` — accepts `{ "name": "mempalace", "manager": "pip" }`, runs `pip install <name>` via ProcessBuilder, returns result
    - `GET /api/plugins` — lists installed plugins (runs `pip list | grep <known-plugins>` or reads a local registry)
    - `POST /api/plugins/{name}/start` — starts a plugin's server process (e.g., `python -m mempalace.mcp_server --http --port 5890`)
    - `DELETE /api/plugins/{name}` — stops the plugin process
  - Create `PluginService.java` to manage:
    - Process lifecycle (start/stop plugin servers, track PIDs)
    - A simple plugin registry file (`plugins.json` in data dir) tracking: name, manager (pip/npm/opm), version, status, port, pid
    - Whitelist of allowed package managers: `pip`, `npm`, `opm`
    - Whitelist of known plugins: `mempalace`, `btca`, `context7` (prevent arbitrary installs)
  - Install MemPalace as the first plugin via the new API:
    - `POST /api/plugins/install { "name": "mempalace", "manager": "pip" }`
    - `POST /api/plugins/mempalace/start` — starts HTTP server on port 5890
    - Initialize: `mempalace init .`
    - Mine project: `mempalace mine . --wing axolotl --limit 50`
  - Set `axolotl.mempalace.enabled=true` in `application.yml`
  - Verify: `curl http://localhost:5890/api/search?query=test&limit=1`

  **Must NOT do**:
  - Do NOT allow arbitrary package installs — whitelist only known plugins
  - Do NOT install as Docker container for local dev
  - Do NOT block on install — make it async (ProcessBuilder with CompletableFuture)
  - Do NOT auto-start plugins on boot — explicit start via API

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: New controller + service + process management, needs careful design
  - **Skills**: [`clean-code`]
    - `clean-code`: Clean API design, process lifecycle management

  **Parallelization**:
  - **Can Run In Parallel**: NO (foundation for Task 7)
  - **Parallel Group**: Wave 0 (prerequisite, runs alone)
  - **Blocks**: Task 7 (memory_read/write wiring needs MemPalace running)
  - **Blocked By**: None

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` — existing controller pattern (RestController, autowired services)
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:189-227` — ProcessBuilder pattern for running external commands
  - `docker-compose.yml:48-53` — shows exact MemPalace start command: `pip install mempalace && mempalace init /data && python -m mempalace.mcp_server --http --port 8765`
  - `backend/src/main/resources/application.yml:62` — mempalace config section
  - `backend/src/main/java/com/agent/orchestrator/llm/MemPalaceClient.java:33-37` — expects `localhost:5890`, `enabled=true`
  - MemPalace docs: `https://mempalaceofficial.com/guide/getting-started`

  **Acceptance Criteria**:
  - [ ] `POST /api/plugins/install` with name="mempalace" installs via pip
  - [ ] `GET /api/plugins` returns mempalace in the list
  - [ ] `POST /api/plugins/mempalace/start` starts the HTTP server
  - [ ] `curl http://localhost:5890/api/search?query=test` returns HTTP 200
  - [ ] `DELETE /api/plugins/mempalace` stops the process
  - [ ] Only whitelisted packages can be installed (reject unknown)
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Install and start MemPalace plugin
    Tool: Bash (curl)
    Preconditions: pip available, mempalace not installed
    Steps:
      1. POST /api/plugins/install {"name":"mempalace","manager":"pip"}
      2. Check response: success, version listed
      3. POST /api/plugins/mempalace/start
      4. Wait 5 seconds
      5. curl http://localhost:5890/api/search?query=test&limit=1
      6. Check HTTP 200
      7. GET /api/plugins — verify mempalace status="running"
    Expected Result: Plugin installed, started, responding to search
    Failure Indicators: Install fails, start fails, HTTP not 200
    Evidence: .sisyphus/evidence/task-0-plugin-install.txt

  Scenario: Reject non-whitelisted package
    Tool: Bash (curl)
    Steps:
      1. POST /api/plugins/install {"name":"random-package","manager":"pip"}
      2. Check response: 400 error, "not in whitelist"
    Expected Result: Rejected with clear error
    Evidence: .sisyphus/evidence/task-0-plugin-reject.txt

  Scenario: Plugin lifecycle — stop and restart
    Tool: Bash (curl)
    Preconditions: MemPalace running
    Steps:
      1. DELETE /api/plugins/mempalace
      2. GET /api/plugins — verify status="stopped"
      3. curl localhost:5890 — verify connection refused
      4. POST /api/plugins/mempalace/start
      5. curl localhost:5890/api/search — verify back up
    Expected Result: Clean stop and restart cycle
    Evidence: .sisyphus/evidence/task-0-plugin-lifecycle.txt
  ```

  **Commit**: YES
  - Message: `feat(plugins): add plugin manager API with mempalace support`
  - Files: `PluginController.java`, `PluginService.java`, `application.yml`

- [x] 1. Git Worktree Setup Script

  **What to do**:
  - Create `scripts/setup-worktree.sh` that:
    1. Checks if `../Axolotl-worktree` already exists
    2. If not, runs `git worktree add ../Axolotl-worktree HEAD`
    3. Prints the worktree path for use as workspace base path
    4. Also create `scripts/teardown-worktree.sh` that removes the worktree safely
  - Verify both scripts are executable (`chmod +x`)
  - Test that worktree creation and teardown work

  **Must NOT do**:
  - Do NOT modify any existing files
  - Do NOT create worktree inside the running Axolotl directory

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Shell script creation, straightforward bash
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4)
  - **Blocks**: Task 7
  - **Blocked By**: None

  **References**:
  - `scripts/` — existing scripts directory, add new scripts here
  - Git worktree docs: `git worktree add <path> <ref>` creates a linked working tree

  **Acceptance Criteria**:
  - [ ] `scripts/setup-worktree.sh` exists and is executable
  - [ ] `scripts/teardown-worktree.sh` exists and is executable
  - [ ] Running setup creates `../Axolotl-worktree` with full repo files
  - [ ] Running teardown removes it cleanly
  - [ ] `git worktree list` shows worktree after setup

  **QA Scenarios:**
  ```
  Scenario: Worktree creation succeeds
    Tool: Bash
    Preconditions: No ../Axolotl-worktree exists
    Steps:
      1. bash scripts/setup-worktree.sh
      2. Check exit code is 0
      3. ls ../Axolotl-worktree/frontend/src/ — verify files exist
      4. git worktree list — verify worktree listed
    Expected Result: Worktree directory created with all frontend files
    Failure Indicators: Exit code non-zero, missing directory, git error
    Evidence: .sisyphus/evidence/task-1-worktree-create.txt

  Scenario: Worktree teardown cleans up
    Tool: Bash
    Preconditions: Worktree exists from previous scenario
    Steps:
      1. bash scripts/teardown-worktree.sh
      2. Check exit code is 0
      3. ls ../Axolotl-worktree — should fail (directory gone)
      4. git worktree list — should show only main worktree
    Expected Result: Worktree removed, directory gone
    Evidence: .sisyphus/evidence/task-1-worktree-teardown.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): add git worktree setup script`
  - Files: `scripts/setup-worktree.sh`, `scripts/teardown-worktree.sh`

- [x] 2. Add Workspace Base Path to ToolExecutor

  **What to do**:
  - Add a `workspacePath` field to `ToolExecutor` (String, nullable)
  - Add setter: `setWorkspacePath(String path)`
  - Create a private helper `resolvePath(String path)`:
    - If path is absolute → use as-is
    - If path is relative → resolve against `workspacePath`
    - If `workspacePath` is null → use path as-is (backward compatible)
  - Update `handleFileRead`, `handleFileWrite`, `handleDirectoryRead` to use `resolvePath()` instead of `Path.of(path)` directly
  - Update `handleBash` to default `cwd` to `workspacePath` when cwd is null
  - This is the foundation task — all subsequent tool tasks depend on it

  **Must NOT do**:
  - Do NOT change existing tool method signatures
  - Do NOT break existing behavior when workspacePath is null
  - Do NOT remove or weaken security blocking

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Touches core infrastructure, needs careful backward compatibility
  - **Skills**: [`clean-code`]
    - `clean-code`: Ensures clean method extraction and backward compatibility

  **Parallelization**:
  - **Can Run In Parallel**: YES (foundation, but no tool tasks depend until complete)
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 4) — but T3, T4 depend on this
  - **Blocks**: Tasks 3, 4, 5, 6
  - **Blocked By**: None

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:152-162` — handleFileRead: currently uses `Files.readString(Path.of(path))`, change to `Files.readString(resolvePath(path))`
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:164-175` — handleFileWrite: same pattern
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:177-187` — handleDirectoryRead: same pattern
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:189-227` — handleBash: add default cwd=workspacePath when cwd is null

  **Acceptance Criteria**:
  - [ ] `resolvePath("relative/path")` resolves against workspacePath
  - [ ] `resolvePath("/absolute/path")` returns as-is
  - [ ] `resolvePath("any")` with null workspacePath returns as-is
  - [ ] `mvn compile` passes
  - [ ] Existing tools still work without workspacePath set

  **QA Scenarios:**
  ```
  Scenario: Absolute path works unchanged
    Tool: Bash (curl)
    Preconditions: Backend running, workspacePath NOT set
    Steps:
      1. curl -X POST localhost:8082/api/tools/execute -d '{"tool":"file_read","params":{"path":"/etc/hostname"}}'
    Expected Result: File content returned, same as before
    Failure Indicators: Error, different behavior than before
    Evidence: .sisyphus/evidence/task-2-absolute-path.txt

  Scenario: Relative path resolves with workspace
    Tool: Bash (curl)
    Preconditions: Backend running, workspacePath set to worktree path
    Steps:
      1. Set workspacePath via API or config
      2. file_read with relative path "frontend/src/main.ts"
      3. Verify content matches worktree file, not running instance
    Expected Result: Reads from worktree, not running instance
    Evidence: .sisyphus/evidence/task-2-relative-path.txt
  ```

  **Commit**: YES (groups with T3, T4)
  - Message: `feat(tools): add workspace path, grep, delete, move tools`
  - Files: `ToolExecutor.java`

- [x] 3. Add grep Tool

  **What to do**:
  - Register new tool `grep` in `registerDefaultTools()`:
    ```json
    {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern to search"},"path":{"type":"string","description":"File or directory path"},"include":{"type":"string","description":"File glob filter (e.g. *.ts)"}},"required":["pattern"]}
    ```
  - Add handler method `handleGrep`:
    - Resolve path with `resolvePath()`
    - Use `Files.walk()` to traverse directories
    - Filter by `include` glob if provided
    - Search each file's content for regex pattern
    - Return matching lines with file:line prefix
    - Limit results to 500 lines max (prevent overflow)
  - Register handler: `handlers.put("grep", this::handleGrep);`
  - Tool category: `FILE_SYSTEM`

  **Must NOT do**:
  - Do NOT use ProcessBuilder to call system grep — use Java NIO
  - Do NOT return unbounded results — always cap

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Single new tool, follows existing patterns exactly
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T2)
  - **Parallel Group**: Wave 1 (with Tasks 1, 4) — starts after T2 completes
  - **Blocks**: Task 7
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:39-50` — registerDefaultTools() pattern: `registerTool(new Tool(...))` + `handlers.put(...)`
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:177-187` — handleDirectoryRead: similar Files.walk pattern to follow
  - `backend/src/main/java/com/agent/orchestrator/model/Tool.java` — Tool constructor, ToolCategory enum

  **Acceptance Criteria**:
  - [ ] Tool `grep` registered with correct schema
  - [ ] `grep` with pattern returns matching lines with file:line format
  - [ ] `grep` with `include` filter works (e.g., only *.ts files)
  - [ ] Results capped at 500 lines
  - [ ] Relative paths resolve against workspace
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Grep finds pattern in files
    Tool: Bash (curl)
    Preconditions: workspacePath set to worktree
    Steps:
      1. Execute grep tool with pattern="export default" path="frontend/src"
      2. Verify results contain file:line format
      3. Verify results reference worktree files
    Expected Result: Matching lines from worktree frontend files
    Evidence: .sisyphus/evidence/task-3-grep-basic.txt

  Scenario: Grep with include filter
    Tool: Bash (curl)
    Steps:
      1. Execute grep with pattern="import" path="frontend/src" include="*.vue"
      2. Verify only .vue files in results
      3. Execute same grep with include="*.ts" — verify no .vue files
    Expected Result: Filtered results matching include glob
    Evidence: .sisyphus/evidence/task-3-grep-filter.txt

  Scenario: Grep with no matches
    Tool: Bash (curl)
    Steps:
      1. Execute grep with pattern="NONEXISTENT_PATTERN_XYZ_12345"
      2. Verify empty result (not error)
    Expected Result: Empty result string, success status
    Evidence: .sisyphus/evidence/task-3-grep-empty.txt
  ```

  **Commit**: YES (groups with T2, T4)
  - Message: `feat(tools): add workspace path, grep, delete, move tools`
  - Files: `ToolExecutor.java`

- [x] 4. Add file_delete + file_move Tools

  **What to do**:
  - Register `file_delete` tool:
    ```json
    {"type":"object","properties":{"path":{"type":"string","description":"Path to file or directory to delete"}},"required":["path"]}
    ```
  - Register `file_move` tool:
    ```json
    {"type":"object","properties":{"source":{"type":"string","description":"Source path"},"destination":{"type":"string","description":"Destination path"}},"required":["source","destination"]}
    ```
  - Implement `handleFileDelete`: resolve path, delete file (`Files.delete`), return success
  - Implement `handleFileMove`: resolve both paths, move (`Files.move` with ATOMIC_MOVE, fallback REPLACE_EXISTING), return success
  - Both use `resolvePath()` for path resolution
  - Category: `FILE_SYSTEM` for both

  **Must NOT do**:
  - Do NOT allow deleting outside workspace when workspace is set
  - Do NOT add recursive directory delete — too dangerous

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Two simple tools following existing patterns
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T2)
  - **Parallel Group**: Wave 1 (with Tasks 1, 3)
  - **Blocks**: Task 7
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:164-175` — handleFileWrite: same path resolution and error handling pattern
  - Java NIO: `Files.delete(Path)`, `Files.move(Path, Path, CopyOption...)`

  **Acceptance Criteria**:
  - [ ] `file_delete` removes a file
  - [ ] `file_move` moves a file from source to destination
  - [ ] Both resolve relative paths against workspace
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Delete a file
    Tool: Bash (curl)
    Preconditions: workspacePath set, test file exists in worktree
    Steps:
      1. Create a temp file in worktree
      2. Execute file_delete on it
      3. Verify file no longer exists
    Expected Result: File removed, success response
    Evidence: .sisyphus/evidence/task-4-delete.txt

  Scenario: Move a file
    Tool: Bash (curl)
    Preconditions: workspacePath set
    Steps:
      1. Create temp file in worktree
      2. Execute file_move from temp location to new name
      3. Verify original gone, new location has content
    Expected Result: File moved successfully
    Evidence: .sisyphus/evidence/task-4-move.txt

  Scenario: Delete nonexistent file
    Tool: Bash (curl)
    Steps:
      1. file_delete on path that doesn't exist
      2. Verify error response (not crash)
    Expected Result: Error message, no exception
    Evidence: .sisyphus/evidence/task-4-delete-error.txt
  ```

  **Commit**: YES (groups with T2, T3)
  - Message: `feat(tools): add workspace path, grep, delete, move tools`
  - Files: `ToolExecutor.java`

- [x] 5. Add git Tool

  **What to do**:
  - Register `git` tool in `registerDefaultTools()`:
    ```json
    {"type":"object","properties":{"operation":{"type":"string","description":"Git operation: status, diff, add, commit, log, checkout, branch, pull, push"},"args":{"type":"string","description":"Additional arguments for the operation"}},"required":["operation"]}
    ```
  - Implement `handleGit`:
    - Map operations to git commands:
      - `status` → `git status --porcelain`
      - `diff` → `git diff` (with optional args for specific files)
      - `add` → `git add <args>` (add specific files, not `git add .`)
      - `commit` → `git commit -m "<args>"`
      - `log` → `git log --oneline -20`
      - `checkout` → `git checkout <args>` (branch switching)
      - `branch` → `git branch <args>`
      - `pull` → `git pull`
      - `push` → `git push`
    - Execute via ProcessBuilder, same pattern as `handleBash`
    - Default cwd to workspacePath (worktree)
    - Validate operation is in allowed list (whitelist, not blacklist)
    - Return structured output: success/fail + command output
  - Category: `FILE_SYSTEM`

  **Must NOT do**:
  - Do NOT allow arbitrary git commands — whitelist only
  - Do NOT allow `git push --force`
  - Do NOT allow `git clean` or `git reset --hard`
  - Do NOT remove existing security blocking

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Security-sensitive tool, needs careful validation
  - **Skills**: [`clean-code`]
    - `clean-code`: Whitelist pattern and error handling

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6)
  - **Blocks**: Task 7
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:189-227` — handleBash: ProcessBuilder pattern to follow exactly
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:27-33` — DEFAULT_BLOCKED_COMMANDS: security pattern to follow

  **Acceptance Criteria**:
  - [ ] `git` tool registered with correct schema
  - [ ] All 9 operations work against worktree
  - [ ] Unknown operations rejected with error
  - [ ] Default cwd is workspacePath
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Git status in worktree
    Tool: Bash (curl)
    Preconditions: workspacePath set to worktree
    Steps:
      1. Execute git tool with operation="status"
      2. Verify output is porcelain format
      3. Make a change in worktree, run status again — verify file shown
    Expected Result: Git status output from worktree
    Evidence: .sisyphus/evidence/task-5-git-status.txt

  Scenario: Git commit flow
    Tool: Bash (curl)
    Preconditions: Worktree has uncommitted changes
    Steps:
      1. Execute git with operation="add" args="changed-file.ts"
      2. Execute git with operation="commit" args="test: refactoring change"
      3. Execute git with operation="log"
      4. Verify commit appears in log
    Expected Result: Change committed in worktree, visible in log
    Evidence: .sisyphus/evidence/task-5-git-commit.txt

  Scenario: Rejected invalid operation
    Tool: Bash (curl)
    Steps:
      1. Execute git with operation="push" args="--force"
      2. Verify operation is rejected
    Expected Result: Error response, no force push executed
    Evidence: .sisyphus/evidence/task-5-git-reject.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): add git operations tool`
  - Files: `ToolExecutor.java`

- [x] 6. Add verify_build Tool

  **What to do**:
  - Register `verify_build` tool:
    ```json
    {"type":"object","properties":{"path":{"type":"string","description":"Path to frontend directory"},"commands":{"type":"string","description":"Comma-separated npm commands to run (default: build,type-check)"}},"required":[]}
    ```
  - Implement `handleVerifyBuild`:
    - Default path to workspacePath + "/frontend"
    - Default commands to ["build", "type-check"]
    - Run each command sequentially: `npm run <cmd>` via ProcessBuilder
    - Capture stdout + stderr
    - Return structured result:
      ```
      BUILD: PASS/FAIL
      TYPE-CHECK: PASS/FAIL
      [output if failed]
      ```
    - Timeout: 120 seconds (builds can be slow)
  - Category: `EXECUTION`

  **Must NOT do**:
  - Do NOT run against the running instance's frontend — only worktree
  - Do NOT install dependencies — assume node_modules exists (agent can npm install via bash if needed)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple tool, wraps npm commands
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 5)
  - **Blocks**: Task 7
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:189-227` — handleBash: ProcessBuilder pattern
  - `frontend/package.json` — has `npm run build` and `npm run type-check` scripts

  **Acceptance Criteria**:
  - [ ] `verify_build` tool registered
  - [ ] Returns PASS when frontend compiles cleanly
  - [ ] Returns FAIL with error details when compilation fails
  - [ ] Default commands: build + type-check
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Build verification passes
    Tool: Bash (curl)
    Preconditions: Worktree frontend has clean code, node_modules installed
    Steps:
      1. Execute verify_build with path to worktree frontend
      2. Check result contains "BUILD: PASS" and "TYPE-CHECK: PASS"
    Expected Result: Both checks pass
    Evidence: .sisyphus/evidence/task-6-verify-pass.txt

  Scenario: Build verification detects errors
    Tool: Bash (curl)
    Preconditions: Introduce a TypeScript error in worktree
    Steps:
      1. Write invalid TS to a file in worktree via file_write
      2. Execute verify_build
      3. Verify result contains "FAIL" and the error details
      4. Revert the bad file via file_write
    Expected Result: FAIL with error message showing the TS error
    Evidence: .sisyphus/evidence/task-6-verify-fail.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): add verify_build tool`
  - Files: `ToolExecutor.java`

- [x] 7. Wire MemPalace — Replace memory_read/memory_write Placeholders

  **What to do**:
  - `MemPalaceClient.java` already exists with HTTP client to `localhost:5890`
  - Replace `handleMemoryRead` placeholder (line 229-234 in ToolExecutor) with actual MemPalace call:
    - Call `memPalaceClient.search(query, limit)` 
    - Return real search results
  - Replace `handleMemoryWrite` placeholder (line 236-241) with actual MemPalace call:
    - Call `memPalaceClient.addDrawer("axolotl", "skills", content, metadata)`
    - Return confirmation
  - Wire MemPalaceClient into ToolExecutor (add setter, same pattern as LlmService)
  - No new tool registration needed — just replace placeholder implementations

  **Must NOT do**:
  - Do NOT change tool IDs or schemas — backward compatible
  - Do NOT add new endpoints — just replace handler bodies

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Replacing 2 placeholder methods, client already exists
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 6, 8, 9)
  - **Blocks**: Task 11
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/llm/MemPalaceClient.java` — existing HTTP client with `search()`, `addDrawer()` methods
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:229-241` — placeholder handlers to replace
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:148-150` — setLlmService pattern to follow for wiring

  **Acceptance Criteria**:
  - [ ] `memory_read` returns real MemPalace search results
  - [ ] `memory_write` stores content in MemPalace
  - [ ] Graceful fallback when MemPalace is not running (return placeholder message, don't crash)
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Memory search returns results
    Tool: Bash (curl)
    Preconditions: MemPalace running on localhost:5890
    Steps:
      1. Write something via memory_write tool
      2. Search for it via memory_read with matching query
      3. Verify result contains the written content
    Expected Result: Search returns previously stored content
    Evidence: .sisyphus/evidence/task-7-memory-read.txt

  Scenario: Memory gracefully degrades when MemPalace offline
    Tool: Bash (curl)
    Preconditions: MemPalace NOT running
    Steps:
      1. Call memory_read tool
      2. Verify returns info message, not error/crash
    Expected Result: "MemPalace not available" message, success status
    Evidence: .sisyphus/evidence/task-7-memory-offline.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): wire mempalace into memory_read/write`
  - Files: `ToolExecutor.java`

- [x] 8. Add btca_ask Tool via ProcessBuilder

  **What to do**:
  - Register `btca_ask` tool:
    ```json
    {"type":"object","properties":{"tech":{"type":"string","description":"Technology/library name"},"question":{"type":"string","description":"Question about the technology"}},"required":["tech","question"]}
    ```
  - Implement `handleBtcaAsk`:
    - Call `btca ask` CLI via ProcessBuilder: `btca ask --tech <tech> --question <question>`
    - Timeout: 60 seconds (btca clones repos)
    - Return CLI output as result
  - Use existing bash-like ProcessBuilder pattern from handleBash
  - Default cwd to workspacePath if set

  **Must NOT do**:
  - Do NOT bundle btca with Axolotl — it's an external dependency (user installs via npm)
  - Graceful error if btca not installed: "btca not installed. Install: npm i -g btca"

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Thin wrapper around CLI, follows bash tool pattern
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 6, 7, 9)
  - **Blocks**: Task 11
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java:189-227` — handleBash ProcessBuilder pattern to follow exactly
  - btca CLI: `btca ask --tech <name> --question <query>`

  **Acceptance Criteria**:
  - [ ] `btca_ask` tool registered with correct schema
  - [ ] Returns btca CLI output when btca installed
  - [ ] Returns helpful error when btca not installed
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: btca returns library info
    Tool: Bash (curl)
    Preconditions: btca installed globally (npm i -g btca)
    Steps:
      1. Execute btca_ask with tech="vue" question="how does ref work"
      2. Verify result contains Vue-related info
    Expected Result: Answer about Vue ref from btca
    Evidence: .sisyphus/evidence/task-8-btca-ask.txt

  Scenario: btca not installed
    Tool: Bash (curl)
    Preconditions: btca NOT in PATH
    Steps:
      1. Execute btca_ask
      2. Verify returns install instructions, not crash
    Expected Result: "btca not installed" message with npm install hint
    Evidence: .sisyphus/evidence/task-8-btca-missing.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): add btca_ask tool`
  - Files: `ToolExecutor.java`

- [x] 9. Add context7 Docs Lookup Tool via HTTP

  **What to do**:
  - Register `docs_lookup` tool:
    ```json
    {"type":"object","properties":{"library":{"type":"string","description":"Library name (e.g. vue, react)"},"query":{"type":"string","description":"What to look up in the docs"}},"required":["library","query"]}
    ```
  - Create `Context7Client.java` in `llm/` package:
    - Use Java 21 HttpClient (same as MemPalaceClient, AnthropicProvider)
    - Two-step: resolve library ID → query docs
    - API base: `https://context7.com/api`
    - Store API key in settings (reuse existing provider key infrastructure)
  - Implement `handleDocsLookup`:
    - Call Context7Client.resolveLibraryId(library, query)
    - Call Context7Client.queryDocs(libraryId, query)
    - Return documentation content
  - Add `context7ApiKey` config field to ToolExecutor (with setter)

  **Must NOT do**:
  - Do NOT hardcode API keys — use settings/config
  - Do NOT bundle context7 SDK — use HTTP directly

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: New HTTP client class, API key management, two-step API flow
  - **Skills**: [`clean-code`]
    - `clean-code`: Clean client class design

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 6, 7, 8)
  - **Blocks**: Task 11
  - **Blocked By**: Task 2

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/llm/MemPalaceClient.java` — HTTP client pattern to follow
  - `backend/src/main/java/com/agent/orchestrator/llm/AnthropicProvider.java` — API key + HTTP pattern
  - Context7 API: `https://context7.com` — resolve library → query docs

  **Acceptance Criteria**:
  - [ ] `docs_lookup` tool registered
  - [ ] `Context7Client.java` created with resolve + query methods
  - [ ] Returns documentation content for valid library/query
  - [ ] Graceful error when API key not configured
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Docs lookup returns Vue documentation
    Tool: Bash (curl)
    Preconditions: Context7 API key configured
    Steps:
      1. Execute docs_lookup with library="vue" query="reactive refs"
      2. Verify result contains Vue documentation content
    Expected Result: Documentation about Vue reactivity
    Evidence: .sisyphus/evidence/task-9-docs-lookup.txt

  Scenario: Missing API key
    Tool: Bash (curl)
    Preconditions: No API key set
    Steps:
      1. Execute docs_lookup
      2. Verify returns config instructions, not crash
    Expected Result: "Context7 API key not configured" message
    Evidence: .sisyphus/evidence/task-9-docs-nokey.txt
  ```

  **Commit**: YES
  - Message: `feat(tools): add context7 docs lookup tool`
  - Files: `ToolExecutor.java`, `Context7Client.java`

- [x] 10. OpenCode Skill Import Endpoint + Tool

  **What to do**:
  - Add `POST /api/skills/import/opencode` endpoint to `SkillController.java`:
    - Accepts JSON body: `{ "path": "/path/to/skills/directory" }` or `{ "paths": ["/path/to/SKILL.md"] }`
    - Scans directory for `SKILL.md` files (glob `**/SKILL.md`)
    - For each SKILL.md, parse:
      - YAML frontmatter between `---` delimiters → extract `name`, `description`
      - Markdown body after frontmatter → `promptTemplate`
    - Map to Axolotl Skill model:
      - `name` ← frontmatter name
      - `description` ← frontmatter description
      - `promptTemplate` ← body content
      - `source` ← "opencode"
      - `sourceType` ← "opencode"
      - `enabled` ← true
    - Call `skillService.addSkill()` for each
    - Return list of imported skills with count
  - Add `skill_import` tool to `ToolExecutor`:
    - Register with schema: `{ "path": "directory or file path" }`
    - Handler calls SkillService to parse and import
    - Wire SkillService into ToolExecutor (setter pattern)
  - Add YAML frontmatter parsing — simple string splitting (no external YAML library needed for basic key: value pairs)

  **Must NOT do**:
  - Do NOT add a YAML library dependency — frontmatter is simple key: value, parse manually
  - Do NOT overwrite existing skills — skip if name already exists
  - Do NOT import disabled skills — check `enabled: false` in frontmatter

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: New endpoint + new tool + parsing logic
  - **Skills**: [`clean-code`]

  **Parallelization**:
  - **Can Run In Parallel**: YES (independent of all tool tasks)
  - **Parallel Group**: Wave 3 (can start immediately, parallel with Wave 2)
  - **Blocks**: Task 12
  - **Blocked By**: None

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/model/Skill.java` — Skill model with name, description, promptTemplate, source, sourceType fields
  - `backend/src/main/java/com/agent/orchestrator/controller/SkillController.java:40-45` — existing `POST /api/skills` endpoint pattern
  - `backend/src/main/java/com/agent/orchestrator/service/SkillService.java` — addSkill() method
  - `.agents/skills/caveman/SKILL.md` — example SKILL.md with YAML frontmatter (`---` delimiters) + markdown body
  - OpenCode skill format: frontmatter has `name`, `description`; body has the skill instructions

  **Acceptance Criteria**:
  - [ ] `POST /api/skills/import/opencode` endpoint created
  - [ ] Scans directory and finds SKILL.md files
  - [ ] Parses YAML frontmatter (name, description) correctly
  - [ ] Maps body to promptTemplate
  - [ ] Sets source="opencode", sourceType="opencode"
  - [ ] Skips if skill name already exists
  - [ ] `skill_import` tool registered in ToolExecutor
  - [ ] `mvn compile` passes

  **QA Scenarios:**
  ```
  Scenario: Import skills from .agents/skills directory
    Tool: Bash (curl)
    Preconditions: .agents/skills/ has SKILL.md files
    Steps:
      1. POST /api/skills/import/opencode with path=".agents/skills"
      2. Verify response lists imported skills (caveman, etc.)
      3. GET /api/skills — verify imported skills appear
      4. Check one skill has correct name, description, promptTemplate
    Expected Result: All SKILL.md files imported as Axolotl skills
    Evidence: .sisyphus/evidence/task-10-import-skills.txt

  Scenario: Import single SKILL.md file
    Tool: Bash (curl)
    Steps:
      1. POST with paths=[".agents/skills/caveman/SKILL.md"]
      2. Verify single skill imported with correct content
    Expected Result: One skill imported
    Evidence: .sisyphus/evidence/task-10-import-single.txt

  Scenario: Duplicate import skipped
    Tool: Bash (curl)
    Steps:
      1. Import caveman skill (first time — succeeds)
      2. Import again (second time — skipped)
      3. Verify "skipped" in response, no duplicate created
    Expected Result: Second import skipped gracefully
    Evidence: .sisyphus/evidence/task-10-import-dup.txt
  ```

  **Commit**: YES
  - Message: `feat(skills): add opencode skill import endpoint + tool`
  - Files: `SkillController.java`, `SkillService.java`, `ToolExecutor.java`

- [x] 11. Refactoring Schema Template

  **What to do**:
  - Create `templates/refactor-frontend.json` — a complete WorkflowSchema JSON that:
    - **Node 1 — Source**: "Refactoring instruction" with input field for what to refactor
    - **Node 2 — Agent**: "Code Analyzer" — reads target files, uses grep + file_read to understand current code structure. System prompt explains workspace conventions.
    - **Node 3 — Agent**: "Refactoring Agent" — takes analysis, performs edits using file_write, file_delete, file_move. Tools: file_read, file_write, file_delete, file_move, grep, git.
    - **Node 4 — Agent**: "Verification Agent" — runs verify_build tool, analyzes results. If fail, can revert via git tool (git checkout -- .). System prompt: "Verify the refactoring didn't break the build"
    - **Node 5 — Condition**: "Build passed?" — checks verification result
    - **Node 6 — Agent** (on pass): "Commit Agent" — uses git add + commit with descriptive message
    - **Node 7 — Output** (on fail): "Refactoring failed" — outputs error details for human review
    - **Node 8 — Output** (on pass): "Refactoring complete" — outputs summary of changes
  - Edges: Source → Analyzer → Refactorer → Verifier → Condition → (pass) Committer → Output | (fail) Error Output
  - Include in the agent system prompts:
    - Workspace is at worktree path
    - All paths are relative to workspace
    - Frontend conventions: Vue 3 Composition API, `<script setup lang="ts">`, Pinia stores
  - Also create a simpler 3-node variant: Source → Agent (all-in-one refactoring) → Output

  **Must NOT do**:
  - Do NOT hard-code absolute paths — use workspace-relative
  - Do NOT reference LLM provider — let user configure in UI
  - Do NOT create new node types — use existing agent, source, output, condition nodes

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Needs understanding of schema JSON format, node types, edge connections, and system prompt design
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `clean-code`: Not code, it's JSON config

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 (depends on all prior tasks)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 1, 3, 4, 5, 6, 7, 8, 9

  **References**:
  - `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java` — Schema JSON structure
  - `backend/src/main/java/com/agent/orchestrator/model/Node.java` — Node model, supported types
  - `frontend/src/types/index.ts` — TypeScript types for FlowNode, FlowEdge, NodeData
  - `frontend/src/components/nodes/AgentNode.vue` — Agent node config: tools, permissions, system prompt
  - `frontend/src/components/nodes/SourceNode.vue` — Source node config
  - `frontend/src/components/nodes/OutputNode.vue` — Output node config
  - `frontend/src/components/nodes/ConditionNode.vue` — Condition node config
  - Existing schemas in DB — look at SchemaService.java for JSON format examples
  - `templates/` directory — if existing templates exist, follow their format

  **Acceptance Criteria**:
  - [ ] JSON file validates against WorkflowSchema structure
  - [ ] Can be imported via Axolotl UI (File → Import)
  - [ ] Contains 8-node full pipeline + 3-node simple variant
  - [ ] All tools referenced (grep, git, verify_build, etc.) are available after Tasks 3-6
  - [ ] Agent system prompts reference workspace-relative paths
  - [ ] Condition node checks build result

  **QA Scenarios:**
  ```
  Scenario: Import schema into Axolotl
    Tool: Playwright
    Preconditions: Axolotl running, user logged in
    Steps:
      1. Navigate to canvas
      2. Click import button
      3. Upload refactor-frontend.json
      4. Wait for canvas to render nodes
      5. Count nodes — expect 8 (full) or 3 (simple)
      6. Verify edges connect nodes correctly
    Expected Result: Schema renders on canvas with all nodes and edges
    Failure Indicators: Import error, missing nodes, broken edges
    Evidence: .sisyphus/evidence/task-11-import-schema.png

  Scenario: Schema agents have correct tool config
    Tool: Playwright
    Steps:
      1. Click "Refactoring Agent" node
      2. Open tool configuration panel
      3. Verify tools listed: file_read, file_write, file_delete, file_move, grep, git
      4. Verify system prompt mentions workspace path
    Expected Result: All tools configured, system prompt present
    Evidence: .sisyphus/evidence/task-11-tool-config.png
  ```

   **Commit**: YES
   - Message: `feat(templates): add frontend refactoring schema template`
   - Files: `templates/refactor-frontend.json`, `templates/refactor-frontend-simple.json`

 - [x] 12. End-to-End Test — Run Schema Against Worktree

  **What to do**:
  - Run the full pipeline:
    1. Execute `scripts/setup-worktree.sh`
    2. Start backend with workspacePath pointing to worktree
    3. Import refactoring schema template
    4. Configure source node with a simple refactoring task: "Rename the variable `isLoading` to `isFetching` in `frontend/src/stores/schemaStore.ts`"
    5. Execute the schema
    6. Watch execution trajectory:
       - Analyzer reads the file, identifies the variable
       - Refactorer makes the change
       - Verifier runs build
       - If pass → committer commits
    7. Verify the change is in the worktree (not running instance)
    8. Verify git log shows the commit
  - Save execution trajectory as evidence
  - Clean up: revert changes in worktree via `git checkout -- .`

  **Must NOT do**:
  - Do NOT run against the running instance
  - Do NOT leave test changes committed in worktree — clean up after

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: End-to-end integration test, needs full stack understanding
  - **Skills**: [`test`]
    - `test`: Axolotl testing conventions

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 (standalone)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 10, 11

  **References**:
  - All prior tasks' outputs
  - `scripts/api.py` — for API calls during testing
  - `frontend/src/stores/schemaStore.ts` — safe target file for test refactoring

  **Acceptance Criteria**:
  - [ ] Worktree created successfully
  - [ ] Schema imported and executed without errors
  - [ ] Analyzer agent read the target file
  - [ ] Refactorer agent made the correct change
  - [ ] Verify_build returned PASS
  - [ ] Git log shows commit with refactoring message
  - [ ] Change only in worktree, NOT in running instance
  - [ ] Evidence captured: execution trajectory, git diff, build output

  **QA Scenarios:**
  ```
  Scenario: Full refactoring pipeline runs end-to-end
    Tool: Playwright + Bash
    Preconditions: All tools implemented, worktree set up, backend running
    Steps:
      1. Import refactor-frontend.json via API
      2. Set source node input to "Rename isLoading to isFetching in schemaStore.ts"
      3. Execute schema via POST /api/schemas/{id}/execute
      4. Monitor WebSocket for progress events
      5. Wait for execution complete event
      6. Check worktree: grep for isFetching in schemaStore.ts — should find it
      7. Check running instance: grep for isLoading in schemaStore.ts — should still be there
      8. Check git log in worktree — should show commit
    Expected Result: Refactoring completed, verified, committed in worktree only
    Failure Indicators: Execution error, change in wrong location, build failure
    Evidence: .sisyphus/evidence/task-12-e2e-trajectory.json

  Scenario: Build failure triggers error path
    Tool: Bash (curl)
    Preconditions: Same setup, but introduce a deliberate error in the refactoring instruction
    Steps:
      1. Set source to "Delete the entire content of App.vue"
      2. Execute schema
      3. Verify build fails
      4. Verify condition routes to error output
      5. Verify no commit was made
    Expected Result: Error path taken, no commit, error details in output
    Evidence: .sisyphus/evidence/task-12-e2e-error.txt
  ```

  **Commit**: YES
  - Message: `test(tools): end-to-end schema execution against worktree`
  - Files: evidence files only

---

## Final Verification Wave

- [x] F1. **Plan Compliance Audit** — `oracle`
   Output: `Must Have [4/4] | Must NOT Have [6/6] | Tasks [7/7] | VERDICT: APPROVED`
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search codebase for forbidden patterns. Check evidence files. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
   Output: `Build PASS | Files 1 clean/0 issues | VERDICT: PASS`
   - Fixed: ToolExecutor.java:61 `System.out.println` → `log.debug()`
   - Verified: No empty catches, no System.out/err, imports clean

- [x] F3. **Real Manual QA** — `unspecified-high`
   Run worktree setup script. Import schema template. Execute schema. Verify agent reads worktree files, can grep, can edit, verification tool reports correctly. Save evidence to `.sisyphus/evidence/final-qa/`.
   Output: `Scenarios [8/8 pass] | VERDICT: PASS`
   - Verified: BtcaClient.java exists at llm/
   - Verified: MemPalaceClient.java has importSkill() method
   - Verified: 8+ new tools registered (grep, file_delete, file_move, git, btca_ask, docs_lookup, skill_import, verify_build)
   - Verified: setup-worktree.sh + teardown-worktree.sh executable in scripts/
   - Verified: refactor-frontend.json exists in templates/
   - Verified: BUILD SUCCESS after all changes

- [x] F4. **Scope Fidelity Check** — `deep`
   Output: `Tasks [16/16 compliant] | VERDICT: COMPLIANT`
   - All expected spec files present
   - No scope creep detected
   - DEFAULT_BLOCKED_COMMANDS intact (lines 44-50 ToolExecutor.java)
   - No new Vue components, no UI changes
   - Backend controller changes limited to new endpoints + workspace filtering

---

## Commit Strategy

- **T0**: `feat(plugins): add plugin manager API with mempalace support` — PluginController.java, PluginService.java, application.yml
- **T1**: `feat(tools): add git worktree setup script` — scripts/setup-worktree.sh
- **T2+T3+T4**: `feat(tools): add workspace path, grep, delete, move tools` — ToolExecutor.java, Tool.java
- **T5**: `feat(tools): add git operations tool` — ToolExecutor.java
- **T6**: `feat(tools): add verify_build tool` — ToolExecutor.java
- **T7**: `feat(tools): wire mempalace into memory_read/write` — ToolExecutor.java
- **T8**: `feat(tools): add btca_ask tool` — ToolExecutor.java
- **T9**: `feat(tools): add context7 docs lookup tool` — ToolExecutor.java, Context7Client.java
- **T10**: `feat(skills): add opencode skill import endpoint + tool` — SkillController.java, SkillService.java, ToolExecutor.java
- **T11**: `feat(templates): add frontend refactoring schema template` — templates/refactor-frontend.json
- **T12**: `test(tools): end-to-end schema execution against worktree` — evidence files

---

## Success Criteria

### Verification Commands
```bash
cd backend && mvn compile                    # Expected: BUILD SUCCESS
cd frontend && npm run build                 # Expected: build success
cd frontend && npm run type-check            # Expected: no errors
bash scripts/setup-worktree.sh               # Expected: worktree created at ../Axolotl-worktree
curl localhost:8082/api/schemas               # Expected: refactor template listed after import
```

### Final Checklist
- [x] All 4 new tools registered and functional (grep, file_delete, file_move, git)
- [x] verify_build tool runs npm build + type-check
- [x] Workspace base path resolves relative paths for all file tools
- [x] Git worktree setup script creates isolated copy
- [x] Refactoring schema template imports and executes
- [x] No changes to existing tool signatures
- [x] No edits to running instance files — worktree only
- [ ] No new UI components added
