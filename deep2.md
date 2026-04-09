# Axolotl Project Overview

## 🦎 What is Axolotl?

**Axolotl** is a visual AI agent orchestration platform with an infinite canvas where users can create, connect, and execute workflows using node-based graphs. It combines the power of AI agents with an intuitive drag-and-drop interface, real-time execution monitoring, and a marketplace for sharing prompts and templates.

### Core Concept
- **Infinite canvas** with zoom from bird's-eye view down to chat level inside nodes
- **Node-based workflow builder** (Source → Agent → Condition → Output)
- **Real-time execution** with WebSocket progress tracking
- **Long-term memory** via MemPalace integration
- **Telegram bot** for remote management
- **Marketplace** for sharing prompts, agent templates, and complete workflows

---

## 👥 Team Roles

### 🎯 Евгений (Eugenio) — Lead Developer & Visionary

**Primary Responsibilities:**
- Full-stack implementation (backend + frontend)
- System architecture design
- Technical decision making
- Prototype development
- Integration with external services (MemPalace, OpenClaw, Telegram)

**Key Contributions:**
- Created the initial prototype using React (later migrated to Vue 3)
- Implemented WebSocket for real-time execution monitoring
- Built node-based canvas with Vue Flow
- Integrated SQLite for local data persistence
- Developed export/import functionality (Mermaid, JSON)
- Added progress bars and status indicators for all node types
- Created `useWebSocket.ts` composable with auto-reconnect

**Technical Focus:**
- Spring Boot 3.2 + Java 21
- Vue 3 + TypeScript + Vue Flow
- WebSocket for real-time communication
- SQLite / PostgreSQL for data storage
- Docker Compose for orchestration

**Quote:** *"завтра прототип буду готовить, к концу дня пришлю че"*

---

### 🧠 Владимир Гусев (Vladimir) — Product Architect & Strategist

**Primary Responsibilities:**
- Product vision and requirements
- UX/UI conceptual design
- Feature prioritization
- User experience flow design
- Long-term product strategy

**Key Contributions:**
- Defined the "infinite canvas with zoom to chat" concept
- Proposed workspace plan (drag-and-drop todo list inside the app)
- Suggested model selection per agent with onboarding
- Envisioned multi-user mode with paid team plans
- Designed the two-block architecture (Artifacts + AI)
- Emphasized human-in-the-loop over full automation
- Proposed marketplace for prompt sharing

**Strategic Insights:**
> *"Весь проект это ловушка чтобы удержать человека в процессе"* — The project is a trap to keep humans in the loop.

> *"Ориентировка на гуй это чисто человеческая тема"* — GUI orientation is purely a human thing.

> *"Сначала юзер видел всю структуру, и уже от нее исходил"* — User should see the entire structure first, then work from it.

**Key Decisions Made:**
- Node-based graph over classic flowcharts (chose Vue Flow)
- Dark theme as default
- WebSocket for real-time progress
- Local-first architecture (privacy focused)
- Two workspace scenarios: default GUI prototype + full agent automation

---

## 📊 Collaboration Style

| Aspect | Евгений | Владимир |
|--------|---------|----------|
| **Role** | Builder / Implementer | Architect / Strategist |
| **Focus** | Code, technology, execution | Product, UX, vision |
| **Output** | Working prototypes, PRs | Requirements, mockups, decisions |
| **Tools** | Spring Boot, Vue, Docker | Diagrams, product specs |
| **Decision style** | "Как сделать" (How to build) | "Что делать" (What to build) |

---

## 🏗️ Project Evolution Timeline

### Phase 1: Initial Concept (April 8, 2026)
- Владимир proposes workspace with multiple scenarios
- Евгений suggests text→block-schema generation
- Agreement on default window: infinite canvas
- Decision: 2 blocks for start (Artifacts + AI)

### Phase 2: Technology Stack (April 8-9, 2026)
- Владимир recommends Spring AI + SQLite
- Vector storage for RAG
- Electron packaging for future
- Евгений implements prototype

### Phase 3: Frontend Migration (April 9, 2026)
- Decision to use Vue 3 instead of React
- Docker Compose for orchestration
- Proper separation: `backend/` and `frontend/`
- `.env` configuration for all services

### Phase 4: WebSocket Implementation (April 9, 2026)
- Full WebSocket integration by Евгений
- Progress tracking on all node types
- Status icons (⏳/✅/❌)
- Auto-reconnect logic

---

## 🚀 Current Status

| Component | Status | Owner |
|-----------|--------|-------|
| Backend API | ✅ Complete | Евгений |
| SQLite Storage | ✅ Complete | Евгений |
| Vue 3 Frontend | ✅ Complete | Евгений |
| Node Types (Source/Agent/Output) | ✅ Complete | Евгений |
| WebSocket Real-time | ✅ Complete | Евгений |
| Progress Bars | ✅ Complete | Евгений |
| Mermaid Export/Import | ✅ Complete | Евгений |
| Model Selection per Agent | ✅ Complete | Евгений |
| MemPalace Integration | ⏳ Planned | Both |
| Telegram Bot | ⏳ Planned | Both |
| Marketplace | ⏳ Planned | Both |
| Multi-user Mode | ⏳ Planned | Владимир's vision |

---

## 💡 Key Differentiators (Axolotl vs n8n, LangFlow)

| Feature | n8n | LangFlow | Axolotl |
|---------|-----|----------|---------|
| Infinite zoom to chat | ❌ | ❌ | ✅ |
| Long-term memory (MemPalace) | ❌ | ❌ | ✅ |
| Real-time execution with progress | Limited | Limited | ✅ (WebSocket) |
| Built-in todo list (workspace plan) | ❌ | ❌ | ✅ |
| Telegram bot integration | ❌ | ❌ | ✅ (planned) |
| Marketplace for prompts | ❌ | ❌ | ✅ (planned) |
| Local-first privacy | ❌ | ❌ | ✅ |

---

## 🎯 Next Milestones

### Sprint 2 (Current)
- [ ] Execution panel with timer and log
- [ ] Auto-save node positions
- [ ] Condition node (if/else)
- [ ] Loop node

### Sprint 3
- [ ] Workspace Plan (drag-and-drop todo list)
- [ ] Telegram bot
- [ ] Connection settings UI

### Sprint 4
- [ ] MemPalace integration
- [ ] Multi-user mode
- [ ] Marketplace MVP

---

## 📝 Summary

**Axolotl is a visual AI agent orchestration platform** being built by two collaborators:
- **Евгений** — the hands-on developer implementing the technical vision
- **Владимир** — the product architect shaping the user experience and long-term strategy

Together, they're creating a tool that makes complex AI workflows as simple as drawing diagrams, with unique features like infinite zoom-to-chat, long-term memory, and community marketplace — positioning Axolotl as a more intuitive and powerful alternative to existing workflow automation tools.
