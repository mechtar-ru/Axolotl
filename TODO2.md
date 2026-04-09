# TODO2: Axolotl — Roadmap & Reasoning

> Co-authored perspective: Евгений (builder) + Владимир (strategist)
> Updated: 2026-04-09

---

## Philosophy

**"Рисуй логику, а не пиши её"** — Axolotl transforms AI orchestration from code into visual interaction.
**"Ловушка чтобы удержать человека в процессе"** — the system keeps humans in the loop, not automates them away.

We're building a **local-first, privacy-focused** visual OS for AI agents. The infinite canvas with zoom-to-chat is our core metaphor. Every feature must serve this vision.

---

## Phase 1: Foundation (DONE)

The prototype is alive. Core loop works: create nodes → wire them → execute → see results.

- [x] Spring Boot backend + SQLite persistence
- [x] Vue 3 + Vue Flow canvas with Source/Agent/Output nodes
- [x] REST API — CRUD schemas, execute, export Mermaid
- [x] WebSocket — real-time progress, results, errors
- [x] Docker Compose stack (backend + frontend + postgres + nginx)
- [x] Node execution: topological sort (Kahn's), Condition (GraalJS), Loop

**What we learned:** Vue Flow was the right call. WebSocket is essential — without real-time feedback, the canvas feels dead. SQLite for dev, PostgreSQL for prod is a good split.

---

## Phase 2: Editor Polish (current focus)

The canvas works, but the editing experience needs to feel smooth and professional. This phase is about making users *want* to build here.

### 2.1 Canvas UX
- [ ] **Search** — find nodes by name/type (Cmd+F)
- [ ] **Node grouping** — subgraphs for logical sections
- [ ] **Canvas comments** — sticky notes on the canvas
- [ ] **Node collapse/expand** — minimize complexity on large graphs
- [ ] **Zoom to node** — double-click to focus
- [ ] **Drag & drop files** — into SourceNode for file input

### 2.2 Prompt Editor
- [ ] **Full-screen editor** — modal with syntax highlighting for prompts
- [ ] **Prompt templates** — quick-insert snippets inside the editor
- [ ] **Variable interpolation** — {{input}}, {{prev_result}}, etc.

### 2.3 Execution Panel
- [ ] **Parallel execution** — run independent branches simultaneously (CompletableFuture)
- [ ] **Per-node timer** — how long each node took
- [ ] **Execution metrics** — nodes/sec, total time, token usage
- [ ] **Execution history** — list of past runs with results

### 2.4 Model Selection
- [ ] **Provider setup UI** — configure API keys (OpenAI, Anthropic, DeepSeek, Ollama)
- [ ] **Onboarding flow** — first-time setup picks default model
- [ ] **Per-agent model override** — dropdown in AgentNode
- [x] Model field exists in AgentNode data — needs UI wiring

**Why this matters:** Before we add integrations (Telegram, marketplace), the core editing experience must be solid. Users won't explore advanced features if basic interactions feel clunky.

---

## Phase 3: Real AI Connection

Replace `OpenClawClient` stub with actual LLM calls. This is the moment Axolotl becomes useful.

### 3.1 LLM Providers
- [ ] **Provider abstraction** — common interface for all LLM calls
- [ ] **OpenAI** — GPT-4o, GPT-4o-mini via API
- [ ] **Anthropic** — Claude Sonnet/Opus via API
- [ ] **DeepSeek** — budget option for simple agents
- [ ] **Ollama** — local models, fully offline mode
- [ ] **OpenClaw** — action execution (tool use)

### 3.2 Connector Management
- [ ] **Settings page** — API keys, URLs, timeouts per provider
- [ ] **Key storage** — encrypted in .env or vault
- [ ] **Health check** — verify API connectivity in settings
- [ ] **Token balance display** — show remaining quota

### 3.3 Streaming & Context
- [ ] **Token streaming** — stream LLM output to WebSocket in real-time
- [ ] **Context management** — pass upstream results into downstream agents
- [ ] **Context compression** — summarize long chains (avoid token overflow)

**Why this matters:** The stub is fine for demos, but real value comes from actual AI execution. Provider abstraction lets users choose cost vs quality per agent.

---

## Phase 4: Memory & Intelligence

MemPalace integration makes agents *remember* across sessions. This is our key differentiator.

### 4.1 Memory Node
- [ ] **MemoryNode.vue** — search/query MemPalace, teal color `#00bcd4`
- [ ] **Search UI** — input field, filters by wing/room/hall
- [ ] **Result cards** — temporary floating cards with found memories
- [ ] **Pin memories** — convert search results to permanent nodes

### 4.2 Backend Integration
- [ ] **MemPalaceClient.java** — HTTP client to MCP server
- [ ] **Memory as context** — inject MemPalace results into agent prompts
- [ ] **Auto-save results** — agent outputs → MemPalace for future recall
- [ ] **Schema versioning in memory** — track canvas evolution over time

### 4.3 Advanced Nodes
- [ ] **Guardrail Node** — validate/transform outputs (yellow `#ffc107`)
- [ ] **Human Node** — pause for human approval (orange `#ff7043`)
- [ ] **Fallback Node** — error handling alternative path (gray `#78909c`)

**Why this matters:** Memory turns one-shot agents into persistent assistants. Vladimir's insight: *"сначала юзер видел всю структуру, и уже от нее исходил"* — memory gives the system structure that persists.

---

## Phase 5: Workspace & Collaboration

Move from single-user tool to platform.

### 5.1 Workspace Plan
- [ ] **Todo panel** — built-in task list in sidebar
- [ ] **Drag & drop reorder** — rearrange tasks
- [ ] **Task statuses** — todo / in progress / done / blocked
- [ ] **Link tasks to nodes** — trace plan items to specific nodes
- [ ] **Plan templates** — pre-built plans for common workflows
- [ ] **Export plan** — Markdown, JSON

### 5.2 Auth & Multi-user
- [ ] **JWT authentication** — Spring Security + JWT tokens
- [ ] **User registration/login** — email + password
- [ ] **Roles** — admin, user, viewer
- [ ] **Multi-tenancy** — workspace isolation
- [ ] **Invitations** — invite collaborators to workspace

### 5.3 Sharing & Export
- [ ] **JSON export/import** — full schema exchange
- [ ] **PNG/SVG export** — visual snapshots of schemas
- [ ] **Python export** — generate executable script from schema
- [ ] **Share link** — read-only view of schema

**Why this matters:** Collaboration is the growth lever. Solo users are the entry point; teams are the business model. Vladimir's vision: free for solo, paid for teams.

---

## Phase 6: Ecosystem

Telegram bot, marketplace, community — the network effects.

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
- [ ] **One-click import** — add template to your workspace

### 6.3 Triggers & Automation
- [ ] **Webhook Node** — HTTP-triggered execution (purple `#ab47bc`)
- [ ] **Schedule Node** — cron-based execution (gray `#607d8b`)
- [ ] **Event-driven** — execute on file change, email, etc.

**Why this matters:** Marketplace creates lock-in and community. Telegram extends reach beyond the browser. Triggers make Axolotl a real automation platform, not just a design tool.

---

## Infrastructure & Quality (ongoing)

These run parallel to all phases. Prioritize as needed.

### Testing
- [ ] **Backend unit tests** — JUnit 5 + Mockito (services, execution logic)
- [ ] **Backend integration tests** — Testcontainers (API endpoints, DB)
- [ ] **Frontend tests** — Vitest + Vue Test Utils (stores, components)
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
1. Search by nodes (Cmd+F)
2. Model selection UI with provider config
3. Parallel execution in backend

**Short-term (next 2 weeks):**
4. Real LLM provider integration (start with OpenAI + Ollama)
5. Settings page for API keys
6. Node grouping + canvas comments

**Medium-term (next month):**
7. Memory Node + MemPalace
8. JWT authentication
9. JSON export/import
10. Execution history

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
1. `cd backend && mvn compile` — passes
2. `cd frontend && npm run build` — passes
3. Start backend + frontend → create new schema
4. Add Source → Agent → Output, wire them
5. Set Agent prompt, select "Ollama" model
6. Execute → verify real AI response appears
7. Check `/api/health` returns `ollama: true`

---

## Open Questions

- **Which LLM provider first?** OpenAI for reach, Ollama for local-first purity. Maybe both simultaneously?
- **MemPalace: MCP server or direct API?** Depends on MemPalace's current interface stability
- **Auth: build or use Spring Security starter?** Starter is faster, but custom gives more control
- **Monetization timing:** When do we add paywalls? After marketplace or before?
- **Mobile:** PWA now or native later? PWA is faster, native is better UX
