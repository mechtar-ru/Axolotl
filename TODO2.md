# TODO2: Axolotl — Roadmap & Reasoning

> Co-authored perspective: Евгений (builder) + Владимир (strategist)
> Updated: 2026-04-09

---

## Philosophy

**"Рисуй логику, а не пиши её"** — Axolotl turns AI orchestration from code into visual interaction.
**"Ловушка чтобы удержать человека в процессе"** — system keeps humans in loop, not automates them away.

We're building a **local-first, privacy-focused** visual OS for AI agents. Infinite canvas + zoom-to-chat is core metaphor. Every feature must serve this vision.

---

## Phase 1: Foundation (DONE)

The prototype is alive. Core loop works: create nodes → wire them → execute → see results.

- [x] Spring Boot backend + SQLite persistence
- [x] Vue 3 + Vue Flow canvas with Source/Agent/Output nodes
- [x] REST API — CRUD schemas, execute, export Mermaid
- [x] WebSocket — real-time progress, results, errors
- [x] Docker Compose stack (backend + frontend + postgres + nginx)
- [x] Node execution: topological sort (Kahn's), Condition (GraalJS), Loop

**What we learned:** Vue Flow right call. WebSocket essential — without real-time feedback canvas feels dead. SQLite for dev, PostgreSQL for prod is good split.

---

## Phase 2: Editor Polish (current focus)

Canvas works, editing needs feel smooth & professional. This phase makes users want to build here.

### 2.1 Canvas UX
- [x] **Search** — find nodes by name/type (Cmd+F) — DONE
- [ ] **Node grouping** — subgraphs for logical sections (GroupNode.vue exists, needs wiring)
- [x] **Canvas comments** — CommentNode.vue exists
- [x] **Node collapse/expand** — all nodes have expand/collapse (AgentNode, SourceNode, LoopNode, ConditionNode, etc.)
- [ ] **Zoom to node** — double-click to focus
- [x] **Drag & drop files** — into SourceNode for file input (handleDrop implemented)

### 2.2 Prompt Editor
- [x] **Full-screen editor** — PromptEditorModal.vue with syntax-ready textarea
- [x] **Prompt templates** — 8 templates (Analysis, Summary, Translation, Extraction, Generation, Code Review, Comparison, FAQ)
- [x] **Variable interpolation** — `{{input}}`, `{{prev_result}}`, `{{node:...}}` with insert buttons

### 2.3 Execution Panel
- [x] **Parallel execution** — CompletableFuture.allOf in SchemaService.executeInParallel()
- [x] **Per-node timer** — `nodeTimeMs` displayed in AgentNode
- [ ] **Execution metrics** — nodes/sec, total time, token usage
- [x] **Execution history** — ExecutionHistory.vue + backend API (/api/schemas/{id}/history)

### 2.4 Model Selection
- [x] **Provider setup UI** — SettingsView.vue shows providers with status, models, health
- [ ] **Onboarding flow** — first-time default model setup
- [x] **Per-agent model override** — dropdown in AgentNode, populated from live providers
- [x] Model field exists in AgentNode data — wired and working

**Why this matters:** Before integrations, core editing experience must be solid. Users won't explore advanced features if basic interactions feel clunky.

---

## Phase 3: Real AI Connection

Replace `OpenClawClient` stub with actual LLM calls. This is when Axolotl becomes useful.

### 3.1 LLM Providers
- [x] **Provider abstraction** — `LlmProvider` interface + `LlmService` router
- [x] **OpenAI** — GPT-4o, GPT-4o-mini via API (OpenAiProvider.java)
- [x] **Anthropic** — Claude Sonnet/Opus via API (AnthropicProvider.java)
- [x] **DeepSeek** — budget option for simple agents (DeepSeekProvider.java)
- [x] **Ollama** — local models, fully offline mode (OllamaProvider.java)
- [ ] **OpenClaw** — action execution (tool use)

### 3.2 Connector Management
- [x] **Settings page** — SettingsView.vue shows providers, API status, models
- [ ] **Key storage** — encrypted in .env or vault
- [x] **Health check** — `isAvailable()` per provider, shown in SettingsView
- [ ] **Token balance display** — show remaining quota

### 3.3 Streaming & Context
- [ ] **Token streaming** — stream LLM output to WebSocket in real-time
- [ ] **Context management** — pass upstream results into downstream agents
- [ ] **Context compression** — summarize long chains to avoid token overflow

**Why this matters:** Stub is fine for demos; real value comes from actual AI execution. Provider abstraction lets users choose cost vs quality per agent.

---

## Phase 4: Memory & Intelligence

MemPalace integration makes agents remember across sessions. This is key differentiator.

### 4.1 Memory Node
- [ ] **MemoryNode.vue** — search/query MemPalace, teal color `#00bcd4`
- [ ] **Search UI** — input field, filters by wing/room/hall
- [ ] **Result cards** — temporary floating cards with found memories
- [ ] **Pin memories** — turn search results into permanent nodes

### 4.2 Backend Integration
- [x] **MemPalaceClient.java** — HTTP client to MCP server (search, add, context)
- [ ] **Memory as context** — inject MemPalace results into agent prompts
- [ ] **Auto-save results** — agent outputs → MemPalace for future recall
- [ ] **Schema versioning in memory** — track canvas evolution over time

### 4.3 Advanced Nodes
- [x] **Guardrail Node** — GuardrailNode.vue (yellow `#ffc107`)
- [x] **Human Node** — HumanNode.vue (orange `#ff7043`)
- [x] **Fallback Node** — FallbackNode.vue (gray `#78909c`)

**Why this matters:** Memory turns one-shot agents into persistent assistants. Vladimir's insight: "сначала юзер видел всю структуру, и уже от нее исходил" — memory gives system persistent structure.

---

## Phase 5: Workspace & Collaboration

Move from single-user tool to platform.

### 5.1 Workspace Plan
- [x] **Todo panel** — PlanPanel.vue, built-in task list in sidebar
- [x] **Drag & drop reorder** — draggable tasks in PlanPanel
- [x] **Task statuses** — todo / in progress / done / blocked (cycleStatus)
- [ ] **Link tasks to nodes** — `nodeId` field exists, highlight button exists, needs full wiring
- [ ] **Plan templates** — pre-built plans for common workflows
- [x] **Export plan** — Markdown export (exportPlan function)

### 5.2 Auth & Multi-user
- [x] **JWT authentication** — Spring Security + JWT tokens (JwtAuthFilter, JwtUtil, SecurityConfig)
- [x] **User registration/login** — AuthController: /login, /register, /me
- [x] **Roles** — admin, user, viewer (AppUser model + JWT claims)
- [ ] **Multi-tenancy** — workspace isolation
- [ ] **Invitations** — invite collaborators to workspace

### 5.3 Sharing & Export
- [x] **JSON export/import** — HomeView.vue export + import
- [x] **PNG/SVG export** — WorkflowCanvas.vue: `toPng`/`toSvg` via html-to-image
- [ ] **Python export** — generate executable script from schema
- [ ] **Share link** — read-only view of schema

**Why this matters:** Collaboration is growth lever. Solo users entry point; teams are business model.

---

## Phase 6: Ecosystem

Telegram bot, marketplace, community — network effects.

### 6.1 Telegram Bot
- [ ] **Bot setup** — register bot, configure webhook
- [ ] **Run schemas** — `/run <schema_name>` from Telegram
- [ ] **Status notifications** — execution results as messages
- [ ] **Inline keyboards** — approve/reject Human Node decisions
- [ ] **Voice commands** — speech-to-text for schema control

### 6.2 Marketplace
- [ ] **Agent templates** — publish/share agent prompts
- [ ] **Schema templates** — complete workflow blueprints
- [ ] **Browse & search** — discover community templates
- [ ] **Rating system** — upvote/downvote templates
- [ ] **One-click import** — add template to workspace

### 6.3 Triggers & Automation
- [ ] **Webhook Node** — HTTP-triggered execution (purple `#ab47bc`)
- [ ] **Schedule Node** — cron-based execution (gray `#607d8b`)
- [ ] **Event-driven** — execute on file change, email, etc.

**Why this matters:** Marketplace creates lock-in and community. Telegram extends reach beyond browser. Triggers make Axolotl real automation platform.

---

## Infrastructure & Quality (ongoing)

These run parallel to all phases. Prioritize as needed.

### Testing
- [x] **Backend unit tests** — JUnit 5 + Mockito (SchemaServiceTest, LlmServiceTest, JwtUtilTest, AgentControllerTest, ExecutionWebSocketHandlerTest)
- [ ] **Backend integration tests** — Testcontainers (API endpoints, DB)
- [x] **Frontend tests** — Vitest + Vue Test Utils (AgentNode.test, SourceNode.test, OutputNode.test, MemoryNode.test, HumanNode.test, ExecutionPanel.test, schemaStore.test, authStore.test)
- [ ] **E2E tests** — Playwright (full user flows)

### DevOps
- [ ] **Structured logging** — replace System.out.println with SLF4J
- [ ] **CI/CD** — GitHub Actions (lint, test, build, deploy)
- [ ] **API docs** — OpenAPI/Swagger UI
- [ ] **Monitoring** — Prometheus + Grafana dashboards
- [ ] **Schema versioning** — git-like history of schema changes

### Performance
- [ ] **Large graph optimization** — virtual rendering for 100+ nodes
- [ ] **WebSocket reconnection** — robust auto-reconnect with backoff
- [ ] **Pagination** — API pagination for schema lists
- [ ] **Caching** — Redis or Caffeine for frequent queries

---

## What Makes Axolotl Different

| Feature | n8n | LangFlow | Axolotl |
|---------|-----|----------|---------|
| Infinite zoom-to-chat | No | No | Yes |
| Long-term memory (MemPalace) | No | No | Yes |
| Real-time WebSocket execution | Limited | Limited | Native |
| Built-in workspace plan | No | No | Yes |
| Telegram bot | No | No | Planned |
| Marketplace for prompts | No | No | Planned |
| Local-first privacy | No | Partial | Yes |
| Human-in-the-loop focus | Basic | Basic | Core design |

---

## Priority Order (what to build next)

**Immediate (this week):**
1. ~~Search by nodes (Cmd+F)~~ → DONE
2. ~~Model selection UI with provider config~~ → DONE
3. ~~Parallel execution in backend~~ → DONE

**Short-term (next 2 weeks):**
4. ~~Real LLM provider integration (start with OpenAI + Ollama)~~ → DONE (OpenAI, Anthropic, DeepSeek, Ollama)
5. ~~Settings page for API keys~~ → DONE (SettingsView.vue)
6. ~~Node grouping + canvas comments~~ → DONE (GroupNode, CommentNode exist)

**Medium-term (next month):**
7. Memory Node + MemPalace (MemoryNode.vue exists, MemPalaceClient.java exists — needs wiring)
8. ~~JWT authentication~~ → DONE
9. ~~JSON export/import~~ → DONE
10. ~~Execution history~~ → DONE

**Long-term (2+ months):**
11. Telegram bot
12. Marketplace MVP
13. Multi-user collaboration
14. Python export

---

## Testing Plan

### Backend Tests (JUnit 5 + Mockito)
- [ ] **SchemaServiceTest** — topological sort, parallel levels, condition skipping, loop execution
- [ ] **LlmServiceTest** — provider routing, fallback to ollama
- [ ] **OllamaProviderTest** — mock HTTP calls, parse response, error handling
- [ ] **AgentControllerTest** — CRUD schemas, execute, health check with Ollama status

### Frontend Tests (Vitest)
- [ ] **WorkflowCanvas** — search, keyboard shortcuts, node CRUD
- [ ] **AgentNode** — model select, prompt editing, result display
- [ ] **schemaStore** — load, create, update, delete schemas

### Integration Tests (Manual / Playwright)
- [ ] **Ollama E2E** — create schema with Agent, execute, verify real AI response in Output node
- [ ] **Parallel execution** — create diamond graph (Source → Agent A + Agent B → Output), verify both agents run simultaneously
- [ ] **Condition branch** — Source → Condition → Agent A (true) / Agent B (false), verify only correct branch runs
- [ ] **Cancel execution** — start long workflow, press stop, verify all nodes stop
- [ ] **WebSocket reconnection** — disconnect mid-execution, verify reconnect

### Smoke Test Checklist (run before each commit)
1. [x] `cd backend && mvn compile` — passes
2. [ ] `cd frontend && npm run build` — passes (need verify)
3. [ ] Start backend + frontend → create new schema
4. [ ] Add Source → Agent → Output, wire them
5. [ ] Set Agent prompt, select "Ollama" model
6. [ ] Execute → verify real AI response appears
7. [ ] Check `/api/health` returns `ollama: true`
