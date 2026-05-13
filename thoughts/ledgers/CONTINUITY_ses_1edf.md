---
session: ses_1edf
updated: 2026-05-10T13:21:15.849Z
---

# Session Summary

## Goal
Completed a comprehensive audit-comparison of the Axolotl README.md (product promises) against the actual frontend UI and backend code to produce a gap analysis of promised vs. delivered features.

## Constraints & Preferences
- Exact file paths and identifiers must be preserved
- Analysis must be grounded in actual code, not assumptions
- Gaps must distinguish between "not implemented at all" vs. "partially implemented but invisible to user"

## Progress

### Done
- [x] Read `/Users/evgenijtihomirov/git/Axolotl/Axolotl/README.md` in full — extracted all feature claims
- [x] Read `/Users/evgenijtihomirov/git/Axolotl/Axolotl/DESIGN.md` — verified architecture claims vs. actual code layout
- [x] Read `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/NEO4J_MIGRATION.md` — confirmed this is a specification, not evidence of completion
- [x] Examined `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/en/getting-started.md` and `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/ru/getting-started.md`
- [x] Examined all 24+ Vue component files across `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/` — mapped every UI feature claimed
- [x] Examined `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/main.ts` — verified Electron app skeleton exists but is minimal
- [x] Checked backend services: `ToolExecutor.java`, `SkillService.java`, `AgentService.java`, `GraphMemoryService.java`, `PlanService.java`, `SchemaService.java`, `GraphMcpTools.java`, `PlanTools.java`
- [x] Verified which node types actually exist vs. claimed 11
- [x] Verified export capabilities (PNG → yes, SVG → no, Python → yes via API, Mermaid → yes via API)
- [x] Checked for "Agent Types" UI (7 types claimed) — not found in AgentNode.vue (only has select for model, not agent type)
- [x] Checked for Tool Permissions UI (per-node allowed paths, blocked commands) — not found
- [x] Checked for Share Links, Webhook Callbacks, Remote API key management UI — not found
- [x] Verified PlanPanel and TemplateGallery have functional implementations but may be thin
- [x] Verified MemoryGraphView exists but is list-based, not a visual graph

### In Progress
- (none — this was a read-only audit)

### Blocked
- (none)

## Key Decisions
- **Gap classification used three tiers**: (1) Feature exists in code and UI, (2) Feature exists in backend only with no frontend UI, (3) Feature claimed but no evidence of implementation found anywhere
- **Focused on user-facing gaps**: Features that exist only in backend code (e.g., ToolPermission model) count as gaps because the user cannot interact with them

## Next Steps
1. (No next steps — the conversation was to produce the gap analysis itself)

## Critical Context

### README Claims vs. Actual Implementation: Gap Analysis

**Group A — Feature exists in both UI and backend; promise delivered:**
- Infinite canvas / VueFlow drag-and-drop, zoom, pan → ✅ Verified in `WorkflowCanvas.vue`
- 11+ Node Types → ✅ Actually 14: Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback, Subagent, Group, Comment, Transform, SchemaBuilder. README under-counts.
- Undo/Redo (Cmd+Z / Cmd+Shift+Z) → ✅ Verified `undoStack` / `redoStack` in `WorkflowCanvas.vue`
- Copy/Paste/Duplicate (Cmd+C / Cmd+V / Cmd+D) → ✅ Verified clipboard node + paste logic
- Search (Cmd+F by name or type) → ✅ Verified in `WorkflowCanvas.vue`
- JSON Import/Export → ✅ Verified in `HomeView.vue` sidebar + `exportSchema`
- Mermaid Export → ✅ Verified API call to backend
- Python Export → ✅ Verified API call to backend
- PNG Export → ✅ Verified via `html-to-image` library
- Comments + Node Grouping → ✅ Verified `CommentNode.vue` and group logic
- Execution modes (EXECUTE / ANALYZE / DRY_RUN) → ✅ Verified in `WorkflowCanvas.vue` mode selector
- Cancel execution → ✅ Verified `stopExecution` in `ExecutionPanel.vue`
- Execution History → ✅ Verified `ExecutionHistory.vue` with API integration
- WebSocket real-time progress, logs, tokens, metrics → ✅ Verified in `ExecutionPanel.vue`
- Token streaming → ✅ Verified character-by-character streaming in exec panel
- Prompt editor for Agent nodes → ✅ Verified `PromptEditorModal.vue` and expanded textarea
- Command Palette (Cmd+K) → ✅ Verified `CommandPalette.vue`
- Settings page → ✅ Verified `SettingsView.vue` with Ollama, OpenAI, Anthropic, DeepSeek providers
- Template Gallery → ✅ Verified `TemplateGallery.vue`
- Plan/Todo panel → ✅ Verified `PlanPanel.vue` with workspaces, batch add, node linking, acceptance criteria (partial)
- MCP Server (7 plan tools) → ✅ Verified `PlanTools.java` and `GraphMcpTools.java` in backend
- Skills backend → ✅ `SkillService.java` exists on backend
- Neo4j Graph Memory → ✅ `GraphMemoryService.java`, `UnifiedMemoryService.java`, graph model classes in `/graph/model/` all exist
- SchemaBuilder (AI prompt-to-schema) → ✅ `SchemaBuilderNode.vue` exists
- Guardrail, Fallback, Human, Subagent, Loop, Condition node types → ✅ All have dedicated `.vue` components

**Group B — Feature claimed but has NO frontend UI (backend code exists, user cannot interact):**
- **Tool Permissions** (per-node allowed paths, blocked commands like rm -rf) → Backend `ToolPermission.java` model exists. No UI to configure it anywhere in frontend. AgentNode.vue has no tool selector, no permission grid, no path allowlist/blocklist.
- **15 Built-in Tools** (file_read, file_write, directory_read, grep, git, bash, memory_read, memory_write, memory_search, web_search, web_fetch, web_api, graph_query, mcp_execute, rlm_predict) → Backend `ToolExecutor.java` exists. However, there is NO frontend UI to see, select, or configure which tools an agent can use. AgentNode.vue has no tools/function-calling configuration panel.
- **7 Agent Types** (Assistant, Coder, Researcher, Reviewer, Project Analyzer, Graph Engineer, MCP Agent) → No agent type selector found in `AgentNode.vue`. The component only has a model selector (`localModel`), not a type/persona selector.
- **Skill Auto-Generation** (versioning, usage count, success rate, auto-improvement) → `SkillService.java` exists. No UI for skills anywhere in the frontend — no Skills tab, no skill tracking display, no usage stats.
- **Share Links** with expiration → Backend has `ShareController.java` and `/api/share/t/**` endpoints. No UI button or dialog to create share links in the frontend.
- **Remote API** (`/api/remote/*`) + API key management → No frontend UI for generating/revoking API keys or triggering workflows remotely.
- **Webhook Callbacks** → No frontend UI for webhook configuration.

**Group C — Feature claimed but has NO evidence of implementation anywhere:**
- **SVG Export** → README says `PNG/SVG Export`. Only `toPng()` is used in `WorkflowCanvas.vue` (html-to-image library). No SVG export function exists.
- **Typed Edges** (condition true/false, loop) → All edges use the same `'custom'` edge type. `EdgeTypes` in `WorkflowCanvas.vue` only registers one edge renderer. Edge type differentiation (data vs condition vs loop) exists in schema model but not in visual rendering.
- **Trajectory Panel (fully functional)** → `ExecutionPanel.vue` has a Trajectory tab, but it shows: "Trajectory will appear here when an agent with tools executes". It's an empty state because tools are never configured (see Group B above).
- **Convergence Monitoring / BLOCKED status** → README claims "error counter, threshold 3 → BLOCKED". Node statuses in code are: idle, running, completed, failed. No BLOCKED status visible in any node component or execution panel.
- **Graph Visualization of Memory** → README and DESIGN.md describe memory as a graph (nodes + connections). `MemoryGraphView.vue` is a hierarchical list (wings > rooms > drawers > tunnels), not a visual graph visualization.
- **Semantic Search (cosine similarity)** → `MemoryNode.vue` has a search input and filter fields (wing/room) but there's no cosine similarity or semantic search logic visible — it's keyword-based filtering.
- **Electron Desktop App features** → `electron/main.ts` exists with tray, global shortcuts, notifications, dialog imports but: no auto-update logic, no system tray icon setup, no global shortcut registration in the active code path. The claims (system tray, global shortcuts, native notifications, auto-update) are code skeletons, not working features.
- **Dangerous Command Blocking** (rm -rf, format, mkfs) → Not visible in any frontend feedback. User has no way to see what's blocked or configure the blocklist.
- **Per-Node Model Selection** → AgentNode.vue has a model selector (`localModel`), but this is only for the LLM model, not for configuring "agent type" persona or toolset per node.
- **{{variable}} interpolation** → No evidence of this template syntax being rendered or parsed in any frontend component.
- **Auto-save indicator** → Backend auto-save exists (debounced 500ms POST) but no visual "saved"/"unsaved" indicator in the header.
- **Prometheus Metrics** at `/actuator/prometheus` → `MetricsService.java` exists but Prometheus endpoint availability is unverified.

**Group D — Architecture mismatch / mental model inconsistencies:**
- Neo4j as "primary database" → DESIGN.md says Neo4j is primary, but `docs/NEO4J_MIGRATION.md` is a *migration plan/spec*, not evidence of completion. SQLite still handles auth and operational data.
- "MemPalace" branding (wings, rooms, tunnels) → The UI uses these metaphors (`MemoryGraphView.vue`) but the user experience is a list hierarchy, not a "palace" spatial metaphor.
- "Draw logic, don't write it" → The canvas is a visual flow builder, not a drawing tool. Users place and connect nodes; they don't "draw logic" in the freeform sense the phrase implies.
- `docker-compose.yml` references PostgreSQL, but `DESIGN.md` says Neo4j 5.x is the primary database. PostgreSQL is not mentioned in DESIGN.md at all — potential divergence.

**Summary of most impactful gaps (user-facing, blocks value):**
1. ❌ **No tool configuration UI** — Agents cannot be told what tools they can use. The "15 Built-in Tools" and "7 Agent Types" are invisible to the user.
2. ❌ **No agent type/persona selector** — The 7 agent types (Assistant, Coder, etc.) are not configurable.
3. ❌ **Trajectory tab is always empty** — Because tools are never configured, trajectory data never renders.
4. ❌ **No SVG export** — Only PNG works.
5. ❌ **Share Links, Remote API, Webhooks** — No frontend UI despite backend endpoints existing.
6. ❌ **Skills are backend-only** — No UI to see or manage skills.
7. ❌ **Edge types are not visually differentiated** — Condition true/false and loop edges look identical.
8. ⚠️ **Neo4j migration is in progress, not complete** — Docs admit SQLite still does auth and operational CRUD.

## File Operations

### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/README.md`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/DESIGN.md`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/NEO4J_MIGRATION.md`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/en/getting-started.md`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docs/ru/getting-started.md`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/main.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/preload.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/views/HomeView.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/views/SettingsView.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/canvas/WorkflowCanvas.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/execution/ExecutionPanel.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/execution/ExecutionHistory.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/memory/MemoryGraphView.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/AgentNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/MemoryNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/SourceNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/OutputNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/ConditionNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/LoopNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/GuardrailNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/FallbackNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/HumanNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/SubagentNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/CommentNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/TransformNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/SchemaBuilderNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/panels/RightPanel.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/plan/PlanPanel.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/CommandPalette.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/TemplateGallery.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/OnboardingModal.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docker-compose.yml`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java` (partial)
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/SkillService.java` (partial)
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/AgentService.java` (partial)

### Modified
- (none)
