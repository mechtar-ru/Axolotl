# Axolotl — Full Project Review (2026-05-08)

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│  Browser (Vue 3 + Vue Flow)                              │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐              │
│  │ Canvas    │  │ Pinia    │  │ WebSocket │              │
│  │ (VueFlow) │◄─►│ Store    │◄─►│ composable│              │
│  └─────┬─────┘  └────┬─────┘  └─────┬─────┘              │
│        │              │              │                     │
│        └──────────────┼──────────────┘                     │
│                       │ HTTP (axios) + WS                  │
└───────────────────────┼───────────────────────────────────┘
                        │
┌───────────────────────┼───────────────────────────────────┐
│  Spring Boot 3.2 (Java 21)                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │Controller│─►│SchemaSvc │─►│LlmService│  │WebSocket │ │
│  │  /api    │  │(core)    │  │(multi-   │  │ Handler  │ │
│  └──────────┘  └────┬─────┘  │ provider)│  └──────────┘ │
│                     │        └──────────┘                 │
│                     ▼                                      │
│              ┌──────────────┐  ┌──────────┐               │
│              │Neo4j Repo    │  │MemPalace │               │
│              │                    │(memory) │               │
│              └──────────────┘  └──────────┘               │
└───────────────────────────────────────────────────────────┘
```

## Strengths

1. **Rich node system.** 14 types, each with distinct execution logic. Transform node with routing, SchemaBuilder that generates sub-workflows via LLM.
2. **Streaming execution.** WebSocket delivers tokens in real-time, wave updates, tool call events, trajectory tracking.
3. **Undo/redo** in canvas — snapshot-based with 50-level stack.
4. **Multi-provider LLM** with per-node, per-schema, per-user, and global default model resolution chain.
5. **Production deps** — Micrometer/Prometheus, Resilience4j, Bucket4j, Spring Security, JWT.
6. **13+ node types in frontend** with proper `markRaw()` registration.

## Critical Findings

### Security

| # | Severity | File:Line | Issue |
|---|----------|-----------|-------|
| S1 | **CRITICAL** | `api.ts:29-31` | Hardcoded fallback credentials `tech/tech` on 401. Attacker gets automatic auth by triggering any expired token. |
| S2 | **HIGH** | `JwtUtil.java:21` | Default JWT secret hardcoded in source. If `axolotl.jwt.secret` not configured, predictable tokens. |
| S3 | **HIGH** | `SchemaService.java:1693-1755` | Command injection via `bash -c` in CommandNode. User-supplied `command` string executed directly. |
| S4 | **HIGH** | `SchemaService.java:1757-1795` | Arbitrary file write in FileWriteNode — user controls `filePath` and `content`. Path traversal possible. |
| S5 | **MEDIUM** | `SchemaService.java:702-712` | GraalVM JS `eval()` for condition evaluation. No sandbox timeout or resource limit. |
| S6 | **MEDIUM** | `SchemaService.java:1799-1818` | SSRF via `fetchUrlContent()` — user-controlled URL fetched server-side. No allowlist. |

### Bugs & Logic Errors

| # | Severity | File:Line | Issue |
|---|----------|-----------|-------|
| B1 | **HIGH** | `SchemaService.java:97-99` | `getSchemasByUserId()` ignores `userId`, returns ALL schemas. Data leak between users. |
| B2 | **HIGH** | `SchemaService.java:1096-1106` | `fallback` node calls `schemaRepository.findById(schemaId)` 3 times in a loop. N+1 query problem. |
| B3 | **MEDIUM** | `SchemaService.java:1136` | `node.getData().setResult(result)` mutates shared object. Race condition with concurrent reads. |
| B4 | **MEDIUM** | `WorkflowCanvas.vue:930-937` | Busy-wait loop for WebSocket connection. Should use callback instead. |
| B5 | **MEDIUM** | `WorkflowCanvas.vue:452-466` | `onNodeClick` calls `convertToFlowElements()` on every click, rebuilding entire element list. |
| B6 | **LOW** | `docker-compose.yml:38-39` | `VITE_API_URL` / `VITE_WS_URL` set as runtime env vars, but Vite bakes env at build time. |

### Thread Safety

| # | Severity | File:Line | Issue |
|---|----------|-----------|-------|
| T1 | **HIGH** | `SchemaService.java:452-578` | `executeWorkflow` mutates `node.setStatus()` on shared schema objects. Concurrent modification risk. |
| T2 | **MEDIUM** | `SchemaService.java:49-53` | `executionHistory` iteration in `getExecutionHistory()` isn't synchronized. Can throw ConcurrentModificationException. |
| T3 | **LOW** | `WebSocketHandler.java:28` | `sessionLocks` map grows unbounded in `sendPlanUpdated`. |

### Architecture / Design Debt

| # | Area | Issue |
|---|------|-------|
| A1 | **Giant service** | `SchemaService.java` is 2277 lines. Node execution, Python export, mermaid export, URL fetching, project reading, schema building — all in one class. Should extract: `NodeExecutor`, `SchemaExporter`, `SchemaBuilderService`. |
| A2 | **In-memory state** | `executionHistory`, `nodeResults`, `conditionResults`, `nodeFailureCounts` — all in-memory. Lost on restart. |
| A3 | **No test coverage** | `SchemaService` has zero tests despite having test helpers. |
| A4 | **Analytics package** | 25+ classes in `analytics/` — appear mostly stub/scaffold. Significant dead weight. |
| A5 | **Mixed languages** | Backend logs in Russian, frontend UI in Russian, code comments in English, commit messages in Russian. |
| A6 | **CLAUDE.md stale** | Says "OpenClawClient is a stub" but uses LlmService. |
| A7 | **GraalVM dependency** | GraalVM polyglot pulled in solely for condition evaluation. Consider lighter alternative. |

### Frontend Issues

| # | Severity | File:Line | Issue |
|---|----------|-----------|-------|
| F1 | **MEDIUM** | `WorkflowCanvas.vue:1078-1083` | `editSchemaName()` uses `prompt()` — blocks UI. |
| F2 | **MEDIUM** | `WorkflowCanvas.vue:541` | `startRenameFromCtx()` also uses `prompt()`. |
| F3 | **LOW** | `WorkflowCanvas.vue:216` | `copiedNode` uses ref but never serialized — stale data risk. |
| F4 | **LOW** | `WorkflowCanvas.vue:314` | Empty `catch {}` in `onMounted` — provider fetch failure silently swallowed. |

### Infrastructure

| # | Issue |
|---|-------|
| I1 | `nginx.conf` referenced in docker-compose but file doesn't exist at project root. |
| I2 | ~~`postgres:15` exposed on `5432:5432`~~ (postgres removed) |
| I3 | MemPalace service uses `pip install` at container start — non-reproducible. |
| I4 | No healthcheck definitions in docker-compose. |
| I5 | No `.dockerignore` files visible. |

## Summary Score

| Dimension | Rating | Notes |
|-----------|--------|-------|
| **Features** | 9/10 | 14 node types, streaming, tool execution, schema generation. |
| **Security** | 4/10 | Hardcoded creds, command injection, SSRF, arbitrary file write. |
| **Code Quality** | 5/10 | Giant god-class, dead analytics code, no tests. |
| **Architecture** | 6/10 | Good LLM abstraction, WebSocket design. SchemaService needs decomposition. |
| **Production Ready** | 3/10 | No persisted history, stale docs, broken Docker config. |

## Recommended Priority Actions

1. **Fix S1** — remove `tech/tech` auto-login. Redirect to login page on 401.
2. **Fix B1** — implement proper user-scoped schema queries in Neo4j.
3. **Decompose SchemaService** — extract `NodeExecutor`, `SchemaExporter`, `SchemaBuilderService`.
4. **Add tests** — execution levels, condition evaluation, transform routing.
5. **Fix Docker** — create missing `nginx.conf`, add healthchecks, pin MemPalace image.
6. **Clean up analytics/** — remove or integrate the 25 stub files.
7. **Update CLAUDE.md** — reflects Neo4j, LlmService, actual node types.
