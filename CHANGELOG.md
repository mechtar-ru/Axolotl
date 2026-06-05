# Changelog

All notable changes to Axolotl are documented in this file.

Format: [Keep a Changelog](https://keepachangelog.com/)  
Versioning: SemVer via git tags.

## [Unreleased]

### Added

#### App Creation Workflow (4-Phase Pipeline)
- **PlanStep Neo4j model**: `GraphPlanStep` `@Node("PlanStep")` with bidirectional `DEPENDS_ON` relationships. `PlanStepStatus` enum (PENDING, IN_PROGRESS, DONE, REJECTED, INCOMPLETE). `PlanStep` POCO with `stepId`, `title`, `description`, `status`, `dependsOn` (list of step IDs), `schemaId`. `Neo4jPlanStepRepository` with Cypher queries for schema-level CRUD, status queries, and ready-step detection (dependencies all DONE). (`<current>`)
- **Plan API**: `PlanStepService` with `createSteps()`, `updateStatus()` (validates dependencies before DONE), `getReadySteps()` (steps whose prerequisites are satisfied), `getDependencyGraph()` (nodes + edges for UI rendering), `syncToDisk()` (writes `plan/steps/*.md` and `plan/implementation_plan.md` to targetPath). `PlanStepController` at `/api/plan-steps/{schemaId}` with 6 REST endpoints (`<current>`).
- **Plan MCP tools**: 5 new MCP tools: `read_plan_steps`, `add_plan_steps`, `update_plan_step_status`, `get_ready_steps`, `get_plan_graph`. Registered in `PlanMcpServer` alongside existing PlanTools. Agent nodes can read/update plan steps directly via MCP. (`<current>`)
- **Pipeline templates**: `PipelineService.createAppPipeline()` generates 9-stage pipeline covering all 4 phases (Design → Planning → Implementation → Documentation). `createMinimalPipeline()` for 3-stage quick runs. Template selected via `pipeline.template` config key. (`<current>`)
- **Planner agent type**: `buildPlannerPrompt()` generates structured plan decomposition system prompt. Outputs parseable `step: N / title: / description: / depends_on: []` format. Creates `PlanStep` nodes in Neo4j via MCP. New `PlannerBlock.vue` with config panels for model + system prompt. (`<current>`)
- **Prep agent type**: `buildPrepPrompt()` generates pseudocode + test stubs. Writes `plan/pseudo-frontend.md`, `plan/pseudo-backend.md`, and test stubs via `file_write`. New `PrepBlock.vue` with config panels. (`<current>`)
- **Doc-Agent mode**: Agent node with `agentType="doc-agent"`. `buildDocAgentPrompt()` instructs to append to `spec.md`, append to `changelog.md`, create `design/feature-*.md` for new features, update README only on core purpose change. Supports `file_read`/`file_write`/`directory_read`. New `DocAgentBlock.vue` icon in palette. (`<current>`)
- **Agent type dispatch in NodeRouter**: `NodeRouter.executeNode()` reads `agentType` from node config (`code-agent`/`planner`/`prep`/`doc-agent`), dispatches to `executeToolAgentNode()` which selects default tools and system prompts per agent type. (`<current>`)
- **Plan step context injection**: `AgentNodeStrategy.buildPlanStepContext()` injects current plan status (total steps, ready steps, per-step status + dependencies) into agent system prompt. Agents see which steps are pending/done/ready without needing separate MCP calls. (`<current>`)
- **Verifier coverage checks**: `VerifierNodeStrategy` reads `design/` docs and `plan/steps/*.md` when `coverageDesign`/`coveragePlan` toggles enabled. Passes doc context to LLM for coverage verification. Results stored in structured verdict JSON under `coverage` key. (`<current>`)
- **DiffViewer component**: Shared `DiffViewer.vue` with LCS-based diff algorithm, side-by-side line numbers (+/- prefix), color-coded added/removed lines, stats bar. Reusable in ReviewApprovalDialog and DiffReviewDialog. (`<current>`)
- **Review dialog redesign**: `ReviewApprovalDialog` now has expandable diff section (collapsible, compares previous design/plan vs current), questions section (findings with `type="question"` rendered as labeled text inputs with answers collected), `reviewType` prop (design/plan), `previousPlan`/`previousDesign` props. (`<current>`)
- **Agent type selector in BlockConfigPanel**: Dropdown to switch agent between `code-agent`, `planner`, `prep`, `doc-agent`. Persisted to `config.agentType`. Shown for all agent-like blocks (agent, planner, prep, doc-agent). (`<current>`)

- **Project grouping overhaul**: Schemas can be assigned to named groups via `projectGroup` field. DashboardView groups schemas by project, sorted A-Z or by recency. `lastRunAt` field tracks last pipeline execution time. Test schemas auto-detected by naming patterns (`test-`, `pw-review`, `debug-`, `check-`, `premortem-`, `pipeline-`, `final-`, `valid-` prefixes). 3 new endpoints: `POST /schemas/batch-delete`, `GET /schemas/test-schemas`, `GET /schemas/recent` (`3ea365e2`).
- **Drag-n-drop between groups**: AppCards in group sections are draggable. Dropping a card on a different group moves it there instantly via `PUT /api/schemas/{id}` with updated `projectGroup`. Groups are drop zones with `@dragover.prevent` / `@drop` (`869b5fe4`).
- **Dashboard filter & sort controls**: Sort by Last Updated, Name A-Z, Last Run. Type filter dropdown (All / Chat / Custom / Game). Apps/tests toggle hides test schemas. Batch delete confirmation uses `AppModal` instead of `window.confirm` (`3ea365e2`, `869b5fe4`).
- **Recent section**: Shows top 5 schemas sorted by lastRunAt/updatedAt across all schemas (not just ungrouped). Expanded by default (`3ea365e2`).
- **Flutter/Dart project support**: Added `.dart` to default source extensions in `NodeSourceHandler.readProjectContext()`, enabling Flutter projects to be loaded by source nodes without explicit extension configuration.
- **Pipeline service decomposition**: Extracted `PipelineBuilder`, `PipelineStatusManager`, `DiffService` from `PipelineService`. PipelineBuilder owns stage creation from nodes/edges. PipelineStatusManager owns 4 in-memory pipeline state maps. DiffService provides `computeSimpleDiff()` and `computeDiffPayloads()`.
- **Execution utility decomposition**: Extracted `ToolCallParser` (4-layer tool call parsing), `NodeCommandExecutor` (bash/grep), `NodeSourceHandler` (text/file/URL/project source resolution), `NodeFileWriter` (sandbox-aware file writes) from `ExecutionUtilityService`.
- **Execution state reconciler**: `@PostConstruct` + `@Scheduled(fixedRate=300000)` marks orphaned "running" `ExecutionRun` nodes as `RECONCILED_FAILED` with cascading `NodeExecution` cleanup.
- **Error categories**: `ErrorCategory` enum with `fromException()`/`fromToolResult()` mappers and overloaded `sendError()`/`sendLog()` in `ExecutionWebSocketHandler` for structured error classification.
- **Per-node execution timeout**: Retry loop in `NodeRouter` wrapped in `CompletableFuture.supplyAsync().get(timeoutSecs, SECONDS)` reading `NodeData.timeoutSeconds` config (default 60s).

### Fixed

- **Plan syncToDisk path traversal**: Removed user-supplied `targetPath` parameter from `POST /sync-to-disk`. Now resolves targetPath from schema's stored value via `Neo4jSchemaRepository.findById()`, preventing arbitrary file writes outside the project directory (`<current>`).
- **AgentNodeStrategy fully-qualified class names**: `buildPlanStepContext()` used verbose `com.agent.orchestrator.model.PlanStep` instead of the shorter imported `PlanStep`. Fixed imports and replaced all fully-qualified references (`<current>`).

- **ProjectGroupDialog not showing**: Missing `:model-value="true"` on `AppModal` — modal was mounted (`v-if="groupDialogSchema"`) but hidden (`v-if="modelValue"` was undefined). Also forwarded `@update:model-value` as `@close` for ✕ button dismissal (`20f4f979`).
- **AppCard click swallowed by action buttons**: Vue 3 processes all VNode handlers synchronously, so `@click.stop` on children didn't prevent parent `@click` navigation. Added `target.closest('.card-actions')` guard in `handleClick()` (`20f4f979`).
- **AppCard action buttons replaced with `<button>`**: Previously `<div>` elements with `@click` handlers — now native `<button>` elements with `pointer-events: auto` on SVGs (`20f4f979`).
- **Changing default model logs out**: `JwtAuthFilter.shouldNotFilter()` skipped `/api/settings/**` entirely, so all settings endpoints ran as anonymous even with valid JWTs. `PUT /api/settings/user/default-model` always returned 401 → frontend logout. Fixed by removing `/api/settings` from `shouldNotFilter()` — the filter now parses the JWT for settings endpoints, allowing user-specific saves. Without a valid JWT, the controller returns 200 without saving, never triggering a logout (`141be284`).
- **Undefined CSS var `--text-tertiary`**: Changed to `--text-muted` in AppCard.vue (`869b5fe4`).
- **DashboardView groups hidden on first load**: `groupExpanded[group]` was `undefined` (falsy in `v-show`). Changed to `groupExpanded[group] !== false` so groups are expanded by default before the watch fires (`20f4f979`).
- **Directory picker reads no file contents**: Replaced `<input type="file" webkitdirectory>` with `window.showDirectoryPicker()` in all 4 folder-picker buttons (ProjectsFolderPrompt, SettingsView, SchemaPropertiesPanel, BlockConfigPanel). Opens the same native OS dialog but returns only a directory name handle — no file contents are read (`0100d07d`).
- **ToolCall parsing in OpenAI-compatible providers**: `OpenAiChatClient.parseResponse()` now handles `content=null` (when `tool_calls` are present in the API response) and extracts structured `tool_calls` JSON into the response text for downstream `ToolCallParser` processing. Previously crashed with NPE on null content.
- **Output node file path resolution**: `ExecutionUtilityService.executeOutputNode()` resolves relative `filePath` against the schema's `targetPath`, so output files (e.g., `review.md`) are written inside the project directory instead of the backend's working directory.
- **Output node null parent directory**: `NodeFileWriter.writeOutput()` checks `path.getParent()` for null before calling `Files.createDirectories()`, fixing NPE when the output path is a bare filename without a parent directory.
- **`build_app` dependency checks for APK-only builds**: `ToolExecutor.handleBuildApp()` skips Xcode and CocoaPods checks for non-iOS build modes (debug/APK). Xcode and CocoaPods are only required for iOS builds; APK builds now proceed without them.
- **`build_app` Android SDK detection**: `checkAndroidSdk()` reports missing `ANDROID_HOME` as a warning instead of a blocker when the SDK is found via common path scan or `flutter config --machine`. Flutter can find the SDK through its own config, so ANDROID_HOME is not required for `flutter build apk`.
- **`build_app` checkXcode/checkCocoaPods null safety**: Both methods now accept `null` for the `missing` list (warn-only mode), preventing NPE when called from APK-only build paths.

### Changed

- **AppCard group button hover**: `.group-btn:hover` now highlights with `var(--accent)` (was no visible hover state) (`869b5fe4`).
- **allAppsCollapsed default**: Changed from `true` to `false` — Recent section expanded on first load (`869b5fe4`).
- **Test schemas button**: Hidden when `testSchemas.length === 0` (was always visible showing "0 tests") (`869b5fe4`).

### Removed

- **Dead CSS in AppCard.vue**: Removed `.app-card:hover .delete-btn { display: inline-flex }` — delete button is always visible, rule had no effect (`869b5fe4`).
- **Dead code**: Removed `transientOnly` skip-logic from `shouldSkipNode()` and callers — feature never used, added only complexity.

## [v0.4.0] - 2026-05-28

### Added

#### Pipeline & Execution
- **Draft pipeline**: Multi-phase pipeline with draft-gate review dialog (collapsible artifact cards), artifact resolution, and full test coverage (`515e9d82`, `2034726e`, `e13e119e`).
- **LLM thoughts & reasoning**: `LlmResponse` record, provider reasoning extraction from API responses, structured capture via `ReasoningCapture`, and persistence to `NodeExecution.reasoning` (`8cf4f356`).
- **Run history timeline**: Full TimelineView rewrite from live-only WebSocket to persistent `ExecutionRun` history. Run cards with status dots, mode tags, relative time, duration, tokens. Expandable node lists with 200-char preview + "Show more" modal, pretty-printed JSON, Copy button. Live Events bar, stale run release, delete with 3s confirmation. Backend `POST /cleanup-runs`, `DELETE /runs/{runId}` endpoints (`b26e54bf`).
- **Diff review**: `file_write` on existing files creates `.bak` backup before overwrite. Pipeline pauses at `AWAITING_DIFF_APPROVAL`. `DiffReviewDialog.vue` shows unified diff per file with Accept/Reject buttons. `POST /approve-diffs` removes `.bak`, `POST /reject-diffs` restores originals. Toggle in stage config (`b26e54bf`).
- **Missing dependencies install**: `build_app` tool sends `deps_needed` WebSocket event with missing-tool list. `DepsInstallDialog.vue` displays per-dependency install status (⟳ → ✅/❌). `POST /api/execution/{id}/install-deps` triggers sequential brew installation. User dismisses or retries pipeline after install (`b26e54bf`).
- **Parallel stage execution**: Stages within a topological-sort level now run concurrently via `CompletableFuture.runAsync` + `allOf().join()` (was sequential per level) (`d2fe4977`).

#### Production Hardening (WS1)
- **Schema validation**: `SchemaValidator` validates name/nodes/edges/pipeline stages at `GET /api/schemas/{id}/validate`, returns structured `ValidationResult` with error vs warning. Execution blocked until errors resolved (`5359c1f5`).
- **Concurrent execution guard**: `executeSchema` throws HTTP 409, `executePipeline` throws `RuntimeException` — prevents silent concurrent execution (`34aef03f`).
- **Input validation**: Non-null + non-blank enforced on schema name, import, delete, and export endpoints (`c9d2da2f`).
- **Edge case audit**: Resume without paused run returns WS error. Empty stage list, null stage configs handled gracefully (`a86c4d7b`).

#### Infrastructure (WS2)
- **Neo4j TTL cleanup**: `ExecutionLogCleanupService` removes `ExecutionRun` and `NodeExecution` nodes older than 30 days, runs daily via `@Scheduled` (`d504c28a`).

#### Quality Gates (WS3)
- **Stub detection toggle**: `VerifierNodeStrategy` detects `// TODO`, empty bodies, `return null`, `throw UnimplementedError`. Toggle in BlockConfigPanel (`9748341e`).
- **Post-write syntax validation**: `ToolExecutor.handleFileWriteWithSandbox` runs `dart analyze` / `python3 -m py_compile` / `javac` after `file_write` with 15s timeout. Errors returned in tool output for LLM self-correction (`af7f9c5b`).

#### Observability (WS4)
- **Token tracking**: `LlmUsage` threaded through all 7 providers (OpenAI, Anthropic, DeepSeek, Ollama, Zen, Custom, RLM). Accumulated via `ExecutionStateManager.recordTokenUsage()`, persisted to `NodeExecution.tokensUsed`. Displayed in TimelineEntry (`fa2abf26`).
- **Tool call history**: `estimateToolCalls()` counts tool invocations in agent result text, persisted to `NodeExecution.toolCalls`. WS tool call events captured with duration + success/fail (`fa2abf26`).
- **MDC tracing**: `TraceIdFilter` injects 8-char hex `traceId` per HTTP request into SLF4J MDC. Log pattern includes `[%X{traceId}]` (`94724a87`).

#### Schema & Blueprint
- **Schema import/export**: `GET /api/schemas/{id}/export` returns full schema JSON (excluding IDs/timestamps). `POST /api/schemas/import` creates new schema from JSON. Export button in StudioTopBar downloads `.json`, Import button in Dashboard opens file picker and navigates to imported schema (`2be92469`).
- **Blueprint undo/redo**: `useUndoRedo` composable tracks node/edge state history; Ctrl+Z (undo), Ctrl+Shift+Z (redo) shortcuts; undo/redo buttons in BlueprintToolbar (`93864f5f`).
- **Agent personas**: Four preset system prompts — Architect (writes specs before code), Hacker (code without plan), Teacher (explains each solution), Minimalist (lean, YAGNI), TDD (tests-first). Persona selector in BlockConfigPanel replaces `block.systemPrompt` on switch (`56247760`).
- **Non-Flutter targets**: `ProjectType` enum with FLUTTER, PYTHON, WEB (Vite/React), GO, RUST. SchemaPropertiesPanel dropdown. Per-language build (`flutter build apk --debug` / `python3 -m py_compile` / `npm run build` / `go build` / `cargo build`) and validate (`dart analyze` / `py_compile` / `tsc --noEmit` / `go vet` / `cargo check`) commands (`30ea30ce`).
- **Blueprint node tools**: Tools sync from stage config to blueprint node dynamically instead of hardcoded defaults (`f8e3b99a`).

#### LangChain4j Migration
- **All 7 LLM providers migrated** from Spring AI to LangChain4j `ChatLanguageModel`: OpenAI, Anthropic, DeepSeek, Ollama, Zen API, Custom endpoints, RLM. Unified tool call handling via `LangChainToolAdapter`. Removed all Spring AI dependencies (`012adf85`).

#### UI/UX
- **BaseButton.vue**: Shared button component with `variant="primary|secondary|danger|ghost"` prop, loading spinner, disabled state. CSS button tokens (`--btn-padding`, `--btn-font-size`, `--btn-font-weight`, `--btn-radius`) in `tokens.css` (`2edae7bd`).
- **CSS design token system**: Global `tokens.css` with backgrounds (`--bg-canvas`, `--bg-surface`), accent colors (`--accent`, `--accent-secondary`), typography (`--font-mono`), spacing (`--space-1` through `--space-8`), type scale (`--text-xs` through `--text-3xl`), radii, z-index scale. Migrated across 20+ components (`2edae7bd`).
- **WebSocket resilience**: Exponential backoff reconnection 1s→16s, heartbeat ping at 30s / pong timeout 8s, `onReconnect` callback, `onDisconnect` callback resets execution state (`5359c1f5`, `a5998e18`).
- **Projects folder prompt**: First-login dialog when `projectsFolder` setting is empty. Editable in SettingsView. Clickable folder icon in SchemaPropertiesPanel opens native `webkitdirectory` picker (`0634e3ba`).
- **SettingsView decomposition**: Extracted `ProviderCard.vue` (684 lines) and `CustomEndpointList.vue` (408 lines). Removed 571 lines of dead code. SettingsView reduced from 1400→498 lines (`eef22e8c`).
- **UI audit fixes**: `toast.error()` wired to 16+ error paths. Empty-state CTAs on Dashboard, Timeline, PipelinePanel. Focus trap in AppModal. `:focus-visible` global styles. `.mono` utility class. Hardcoded z-index → `var(--z-*)` everywhere (`2edae7bd`).

#### Developer Experience
- **Harness directory**: OpenCode integration harness with Playwright MCP server (JSON-RPC 2.0 stdio protocol), tool specs, sub-agent/skill/memory/middleware configs, component manifest, READMEs for all 4 subsystems (`ee9765d9`).
- **Roadmap docs**: `ROADMAP.md` with 5 workstreams, effort estimates, risk assessment, premortem mitigations. Harness scripts for testing (`a9ce2ca2`).
- **CI workflows**: Neo4j service container for backend tests, `actions/cache` for npm with `save-always: true`, Node 22, `v5` checkout/setup-node. Feature branch triggers. Proper Docker tags with semver+sha (`a772ae12`).
- **C4 architecture diagrams**: 6 Mermaid C4 diagrams (Context, Container, Frontend/Backend Components, Dynamic Execution, Deployment). VitePress documentation site populated (`b79548c6`).

#### Other
- **TDD cross-stage input mappings**: TDD stages wire `inputMapping` so upstream outputs flow downstream — verify-test receives test output, impl receives test output to satisfy, verify receives impl output. System prompts reference upstream results (`b5cdde65`).
- **PermitAll mcp endpoint**: `POST /api/schemas/{id}/execute` explicitly permitAll in SecurityConfig for pipeline mode without auth token (`c1425dc0`).

### Changed

- **LangChain4j migration**: All 7 LLM providers use `ChatLanguageModel` instead of Spring AI's `ChatClient`. Removed `spring-ai-starter-*` dependencies from `pom.xml`. Providers configuration moved from properties to programmatic builder (`012adf85`).
- **WebSocket reconnection**: From no reconnection to exponential backoff (1s → 2s → 4s → 8s → 16s) with heartbeat. `onDisconnect` callback resets `isRunning` on unexpected close. `onReconnect` fires after each successful reconnect (`5359c1f5`).
- **SchemaService.updateSchema**: From full ObjectMapper replacement to non-null field merge — preserves fields not in the request body, preventing data loss on single-field PUT (`2f59b0c0`).
- **SettingsView layout**: Fields respaced with consistent margins and label positions. Static provider description replaces dynamic scrolling list. Initial API key population via eye icon toggle (`f8e3b99a`).
- **TimelineView**: From live-only WebSocket feed to persistent Neo4j-backed history with run/delete/stale-release controls (`b26e54bf`).
- **Backend port**: 8082 remains, all pipeline endpoints consolidated under `/api/execution/` prefix (`f8e3b99a`).
- **PromptEditorModal**: Templates panel now collapsible, emoji icons replaced with inline SVGs (`2edae7bd`).
- **Logback configuration**: `org.springframework.data.neo4j.cypher.unrecognized` suppressed to ERROR level, "no active WS session" messages set to DEBUG (`f23f9351`).
- **Neo4j versioning**: `@Version` field added to `GraphExecutionRun` and `GraphExecutionRecord` for optimistic locking (`f23f9351`).
- **`resolveStageModel`**: System-default model constants ("deepseek-v4-flash"/"deepseek-v4-flash-free") treated as transparent when schema has an explicit `defaultModel` (`f8e3b99a`).
- **`ExecutionStateManager` cleanup**: `removeSchema()` clears all 7 maps on pipeline completion to prevent unbounded memory growth (`d2fe4977`).
- **ToolExecutor bash sandbox**: Blocks command substitution (`$(`, `` ` ``) and validates each pipe segment against allowed commands. `validateSandboxPath` uses `Path.normalize()` for canonical path comparison (`d2fe4977`).

### Fixed

#### Pipeline & Execution
- **Pipeline not pausing on AWAITING_APPROVAL**: Wave loop broke and returned early after setting pause (`ce96a7f0`). Post-loop completion code no longer overwrites paused status (`b6eca96a`).
- **Double AWAITING_APPROVAL on resume**: Schema nodes carried stale approval status from parent run — resume resets node statuses to PENDING and sets approval flag (`b6eca96a`).
- **Atomic pause state**: `updateRunPaused` combines `status=paused` + `resumeIndex` in one atomic Cypher query (`1df048ec`).
- **Pipeline failure propagation**: Failed stages counted as completed and pipeline status set to "completed" — `pipelineFailed` flag breaks all loops, calls `updateRunCompleted("failed")`, sends `pipeline_failed` WS event (`b6eca96a`).
- **Stale approval flag leak**: `clearStaleApprovals` resets node approval before each execution, preventing leaked approval from prior runs (`f8e3b99a`).
- **Pipeline resume crash**: `resumePipeline` wrapped in try-catch; `releasePausedRun()` prevents runs stuck in `'resuming'` status (`f23f9351`).
- **Retry respects paused stages**: Tracks stages paused before failure, sets `skipApprovalCheck=false` for them (`f23f9351`).
- **Pipeline stage timeout**: 5-minute per-stage timeout via `CompletableFuture.orTimeout` (`cbe2e55c`). Increased to 20 minutes for code generation scenarios (`f8e3b99a`).
- **Config mutation side-effect**: Stage config cloned via `new HashMap<>(existing)` before `resolveInputMappings` (`cbe2e55c`).
- **ResumeIndex persistence**: Added to `GraphExecutionRun` Neo4j field, fallback when in-memory state lost on restart (`cbe2e55c`).
- **TOCTOU in handleReviewApprove**: Atomic `claimPausedRun` Cypher (`WHERE status='paused' SET status='resuming' RETURN`) prevents double-claim (`0bfaf10b`).
- **ResumeExecution argument overload**: Added 3-param overload without claim phase for already-claimed runs (`0bfaf10b`).
- **claimPausedRun order**: `ORDER BY r.startedAt DESC LIMIT 1` — picks latest paused run, not first (`f8e3b99a`).
- **NodeRouter preserves AWAITING_APPROVAL**: No longer unconditionally overwrites node status to COMPLETED (`f4adc059`).
- **Hardcoded model fallback**: Removed from `createDefaultPipeline` — empty string fails with clear log instead of silently routing to arbitrary model (`f23f9351`).
- **Ollama request timeout**: Increased from 120s to 3600s in `application.yml` to support multi-iteration code generation (`f8e3b99a`).
- **ToolExecutor relative path resolution**: `handleFileReadWithSandbox` prepends `schemaTargetPath` for agent-generated relative paths (`f8e3b99a`).
- **ResumePipeline revert status**: Updated by runId directly without status precondition (`151198b9`).

#### Review Node
- **Empty plan in ReviewApprovalDialog**: Three root causes fixed: (1) `rewrittenPlan=""` passed `!= null` check — added `isBlank()` fallback chain `rewrittenPlan → planText → "No content available for review planning."`. (2) LLM returned empty content due to garbage `inputContent` ("Файл не найден..."). (3) Zen API 429 rate limit errors silently dropped by SSE parser — added retry logic with exponential backoff (2s/4s/8s/16s) and plain JSON error fallback (`cd1bbe95`, `bd37f996`, `69daa78d`).
- **Review node always requires approval**: Manual mode now transitions to `AWAITING_APPROVAL` on both PASS and REWRITE verdicts (previously only on REWRITE) (`1384f4db`).
- **Findings serialization**: Review findings now serialized as JSON array instead of object with numeric keys (`1384f4db`).

#### Schema & Data
- **SchemaService.updateSchema data loss**: Full ObjectMapper replacement on partial PUT wiped name/targetPath/pipeline — fixed to merge non-null fields from incoming request into existing schema (`2f59b0c0`).
- **GraphExecutionRun JSON storage**: `stageStatus` and `stageOutputs` as JSON strings in Neo4j (SDN 6 cannot store `Map` as node property) (`c1425dc0`).
- **Neo4j @Version warnings**: Added `@Version` field to `GraphExecutionRun` and `GraphExecutionRecord` (`f23f9351`).
- **Execution run query**: `getLatestRunBySchemaAndStatus` uses `ORDER BY startedAt DESC LIMIT 1` instead of grabbing first match (`f8e3b99a`).
- **Schema validation on import**: Name uniqueness check, JSON deserialization error handling (`a86c4d7b`).

#### Frontend
- **VueFlow crashes**: `node.dimensions` undefined → added `dimensions: { width: 200, height: 100 }` to all node creation sites (`a99100c2`). Edge rendering broken by direct `edges.value = ...` → replaced with `setNodes()`/`setEdges()`, added `v-if="flowReady"` guard (`de223a28`).
- **BlockConfigPanel stale data**: 40+ refs not reset on block switch — `resetRefs()` on block not found (`5edbab06`).
- **BlueprintView infinite loop**: Deep watch + store mutation — `syncing` flag prevents re-entrant store mutation (`5edbab06`).
- **PipelinePanel silent failures**: fire-and-forget execute/cancel with no error display — `await` + error display, `stageLevels` converted to `computed`, cleanup timeout in `onUnmounted` (`5edbab06`).
- **WebSocket disconnect frozen state**: `onDisconnect` callback resets execution flags on unexpected close (`a5998e18`).
- **Review dialog reject irreversible**: Added `window.confirm()` before reject (`a5998e18`).
- **ResumeBanner stale paused run**: Re-verify paused execution on each click (`a5998e18`).
- **BlockConfigPanel provider list**: `loadProviders()` called on every `blockId` change, not just mount — new providers from Settings appear immediately (`81773370`).
- **GenericAppUI redundant API calls**: `fetchGeneratedFiles` cached by `schemaId` (`81773370`).
- **DesignWorkspaceUI timer leak**: `clearSaveContextTimer` in `onUnmounted` (`81773370`).
- **DashboardView race condition**: `getSchema` retried 3 times with 200/400/600ms backoff after `applyTemplate` (`5790877a`).
- **SettingsView dead code**: `toggleShowKey` referencing deleted reactive objects — removed (`0894890b`).
- **CustomEndpointList split state**: Rewritten to be self-contained with internal API calls (`eef22e8c`).
- **LiveView missing executionResult**: `executionResult` prop never passed to ChatAppUI — agent messages never rendered in live panel (`1384f4db`).
- **File upload content delivery**: Frontend only saved filename — now reads content via `FileReader` and sends as `sourceData` (`af7f9c5b`).
- **SchemaPropertiesPanel debounce**: 400ms debounce on name/description inputs (`5edbab06`).
- **BlockPalette JSON.stringify**: Wrapped in try/catch (`03630d0e`).
- **ChatAppUI executionResult**: Prop wired into chat bubbles (`03630d0e`).
- **AppDashboardView rename**: Syncs with schemaStore (`03630d0e`).
- **QuickStartDialog conflict**: Target path conflict check before app creation (`03630d0e`).
- **CI TypeScript errors**: `targets[i]`, `results[i]` in template expressions → non-null assertions (`!`). Various strict-null fixes across CustomEdge, BlockConfigPanel, SettingsView, QuickStartDialog, DashboardView, TimelineView (`e61fade6`, `bfadfea1`, `38f58594`).

#### Security
- **DebugShutdownListener thread dump**: Downgraded from `log.warn` to `log.info` with inner dump at `log.debug` (`0894890b`).
- **Bash injection**: Shell metacharacter check blocks command substitution (`$(`, `` ` ``), validates pipe segments against allowed commands (`d2fe4977`).
- **Path traversal**: `validateSandboxPath` uses `Path.normalize().toAbsolutePath()` instead of `startsWith` string matching (`d2fe4977`).
- **JWT auth hardening**: `shouldNotFilter()` lists permitAll paths; removed paths still accessible via `permitAll()` but authenticated requests populate SecurityContext (`4476002e`, `672cfe66`).
- **Trailing slash 403**: `TrailingSlashFilter` normalizes URIs before Spring Security processing (`fc67cd34`).

#### CI & Build
- **docs.yml**: `npm ci` → `npm install` (no lockfile). Removed cache config (`72b74ae6`, `51866bb9`).
- **ci.yml**: Neo4j 5-enterprise service container with health check. Npm cache with `save-always: true`. Feature branch triggers. Removed `continue-on-error: true` (was hiding failures). Backend test mock stubbing fixed for nullable parameters (`3683d3e0`, `edb79c16`).
- **release.yml**: `docker/metadata-action` split into backend/frontend with semver+sha tags (`a772ae12`).

#### Other
- **ReviewBlock VueFlow fragment**: Two root elements broke VueFlow node position — wrapped in single `div` (`4a90e9cf`).
- **TDD checkbox persists**: Initialized from `store.currentSchema?.pipeline?.tddEnabled` instead of hardcoded `false` (`f23f9351`).
- **Auth redirect loop**: `api.ts` only clears auth on 401, not 403 (expected for anonymous users hitting non-permitAll) (`4476002e`).
- **BlockConfigPanel model clearing**: Review/verify model persists through `config` map (`3efcb5e7`).

### Removed

- **LiveView.vue**: Unused execution overlay. Removed from StudioView, StudioTopBar, SchemaPropertiesPanel (`4cbfa816`).
- **Spring AI dependencies**: `spring-ai-starter-model-ollama`, `spring-ai-starter-model-openai`, Spring AI BOM (`012adf85`).
- **SettingsView dead code**: 571 lines removed — `toggleShowKey`, `useAutoSave`, stale refs, duplicate CSS (`eef22e8c`).
- **`useAutoSave` composable**: Replaced by schemaStore dirty-flag auto-save (`eef22e8c`).
- **Hardcoded model lists**: Providers now fetch dynamically from API (`012adf85`).
- **Old StatusBar.vue**: CSS-only tag badges replace status bar (`2edae7bd`).

### Security

- **Bash injection prevention**: `handleBash` blocks command substitution (`$(`, `` ` ``) and validates pipe segments against allowed commands set (`d2fe4977`).
- **Path traversal prevention**: `validateSandboxPath` uses `Path.normalize().toAbsolutePath()` canonical comparison instead of string `startsWith` (`d2fe4977`).
- **ExecutionStateManager memory leak**: `removeSchema()` clears all maps on pipeline completion — prevents OOM over many runs (`d2fe4977`).

## [v0.3.0] - 2026-05-20

### Added

- **Pipeline system**: Multi-stage pipelines with stage-level execution abstraction. Stages run in topological order, parallel within levels. Build/execute/cancel/retry controls in Pipeline Panel (`d1beaec3`, `f3b4ae77`).
- **Cross-stage artifact passing**: `Stage.inputMapping` with dot-notation field extraction from upstream stage outputs. Persisted to Neo4j, restored on retry/resume (`82e0165a`).
- **Pipeline retry from failure**: Creates child `ExecutionRun` with `resumesFrom`, resets failed+dependent stages to pending, re-executes only those (`f3b4ae77`).
- **Pipeline pause on review**: Pipeline pauses at review nodes with `AWAITING_APPROVAL` status, resumes on human approval. Topological sort fixes for cycle-safe resume (`7d6a9815`, `6df87a15`, `a544d76c`).
- **Quick Start**: Dashboard header button opens `QuickStartDialog`. Fixed pipeline template (Receive → Review → Agent → Verify → Output). Presets describe app only, no pipeline instructions. Quick Start input maps to Receive node, app description passed to Verify node. Removes LLM-driven schema generation (`0e759304`, `96f21730`, `bcd94abc`, `d25e1258`).
- **Dynamic model lists**: All network providers (Anthropic, DeepSeek, OpenAI, Zen) fetch models dynamically from `GET /v1/models` at startup via `@EventListener(ApplicationReadyEvent)`. Models persist to Neo4j, cached with 5-minute TTL (`47b4ea40`, `0fefacec`).
- **Model enable/disable toggles**: Per-provider model checkboxes in Settings with collapsible groups by family, search filter, batch All/None buttons. All model dropdowns filter out disabled models (`47b4ea40`).
- **Verify node**: Tool-enabled LLM agent that runs checks against generated code. Structured JSON verdict with PASS/FAIL, optional auto-rewrite loop up to `maxRewriteRetries` (default 3). Tools: `file_read`, `bash`, `grep` (`be7da549`).
- **Review node**: Two-phase plan generation + analysis. Three checks (premortem/prism/postmortem) in single LLM call. Three iteration modes: Manual/Hybrid/Auto. `ReviewApprovalDialog` with Accept/Edit/Suggest & Regenerate/Reject (`be7da549`).
- **Mobile app templates**: Android (6 boilerplate files inc. Kotlin) and iOS (3 boilerplate files inc. SwiftUI) scaffolds. Backend copies scaffold files to `targetPath` at schema creation (`295a6feb`).
- **Schema Properties Panel**: Editable schema name, description, target path, default model dropdown with dynamic provider groups (`e4348107`).
- **Dirty-flag auto-save**: Blueprint edits auto-save after 2s debounce. Flush before run, route change, and deactivation (`dd6306b3`).
- **Receive block source types**: Input type dropdown controls sourceType (text/file/url/project) with conditional input sections. Backend file reader resolves relative paths against targetPath with 1MB limit (`e4348107`).
- **Execution result persistence**: Node outputs saved to Neo4j as `outputSummary` on completion. Status (FAILED/COMPLETED/AWAITING_APPROVAL) persisted. Restored on StudioView activation for continuity (`0bf696a2`, `a72d3d97`).
- **Scheduled execution log cleanup**: `@Scheduled(cron = "0 0 3 * * *")` deletes Neo4j execution records older than 14 days via `ExecutionLogCleanupService` (`5dfdd47b`).
- **Settings UX**: API key encryption at rest, masked in list responses. Test-before-save passes unsaved apiKey/baseUrl as query params. Collapsible custom endpoints with delete confirmation. Model search filter with grouped checkboxes (`cfdf869c`, `b213a05a`, `47b4ea40`).
- **CSS design token system**: Constrained spacing/type scale, consolidated tokens.css, token migration across all 20+ frontend components. Refactoring UI principles applied (`4b88e5e7`, `e847d547`).
- **Schema builder node**: AI-powered prompt-to-schema generation as a node type in the palette (`ed8795db`).
- **Transform node**: Data extraction and routing node for pipeline transformations (`cb3fe032`).
- **Tool-enabled agent nodes**: Agents can use `file_write`, `file_read`, `bash`, `grep`, `directory_read`, `web_search`, `web_fetch` tools (`33e132eb`, `80cbd09d`).
- **Neo4j code graph**: Dirac-inspired features including AST analysis, hash-anchored class references, context curation with token budget, import tier planning (`dba6e167`, `cd7a87a2`).
- **Observability**: OpenAPI/Swagger at `/swagger.html`, Prometheus metrics at `/actuator/prometheus`, structured logging (`e2a784f5`).
- **TDD pipeline expansion**: Agent→verifier stage pairs expand to 4 stages (test→verify-test→impl→verify) when `tddEnabled` is true. Backend `expandTddStages()` adds correct dependency wiring (impl depends on test only for parallel execution). Frontend TDD checkbox in PipelinePanel passes toggle to create-default-pipeline API (`928976ef`, `89c39934`).

### Changed

- **Neo4j is now the primary database**: Full migration from SQLite. Auth (BCrypt-hashed), settings, schemas, execution history all in Neo4j (`01788810`, `498e47f0`).
- **Backend port** changed from 8080 to 8082. Frontend `.env.local` defaults updated (`78b5fe65`).
- **Virtual threads**: `SchemaService` switched from fixed thread pool to `Executors.newVirtualThreadPerTaskExecutor()` (`1b7c3a1b`).
- **JWT auth filter**: `shouldNotFilter()` lists paths that skip JWT processing. Removing a path from `shouldNotFilter` while keeping `SecurityConfig.permitAll()` allows authenticated requests to populate SecurityContext without breaking anonymous access (`77ff9ad9`).
- **Frontend `<router-view>`** wrapped with `<keep-alive>` (excluding login, about). StudioView uses `onActivated`/`onDeactivated` for WebSocket lifecycle and auth token sync (`584eb241`).
- **All emoji icons replaced with inline SVGs** across SettingsView, BlockConfigPanel, PipelinePanel, ReviewApprovalDialog, SchemaPropertiesPanel (`e4348107`).
- **Logging**: Java code migrated from `System.out.println` to SLF4J `LoggerFactory.getLogger(...)`. Full stack traces added to 20+ message-only catch blocks in SchemaService, repositories, and controllers (`43d01a48`, `f6e19f75`).
- **ToolExecutor sandbox**: `handleFileWrite` validates sandbox path via `validateSandboxPath()` and auto-creates parent directories (`1b7c3a1b`, `a72d3d97`).
- **Schema creation** auto-creates `targetPath` directory via `Files.createDirectories()` (`295a6feb`).
- **Review node config fields** saved into `config` object (not top-level `baseData`) to survive Jackson deserialization (`a2548429`).
- **Neo4j deprecation warnings** suppressed via `NotificationConfig.disableCategories()` (`44d19aba`).

### Fixed

- **Auth redirect loop**: Frontend `api.ts` interceptor no longer clears auth on 403 (only 401) (`2e599e8c`).
- **Expired JWT handling**: `JwtAuthFilter` silently skips invalid tokens, protected endpoints still reject anonymous (`77ff9ad9`).
- **BlockConfigPanel model clearing**: Review/verify model no longer resets on node switch (`3efcb5e7`).
- **VueFlow `nextSibling` crash**: `v-if` → `v-show` to prevent DOM child pointer invalidation (`584eb241`).
- **VueFlow `instance.update` crash**: `markRaw` wraps component types to prevent Vue reactivity proxy from breaking `h()` (`584eb241`).
- **Main.css missing import**: Added in `main.ts` to load 103-variable CSS token system (`584eb241`).
- **Spring AI dead code**: Removed `SpringAiLlmProvider` (146 lines), `SpringAiConfig` (153 lines), unused Maven deps (`f86211c8`).
- **JSON injection**: All Cypher/JSON construction via ObjectMapper instead of string concatenation (`f6e19f75`, `1b7c3a1b`).
- **McpIntegrationTest failures**: Testcontainers Neo4j module (`neo4j:5-enterprise`) for fresh DB per run (`3443a1d0`).
- **Flaky e2e tests**: `waitForTimeout` → `waitForSelector`. Reduced workers to 1. Increased timeouts to 35s (`584eb241`).

### Removed

- **Spring AI provider**: `SpringAiLlmProvider.java`, `SpringAiConfig.java`, Spring AI BOM, Maven dependencies (`f86211c8`).
- **`POST /api/schemas/{id}/generate-nodes`**: Quick Start uses fixed pipeline template (`bcd94abc`).
- **SQLite and PostgreSQL**: All references removed. Neo4j is the only database (`01788810`).
- **`useAutoSave` composable**: Replaced by schemaStore built-in dirty-flag auto-save (`1b7c3a1b`).
- **Hardcoded model lists**: Static fallback lists removed from all 4 providers (`47b4ea40`).

## [v0.2.1] - 2026-04-26

### Added

- Docker GHCR workflow (automatic build + push on release) (`2cac1dd0`)
- Helm chart for Kubernetes (`2cac1dd0`)
- Deployment documentation (`2cac1dd0`)
- GitHub Codespaces config (`2cac1dd0`)

### Changed

- Community & Deployment setup

## [v0.2.0] - 2026-04-20

### Added

- Initial pipeline primitives and execution persistence
- Tool-enabled agent nodes with file/tool operations

### Fixed

- NodeRouter status propagation fixes; persisted run shape fixes

---

<!-- For maintainers: add one-line PR entries under [Unreleased] when your PR introduces user-visible changes. At release time, promote Unreleased into a new versioned section and close it out. -->
