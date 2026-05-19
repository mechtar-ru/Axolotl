# Axolotl

**Visual Workflow Orchestrator for Autonomous Agent Pipelines.**

Axolotl is a high-performance, graph-based execution engine designed for complex LLM workflows. It provides a visual canvas to design, execute, and monitor multi-agent systems with real-time streaming, tool-use, and human-in-the-loop capabilities.

---

## 🚀 Core Capabilities

- **Graph-Based Execution**: Directed Acyclic Graph (DAG) execution with topological sorting and parallel wave processing.
- **Multi-Agent Orchestration**: Specialized node types for Agents, Verifiers, Reviewers, and Schema Builders.
- **Real-Time Observability**: Token-by-token streaming, live tool-call monitoring, and per-node execution metrics via WebSocket.
- **Extensible Tooling**: Built-in support for filesystem operations, git, bash, web search, and custom MCP (Model Context Protocol) tools.
- **Persistence**: Neo4j for all operational data, codebase analysis, and execution history.
- **Human-in-the-Loop**: Native support for manual approval gates and interactive plan refinement.

## 🏗️ Architecture

### Backend (Java 21 / Spring Boot 3.2)
- **Execution Engine**: Strategy-based routing (`NodeRouter`) that delegates execution to specialized handlers (`AgentNodeStrategy`, `VerifierNodeStrategy`, etc.).
- **LLM Abstraction**: Unified interface for OpenAI, Anthropic, DeepSeek, Ollama, and custom OpenAI-compatible providers.
- **Code Graph**: Neo4j-backed AST analysis for context curation and hash-anchored code edits.
- **Persistence**: 
  - **Neo4j**: All operational data, codebase graph, execution runs, and long-term history.

### Frontend (Vue 3 / TypeScript)
- **Studio**: Infinite canvas powered by `VueFlow` for visual programming.
- **Live View**: Real-time execution dashboard with streaming logs and timeline visualization.
- **Block Palette**: Modular components for rapid pipeline construction.

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

**1. Start the backend:**

```bash
cd backend
mvn spring-boot:run
```

The backend starts on `http://localhost:8082` by default.

**2. In another terminal, start the frontend:**

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on `http://localhost:5173` by default.

**3. Open your browser** at `http://localhost:5173` to see the Axolotl Studio.

### Default Credentials

When running for the first time, the backend auto-creates a default user:
- **Email:** `tech@tech.com`
- **Password:** `tech`

### Running with Docker

For a full production-like environment (backend + frontend + Neo4j):

```bash
docker-compose up -d
```

### Environment Variables

Copy `.env.example` to `.env` in the project root and configure:

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `http://localhost:8082` | Backend API URL |
| `VITE_WS_URL` | `ws://localhost:8082` | WebSocket URL |
| `JWT_SECRET` | *(random)* | JWT signing secret |

### Troubleshooting

**Port already in use:**
```bash
# Kill any existing backend process
pkill -f "spring-boot:run"
# Or change the port
cd backend && mvn spring-boot:run -Dserver.port=8083
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

## 📜 License
MIT © 2026 Axolotl Contributors
