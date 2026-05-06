# 🧬 Axolotl — Visual AI-Agent Orchestration

> *"Draw logic, don't write it"*

Axolotl is a visual platform for building and executing AI-agent workflows. Design workflows from nodes on an infinite canvas, connect LLM providers, manage memory via MemPalace, and run execution in real-time with full trajectory visibility.

---

## ✨ Features

### Visual Editor
- 🎨 **Infinite Canvas** — drag-and-drop, zoom, pan via VueFlow
- 🧩 **11 Node Types**: Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback, Subagent, Group
- 🔗 **Typed Edges** — data, condition true/false, loop
- ↩️ **Undo/Redo** — Cmd+Z / Cmd+Shift+Z
- 📋 **Copy/Paste/Duplicate** — Cmd+C / Cmd+V / Cmd+D
- 🔍 **Search** — Cmd+F by node name or type
- 📦 **JSON Import/Export** — full schema exchange
- 📊 **Mermaid Export** — diagrams from schema
- 🐍 **Python Export** — generate executable `.py` scripts
- 📷 **PNG/SVG Export** — canvas screenshots
- 📝 **Comments** — text notes on canvas
- 📦 **Node Grouping** — group 2+ nodes (Ctrl+G)

### Workflow Execution
- ⚡ **Parallel Execution** — independent branches via CompletableFuture
- 📡 **WebSocket Real-time** — progress, logs, tokens, metrics, waves
- 🔄 **Token Streaming** — character-by-character LLM response
- 🚦 **Convergence Monitoring** — error counter, threshold 3 → `BLOCKED`
- 🛑 **Cancel Execution** — stop on demand
- 📊 **Execution History** — records of past runs
- 🎭 **Execution Modes**: EXECUTE (full), ANALYZE (read-only), DRY_RUN (simulate)

### Tool-Enabled Agents
- 🔧 **15 Built-in Tools**: `file_read`, `file_write`, `directory_read`, `grep`, `git`, `bash`, `memory_read`, `memory_write`, `memory_search`, `web_search`, `web_fetch`, `web_api`, `graph_query`, `mcp_execute`, `rlm_predict`
- 🎯 **7 Agent Types**: Assistant, Coder, Researcher, Reviewer, Project Analyzer, Graph Engineer, MCP Agent
- 📊 **Trajectory Panel** — visualize iterations, tool calls, timing
- 🔒 **Dangerous Command Blocking** — rm -rf, format, mkfs, etc.
- ⚙️ **Tool Permissions** — per-node allowed paths, blocked commands
- 🔗 **Graph Integration** — query Neo4j code graph with hash-anchored edits

### Agent Memory & Skills
- 🎯 **Agent Types**: Assistant, Coder, Researcher, Reviewer, Project Analyzer, Graph Engineer, MCP Agent
- 🧠 **Skill Auto-Generation** — extract patterns from trajectories → auto-generate skills
- 📈 **Skill Tracking** — usage count, success rate, version history
- 💾 **Pattern Storage** — saved to MemPalace (axolotl/patterns, axolotl/skills)

### LLM Integration
- 🦙 **Ollama** — local models, NDJSON streaming
- 🤖 **OpenAI** — GPT-4o/mini, SSE streaming
- 🧠 **Anthropic** — Claude Sonnet/Opus/Haiku
- 🔍 **DeepSeek** — budget-friendly model
- 🔗 **Custom Endpoints** — add OpenAI-compatible providers
- 🎯 **Per-Node Model** — each AgentNode selects its own model

### Template Library
- 📋 **UI/UX Review** — analyze frontend codebase, recommend improvements
- 🔨 **Frontend Refactoring** — multi-step refactor with build verification
- 📊 **Code Analysis** — RLM-powered code review

Load templates via Template Gallery (toolbar button) or import JSON.

### MemPalace — Long-term Memory
- 🧠 **Memory Node** — search memory, filter by wing/room
- 💾 **Auto-save** — agent results → MemPalace automatically
- 🌐 **Graph Visualization** — Memory Graph View with wings, rooms, tunnels
- 📊 **Graph Context** — structured tree + table → injection into systemPrompt
- 🔎 **Semantic Search** — cosine similarity
- 🗂️ **Memory Result Cards** — search results as floating cards on canvas

### Plan / Workspace
- 📋 **Todo List** — tasks with statuses and priorities
- ✍️ **Batch Add** — mass-add via textarea
- ✅ **Acceptance Criteria** — validation on DONE transition
- 🔗 **Node Linking** — bind task to canvas node
- 🤖 **MCP Server** — 7 tools via JSON-RPC 2.0 at `/mcp`
- 🧩 **Skills** — auto-learning system with usage tracking and success rate

### Observability
- 📊 **Prometheus Metrics** — `/actuator/prometheus`
- 📚 **OpenAPI/Swagger** — `/swagger.html`
- 📝 **Structured JSON Logging** — Logstash encoder for ELK/Loki

### Remote API & Integrations
- 🔑 **API Keys** — management for external systems
- 📡 **Remote API** — `/api/remote/*` for workflow triggers
- ⚡ **Rate Limiting** — 60 req/min per key
- 🔗 **Webhook Callbacks** — completion notifications
- 📤 **Share Links** — read-only links with expiration

### Subagent Workflows
- 🤝 **Subagent Node** — invoke nested workflows
- 🔄 **Input/Output Mapping** — data passing between workflows
- 🛡️ **Max Depth** — recursion protection (5 levels)
- 📜 **Nested Logs** — indented logs in Execution Panel

### Security
- 🔐 **JWT Authorization** — registration/login
- 👥 **Multi-tenancy** — schema isolation per user
- 🔑 **Settings API** — CRUD provider API keys
- 🛡️ **Guardrail Node** — data validation/transformation
- 👤 **Human Node** — wait for human confirmation

### UI/UX
- 🌙 **Dark Theme** — #1e1e2e background, #6c63ff accent
- ⌨️ **Command Palette** — Cmd+K quick access
- 🎓 **Onboarding** — 2-step wizard on first visit
- 🎬 **Animations** — pulse running, glow completed, shake failed
- 🔔 **Toast Notifications** — feedback on actions
- 💾 **Auto-save Indicator** — visual save status

### Desktop App (Electron)
- 🖥️ **Native Window** — 1400x900, min 1024x700
- 🧭 **System Tray** — show/hide, new workflow, quit
- 📋 **Application Menu** — File, Edit, View, Window, Help
- ⌨️ **Global Shortcut** — Cmd/Ctrl+Shift+A toggle visibility
- 🔔 **Native Notifications** — execution complete, errors
- 💾 **File Dialogs** — native open/save for workflows
- 🔄 **Auto-update** — electron-updater from GitHub releases

---

## 🚀 Quick Start

### Requirements
- Java 21+
- Node.js 18+
- (optional) Ollama for local LLM

### Backend
```bash
cd backend
mvn spring-boot:run
# http://localhost:8080
```

### Frontend
```bash
cd frontend
npm install
npm run dev
# http://localhost:5173
```

### Docker Compose (full stack)
```bash
docker-compose up -d
```

---

## 🛠️ Tech Stack

### Backend
| Technology | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.2 |
| SQLite | 3.x |
| PostgreSQL | (Docker) |
| WebSocket | Spring |
| Micrometer | Prometheus |
| springdoc | OpenAPI 3.0 |

### Frontend
| Technology | Version |
|-----------|---------|
| Vue | 3 (Composition API) |
| TypeScript | 5.x |
| VueFlow | 1.x |
| Vite | 5.x |
| Pinia | State management |
| Playwright | E2E testing |
| Electron | 34.x |

---

## 📐 Architecture

### Execution Flow
```
Source → Agent → Condition → Loop → Output
         ↓
      Memory (MemPalace)
         ↓
      Subagent (nested workflow)
```

1. **Topological Sort** (Kahn's algorithm) — compute levels
2. **Parallel Execution** of nodes at same level
3. **WebSocket Events**: progress, log, error, nodeTime, token, wave, toolCall, iteration
4. **Context Management**: collect upstream results + LLM summarization
5. **Variable Interpolation**: `{{input}}`, `{{prev_result}}`, `{{node:Name}}`
6. **Execution Modes**: EXECUTE/ANALYZE/DRY_RUN

---

## 📁 Project Structure

```
Axolotl/
├── backend/                          # Spring Boot 3.2, Java 21
│   └── src/main/java/.../
│       ├── controller/              # REST endpoints
│       ├── service/                # Business logic
│       ├── llm/                    # LLM providers
│       ├── model/                  # Domain objects
│       └── config/                 # Security, JWT, WebSocket
├── frontend/                         # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/
│       │   ├── canvas/             # WorkflowCanvas
│       │   ├── nodes/              # Node types
│       │   └── execution/          # ExecutionPanel
│       └── stores/                  # Pinia
├── electron/                         # Electron desktop app
├── .github/workflows/               # CI/CD
│   ├── ci.yml                     # Compile & build
│   └── release.yml                # Docker → GHCR
├── kubernetes/axolotl/             # Helm chart
├── e2e/                           # Playwright tests
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── DEPLOY.md
└── docker-compose.yml
```

---

## 🧪 Tests

```bash
# Backend tests
cd backend && mvn test

# Frontend unit tests
cd frontend && npm run test:unit

# E2E tests
cd e2e && npx playwright test
```

---

## 📊 Implementation Status

| Category | Implemented | Total |
|---------|:-----------:|:-----:|
| Visual Editor | ✅ | 14 |
| Workflow Execution | ✅ | 10 |
| LLM Providers | ✅ | 7 |
| Tool-Enabled Agents | ✅ | 6 |
| MemPalace | ✅ | 6 |
| Plan / MCP | ✅ | 7 |
| Skills System | ✅ | 5 |
| Remote API | ✅ | 6 |
| Security | ✅ | 6 |
| UI/UX | ✅ | 8 |
| Desktop App | ✅ | 8 |
| Infrastructure | ✅ | 5 |
| **Total** | | **100** |

---

## 🎯 Key Differentiators

| Feature | Axolotl | n8n | LangFlow |
|---------|:--------:|:---:|:--------:|
| Infinite canvas to chat | ✅ | ❌ | ❌ |
| Tool-enabled agents | ✅ | ❌ | ❌ |
| Trajectory visualization | ✅ | ❌ | ❌ |
| Memory as graph | ✅ | ❌ | ❌ |
| Auto-learning Skills | ✅ | ❌ | ❌ |
| Execution modes | ✅ | ❌ | ❌ |
| Built-in Plan/Todo | ✅ | ❌ | ❌ |
| Human-in-the-loop core | ✅ | basic | basic |
| Local-first privacy | ✅ | ❌ | ❌ |
| Desktop App | ✅ | ✅ | ❌ |

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Development setup
- PR process
- Coding standards
- Commit message format

---

## 📝 License

MIT
