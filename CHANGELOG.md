# Changelog

All notable changes to Axolotl are documented in this file.

Format: [Keep a Changelog](https://keepachangelog.com/)  
Versioning: SemVer via git tags.

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
- **TDD test-first mode design**: Schema-level `tddEnabled` toggle expands branches to 4 stages (test → verify-test → impl → verify). Test framework discovered from scaffold files (`18895431`).

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
- **Review node config fields** saved into `config` object (not top-level `baseData`) to survive Jackson deserialization. `buildVueFlowNodes` merges config with model/systemPrompt into flat config; `syncFlowToStore` reverses the split on save (`a2548429`).
- **Neo4j deprecation warnings** suppressed via `NotificationConfig.disableCategories(Set.of(NotificationCategory.DEPRECATION))` (`44d19aba`).
- **Execution API** exposes both in-memory `executionHistory` (legacy) and Neo4j-persisted runs API. Both remain active (`f3b4ae77`).

### Fixed

- **Auth redirect loop**: Frontend `api.ts` interceptor no longer clears auth on 403 (only 401). 403 from anonymous users hitting non-permitAll endpoints is expected behavior, not a global auth failure (`2e599e8c`).
- **Expired JWT handling**: `JwtAuthFilter` silently skips invalid tokens (no 401 for permitAll endpoints), while protected endpoints still reject anonymous access (`77ff9ad9`).
- **BlockConfigPanel model clearing**: Review/verify model selection no longer resets on node switch. Config fields (checks, mode, maxIterations, generatePlan) persist through the `config` map (`3efcb5e7`).
- **Review/verify checks persistence**: Full round-trip fix — checks, mode selector, and max iterations now survive navigation to dashboard and back (`a2548429`).
- **VueFlow `nextSibling` crash**: BlueprintView elements changed from `v-if` to `v-show` to prevent VueFlow DOM child pointer invalidation (`584eb241`).
- **VueFlow `instance.update` crash**: Component types wrapped with `markRaw` to prevent Vue reactivity proxy from breaking `h()` calls during node click re-renders (`584eb241`).
- **Main.css missing import**: `main.ts` was not importing `main.css` (which imports `tokens.css`), breaking the entire 103-variable CSS token system. Fixed by adding the import (`584eb241`).
- **Spring AI dead code**: Removed `SpringAiLlmProvider` (146 lines) and `SpringAiConfig` (153 lines) plus unused Maven dependencies. No functional impact (`f86211c8`).
- **JSON injection**: `Neo4jPlanRepository` `findByParentId`/`findBySchemaId` switched from string concatenation to Jackson `writeValueAsString` for Cypher parameter escaping. `ReviewNodeStrategy` all JSON construction uses ObjectMapper. `PlaywrightClient` error responses use ObjectMapper (`f6e19f75`, `1b7c3a1b`).
- **McpIntegrationTest failures**: Fixed via Testcontainers Neo4j module (`neo4j:5-enterprise`) for fresh DB per test run. `Neo4jPlanRepository` now propagates Neo4j exceptions as `RuntimeException` instead of returning null silently (`3443a1d0`).
- **Flaky e2e tests**: Replaced `waitForTimeout` with `waitForSelector`. Reduced Playwright workers to 1. Increased timeouts to 35s (`584eb241`).

### Removed

- **Spring AI provider**: `SpringAiLlmProvider.java`, `SpringAiConfig.java`, Spring AI BOM and Maven dependencies (`spring-ai-starter-model-ollama`, `spring-ai-starter-model-openai`) (`f86211c8`).
- **`POST /api/schemas/{id}/generate-nodes` endpoint**: Quick Start now uses fixed pipeline template. `SchemaService.generateSchemaFromPrompt` is a stub returning errors. Frontend `generateNodes` API call removed (`bcd94abc`, `a8b16f6f`).
- **SQLite and PostgreSQL**: All references removed from code and documentation. Neo4j is the only database (`01788810`).
- **`useAutoSave` composable**: Removed in favor of schemaStore's built-in dirty-flag auto-save with debounce. Competed with store's persistence (`1b7c3a1b`).
- **Hardcoded model lists**: All providers now fetch dynamically from API. Static fallback lists removed from all 4 providers (`47b4ea40`).
- **Deprecated emoji icons**: All remaining emoji-only icons replaced with inline SVGs (`e4348107`).

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
