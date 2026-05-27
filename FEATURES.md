# Axolotl — Feature Overview

## Pipeline Engine

- **Multi-stage pipeline execution** — topological sort, parallel per-level, pause/resume/retry
- **5 default stages**: Receive → Review → Agent → Verify → Output
- **TDD mode**: expands each branch to Test → Verify-Test → Implement → Verify-Implement
- **Cross-stage artifact passing** — dot-notation field extraction from upstream stage outputs
- **Stage-level retry from failure** — creates child run, resets only failed+dependent stages
- **Per-stage timeout** (configurable, default 20 min)
- **autoBuildCheck** — runs `flutter build` / `npm run build` / `go build` after agent stage

## Node Types

| Type | Purpose |
|------|---------|
| Receive | File input, URL fetch, text paste, or project directory listing |
| Agent | Tool-enabled LLM with system prompt, model, tools, iterations |
| Review | Two-phase plan + analysis (premortem/prism/postmortem); manual/auto/hybrid modes |
| Verifier | Structured JSON verdict, auto-rewrite loop (configurable max retries) |
| Output | Collect results; stdout, log, or summary report modes |

## LLM Provider Support

- **OpenAI**, **Anthropic**, **DeepSeek** — official API
- **Ollama** — local inference (qwen2.5-coder:7b/14b, llama3.1, etc.)
- **OpenCode Zen** — free tier (deepseek-v4-flash-free)
- **Custom OpenAI-compatible** — any endpoint (MLX, vLLM, LM Studio, etc.)
- Provider model lists fetched dynamically per provider
- API key management via Settings UI (encrypted at rest)

## Agent Tools

- `file_read` / `file_write` / `directory_read` — filesystem sandboxed by `targetPath`
- `bash` — sandboxed shell with allowed command list
- `grep` — regex search
- `git` — version control operations
- `web_search` / `web_fetch` — web access
- `memory_read` / `memory_write` — persistent key-value memory
- `build_app` — check SDK dependencies + run build (multi-target: Flutter, Python, Web, Go, Rust)
- Post-write syntax validation (dart analyze, python3 -m py_compile, javac + per ProjectType)

## Project Types

| Type | Validation | Build |
|------|-----------|-------|
| Flutter | `dart analyze` | `flutter build apk --debug` |
| Python | `python3 -m py_compile` | `python3 -m py_compile *` |
| Web (Vite/React) | `npx tsc --noEmit` | `npm run build` |
| Go | `go vet ./...` | `go build ./...` |
| Rust | `cargo check` | `cargo build` |

## Canvas (BlueprintView)

- **Infinite canvas** — VueFlow-based drag-and-drop node editor
- **Node palette** — drag from palette onto canvas to create
- **Edge routing** — connect nodes by dragging between ports
- **Undo/Redo** — Ctrl+Z / Ctrl+Shift+Z (50-level history)
- **Schema Properties panel** — name, description, target path, default model, project type
- **BlockConfig panel** — per-node configuration (model, prompt, tools, verifier checks, review mode, source type)
- **Auto-save** — debounced dirty flag → PUT /api/schemas/{id}

## Review System

- **3 check types**: Premortem, Prism, Postmortem
- **3 modes**:
  - Manual — always requires human approval (PASS and REWRITE)
  - Auto — auto-fixes up to N iterations, fails on exceed
  - Hybrid — auto-fix N times, then ask human
- **Plan generation** — LLM generates structured plan before analysis
- **Plan editing** — user can edit plan in dialog before approving

## Authentication

- JWT-based: POST /api/auth/login → token
- Two built-in users: admin/admin, tech/tech
- Spring Security with request matcher-based auth (no auth on /api/**)

## Execution Persistence

- `ExecutionRun` — one per schema execution (status, stage status, token counts, error)
- `NodeExecution` — one per node per run (output summary, tool calls, duration, files written)
- `resumeIndex` — persisted for pause/resume across restarts
- WebSocket events: progress, log, result, error, complete, metrics, paused, deps_needed

## Development Experience

- **Dev script**: `scripts/dev.sh start|stop|logs|execute`
- **API client**: `scripts/api.py` with auth caching (login, GET, POST, execute, wait, results, nodes)
- **Token helper**: `source scripts/token.sh` sets `$TOKEN` and `$CURL_HEADER`
- **Neo4j graph helper**: `scripts/update-graph.sh` loads codebase into Neo4j
- **Test workflow**: sync-to-test/sync-from-test scripts for safe experimentation

## Snapshot Features (import/export)

- Schema Export — download full schema as JSON from StudioTopBar
- Schema Import — upload JSON file from Dashboard to create new schema
- Includes all nodes, edges, pipeline stages, and config

## Agent Personas

Built-in system prompt presets selectable in BlockConfigPanel:

| Persona | Style |
|---------|-------|
| Architect | Plan-first: write spec, get approval, then implement |
| Hacker | Code-first: minimal planning, maximum velocity |
| Teacher | Explain everything: verbose code with educational comments |
| Minimalist | Concise: produce only the minimum viable implementation |
| TDD | Test-first: write tests before implementation, verify each pass |
