# Axolotl

**Visual Workflow Orchestrator for Autonomous Agent Pipelines.**

Axolotl is a high-performance, graph-based execution engine designed for complex LLM workflows. It provides a visual canvas to design, execute, and monitor multi-agent systems with real-time streaming, tool-use, and human-in-the-loop capabilities.

---

## 🚀 Core Capabilities

- **Graph-Based Execution**: Directed Acyclic Graph (DAG) execution with topological sorting and parallel wave processing.
- **Multi-Agent Orchestration**: Specialized node types for Agents, Verifiers, Reviewers, and Schema Builders.
- **Real-Time Observability**: Token-by-token streaming, live tool-call monitoring, and per-node execution metrics via WebSocket.
- **Extensible Tooling**: Built-in support for filesystem operations, git, bash, web search, and custom MCP (Model Context Protocol) tools.
- **Hybrid Persistence**: SQLite for operational metadata and Neo4j for deep codebase analysis and execution history.
- **Human-in-the-Loop**: Native support for manual approval gates and interactive plan refinement.

## 🏗️ Architecture

### Backend (Java 21 / Spring Boot 3.2)
- **Execution Engine**: Strategy-based routing (`NodeRouter`) that delegates execution to specialized handlers (`AgentNodeStrategy`, `VerifierNodeStrategy`, etc.).
- **LLM Abstraction**: Unified interface for OpenAI, Anthropic, DeepSeek, Ollama, and custom OpenAI-compatible providers.
- **Code Graph**: Neo4j-backed AST analysis for context curation and hash-anchored code edits.
- **Persistence**: 
  - **SQLite**: Auth, settings, and operational schema storage.
  - **Neo4j**: Codebase graph, execution runs, and long-term history.

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
- Java 21+
- Node.js 18+
- Maven 3.9+
- Neo4j 5.x (Optional, for code graph features)

### Quick Start (Dev Mode)
```bash
# Start backend
cd backend && mvn spring-boot:run

# Start frontend
cd frontend && npm install && npm run dev
```

For a full production-like environment:
```bash
docker-compose up -d
```

## 📜 License
MIT © 2026 Axolotl Contributors
