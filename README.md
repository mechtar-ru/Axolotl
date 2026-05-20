# Axolotl

**Visual Workflow Orchestrator for Autonomous Agent Pipelines.**

Axolotl is a high-performance, graph-based execution engine designed for complex LLM workflows. It provides a visual canvas to design, execute, and monitor multi-agent systems with real-time streaming, tool-use, and human-in-the-loop capabilities.

---

## 🚀 Core Capabilities

- **Graph-Based Execution**: Directed Acyclic Graph (DAG) execution with topological sorting and parallel wave processing.
- **Multi-Agent Orchestration**: Specialized node types for Agents, Verifiers, Reviewers, and Schema Builders.
- **Multi-Stage Pipelines**: Declarative pipelines with stage-level execution, persistence, retry from failure, and cross-stage artifact passing.
- **Real-Time Observability**: Token-by-token streaming, live tool-call monitoring, and per-node execution metrics via WebSocket.
- **Extensible Tooling**: Built-in support for filesystem operations, git, bash, web search, and custom MCP (Model Context Protocol) tools.
- **Persistence**: Neo4j for all operational data, codebase analysis, and execution history.
- **Human-in-the-Loop**: Native support for manual approval gates and interactive plan refinement.

## 🏗️ Architecture

### Backend (Java 21 / Spring Boot 3.3)
- **Pipelines**: `PipelineService` orchestrates multi-stage execution with topological sort, pause/resume, and retry from failure. Stages run linearly; branches within a stage run in parallel.
- **Execution Engine**: `NodeRouter` delegates execution to specialized strategy handlers (`AgentNodeStrategy`, `VerifierNodeStrategy`, `ReviewNodeStrategy`, etc.).
- **LLM Abstraction**: Unified interface (`LlmService`) routing to OpenAI, Anthropic, DeepSeek, Zen, Ollama, and custom OpenAI-compatible providers. Model lists fetched dynamically from each provider API.
- **Code Graph**: Neo4j-backed AST analysis for context curation and hash-anchored code edits.
- **Persistence**: Neo4j for schema definitions, execution runs, node results, provider settings, auth, and codebase graph.

### Frontend (Vue 3 + TypeScript)
- **Studio**: Infinite canvas (`VueFlow`) for visual pipeline design with Schema Properties panel.
- **Block Palette**: Modular node components for pipeline construction (Receive, Agent, Review, Verify, Output).
- **Live Overlay**: Execution status, logs, and results shown as overlay on the blueprint view.
- **Pipeline Panel**: Sidebar with Build/Execute/Cancel/Retry controls and per-stage status.
- **Dashboard**: App management with recently opened tracking, collapsible sections, search.

### Desktop (Electron)
- Cross-platform desktop application bundling the Spring Boot backend for local-first development.

## 📂 Project Structure

```text
backend/          # Spring Boot application (Maven)
frontend/         # Vue 3 application (Vite)
electron/         # Desktop wrapper
scripts/          # Dev lifecycle and graph management utilities
docs/             # VitePress documentation
templates/        # Pre-configured workflow blueprints
```

## 🛠️ Getting Started

### Prerequisites

| Dependency | Version | Notes |
|-----------|---------|-------|
| Java | 21+ | Required for Spring Boot backend |
| Node.js | 18+ (20+ recommended) | For Vue 3 frontend |
| npm | 9+ | Comes with Node.js |
| Maven | 3.9+ | Build tool for Java backend |
| Neo4j | 5.x | Primary database |

### Platform-Specific Installation

<details>
<summary><b>macOS (Homebrew)</b></summary>

```bash
# Install Java 21
brew install openjdk@21

# Add to PATH (add to ~/.zshrc for permanence)
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"

# Verify
java --version   # Should show "openjdk 21.x.x"

# Install Maven
brew install maven

# Install Node.js (if not already installed)
brew install node
```
</details>

<details>
<summary><b>Ubuntu/Debian (Linux)</b></summary>

```bash
# Install Java 21
sudo apt update && sudo apt install openjdk-21-jdk maven -y

# Install Node.js 20+
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install nodejs -y

# Verify
java --version
mvn --version
node --version
```
</details>

<details>
<summary><b>Windows (Scoop)</b></summary>

```powershell
# Install scoop if not installed
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
irm get.scoop.sh | iex

# Install Java 21 + Maven + Node
scoop install openjdk21 maven nodejs

# Verify
java --version
mvn --version
node --version
```
</details>

### Quick Start (Dev Mode)

**1. Start Neo4j:**

```bash
# Using Docker (recommended)
docker run --name axolotl-neo4j -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/password -d neo4j:5
```

**2. Start the backend:**

```bash
cd backend
mvn spring-boot:run -Dserver.port=8082
```

The backend starts on `http://localhost:8082` by default.

**3. In another terminal, start the frontend:**

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on `http://localhost:5173` by default.

**4. Open your browser** at `http://localhost:5173` to see the Axolotl Studio.

### Default Credentials

When running for the first time, the backend auto-creates a default user:
- **Username:** `tech`
- **Password:** `tech`

### Configuration

Copy `.env.example` to `.env` in the project root and configure:

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `http://localhost:8082` | Backend API URL |
| `VITE_WS_URL` | `ws://localhost:8082` | WebSocket URL |
| `JWT_SECRET` | *(random)* | JWT signing secret (set a fixed value to persist tokens across restarts) |
| `ZEN_API_KEY` | — | API key for Zen provider (required for LLM features) |

## 📡 API

### Pipeline API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/schemas/{id}/pipeline/build` | POST | Build canvas nodes/edges from pipeline template |
| `/api/schemas/{id}/pipeline/execute` | POST | Execute pipeline (topological order) |
| `/api/schemas/{id}/pipeline/retry` | POST | Retry pipeline from first failed stage |
| `/api/schemas/{id}/pipeline/cancel` | POST | Cancel a running pipeline |
| `/api/schemas/{id}/pipeline/status` | GET | Get run and per-stage status |

Prefer these endpoints over manipulating nodes/edges directly — they enforce execution invariants and persistence.

### Python API Client

```bash
source .venv/bin/activate          # if using venv
python3 scripts/api.py login        # authenticate
python3 scripts/api.py GET /api/schemas
python3 scripts/api.py POST /api/schemas @schema.json
python3 scripts/api.py execute <schema-id>
python3 scripts/api.py wait <schema-id>
python3 scripts/api.py results <schema-id>
python3 scripts/api.py mcp add_task '{"title":"...","description":"..."}'
```

See `scripts/api.py` with no arguments for full usage.

## 🧪 Testing

### E2E Tests (Playwright)

Requires backend running on `:8082` and frontend on `:5173`:

```bash
cd frontend
npm run test:e2e
```

Key test files:
- `frontend/e2e/pipeline-review.spec.ts` — review → approve → completion via Pipeline Panel
- `frontend/e2e/studio-persist.spec.ts` — full persistence round-trip for all 5 node types

### Backend Tests

```bash
cd backend && mvn test
```

### Frontend Unit Tests

```bash
cd frontend && npm run test:unit
```

## 📜 Changelog

Read the root `CHANGELOG.md` for release notes. Maintainers and PR authors should add a one-line entry under `[Unreleased]` for any user-visible change.

## 📖 Additional Documentation

- **`AGENTS.md`** — Quick reference for AI agents and automation patterns (MCP commands, harness scripts, code conventions)
- **`docs/NEO4J_MIGRATION.md`** — Neo4j integration specification
- **`CHANGELOG.md`** — Full release history

## 🔧 Troubleshooting

**Port already in use:**
```bash
pkill -f "spring-boot:run"           # Kill backend
pkill -f "vite"                    # Kill frontend
cd backend && mvn spring-boot:run -Dserver.port=8083   # Or change port
```

**Maven build fails:**
```bash
cd backend && mvn clean compile
```

**Frontend node_modules issues:**
```bash
cd frontend && rm -rf node_modules && npm install --ignore-scripts
```

**Electron download fails (frontend dev doesn't need Electron):**
```bash
cd frontend && npm install --ignore-scripts
```

**Backend won't connect to Neo4j:**
```bash
# Verify Neo4j is running
curl http://localhost:7474
# Check credentials in backend/src/main/resources/application.yml
```

## 📜 License

MIT © 2026 Axolotl Contributors
