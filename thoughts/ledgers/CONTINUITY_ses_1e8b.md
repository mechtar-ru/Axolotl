---
session: ses_1e8b
updated: 2026-05-11T13:53:04.521Z
---

# Session Summary

## Goal
Explore the infrastructure and scripts of the Axolotl project at `/Users/evgenijtihomirov/git/Axolotl/Axolotl/` — all CI/CD, deployment, documentation, electron app, scripts, kubernetes, e2e tests, templates, and `backend-next`/`frontend-next` directories.

## Constraints & Preferences
- Return a structured summary with exact file paths and identifiers
- Prefer terse bullets over paragraphs

## Progress
### Done
- [x] **scripts/ directory (10 files)** — read all scripts:
  - `dev.sh` — dev lifecycle: `start` (launches Spring Boot on :8080), `stop` (pkill), `logs [N]` (tail backend logs), `execute <id>` (POST execute schema via curl)
  - `migrate-schemas.py` — re-saves all WorkflowSchema nodes in Neo4j to sync node properties from JSON blob (fix-03 migration). Requires `--uri`, `--user`, `--password`. Dependencies: `neo4j`, `mempalace`
  - `migrate-to-neo4j.py` — migrates SQLite data (schemas, plans, custom_llm_endpoints, provider_settings) to Neo4j graph DB. Supports `--dry-run`, `--skip-auth`
  - `setup-graph-hook.sh` — installs a git post-commit hook that auto-runs `update-graph.sh` after each commit
  - `setup-worktree.sh` — creates a git worktree at `../Axolotl-worktree` on HEAD
  - `teardown-worktree.sh` — removes that worktree
  - `sync-to-test.sh` — rsyncs `backend/src/` → `backend-next/src/` and `frontend/src/` → `frontend-next/src/` (main → test dirs, with `--delete`)
  - `sync-from-test.sh` — reverse syncs test → main dirs (after agent verifies changes)
  - `update-graph.sh` — loads codebase from `backend/src/main/java` (or custom path) into Neo4j graph via API at configurable `API_URL` (default `http://localhost:8082`)
  - `requirements.txt` — Python deps: `mempalace>=3.3.0`, `neo4j>=5.0.0`
- [x] **docker-compose.yml** — 5 services:
  1. `postgres` — PostgreSQL 15 (internal DB, no exposed ports, healthcheck via pg_isready)
  2. `backend` — Spring Boot on :8080, builds from `./backend`, depends on healthy postgres, connects via `MEMPALACE_URL`
  3. `frontend` — Vue.js on :3000, builds from `./frontend`, takes `VITE_API_URL` / `VITE_WS_URL` as build args
  4. `mempalace` — memory service on :8765
  5. `graph` — Neo4j 5 (on :7687 BOLT / :7474 HTTP), `/Users/evgenijtihomirov/git/Axolotl/Axolotl/neo4j/data:/data`, persisted, with `NEO4J_AUTH` env vars
- [x] **kubernetes/** — Helm chart at `kubernetes/axolotl/` with `Chart.yaml`, `templates/`, `values.yaml`
- [x] **.github/workflows/** — 3 workflows:
  - `ci.yml` — trigger: push to `main` / `v*` branches + PRs to `main`. Backend: JDK 21, Maven compile; Frontend: Node 20, npm build
  - `release.yml` — trigger: release published + manual `workflow_dispatch`. Docker build & push to GHCR (multi-arch via QEMU/Buildx)
  - `docs.yml` — trigger: push to `main` with changes in `docs/**` or itself. Deploys VitePress site to GitHub Pages
- [x] **`.env.example`** — 10 env vars:
  - `AXOLOTL_DB_PATH` — absolute path for SQLite (default `~/.axolotl/schema.db`)
  - `AXOLOTL_JWT_SECRET` — HS256 key (≥32 chars)
  - `SPRING_PROFILE` — `docker`
  - `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
  - `MEMPALACE_URL` — `http://mempalace:8765`
  - `VITE_API_URL`, `VITE_WS_URL` — frontend endpoints
  - `JWT_SECRET`, `JWT_EXPIRATION` — for future auth
- [x] **electron/** — 3 files:
  - `package.json` — package `axolotl-desktop v1.0.0`, entry `dist-electron/main.mjs`
  - `main.ts` (501 lines) — spawns embedded backend (Spring Boot JAR), manages system tray, IPC handlers for notifications, file dialogs, window controls, keyboard shortcuts (Cmd/Ctrl+N/O/S/E/G/P), auto-updater (electron-updater)
  - `preload.ts` — exposes `ElectronAPI` bridge (notification, file I/O, dialogs, app version, window controls, workflow events)
- [x] **docs/** — VitePress site:
  - `index.md` — landing page with bilingual links (EN/RU)
  - `en/getting-started.md` — English guide
  - `ru/getting-started.md` — Russian guide
  - `NEO4J_MIGRATION.md` — migration manual
  - `.vitepress/` — VitePress config
- [x] **e2e/** — Playwright test `axolotl.spec.ts`: creates schema with Source→Agent→Output nodes (drag/drop), connects edges, executes via API, verifies node presence and output
- [x] **templates/** — 5 workflow JSON templates (Axolotl workflow schemas for self-editing/code analysis):
  - `ui-ux-review.json` — UI/UX code review agent
  - `refactor-frontend.json` — multi-node frontend refactoring (analyze → refactor → verify → commit) using worktree
  - `refactor-frontend-simple.json` — simplified 3-node version
  - `rllm-project-analysis.json` — project analysis with Transform nodes for field-based routing
  - `rlm-kimi-code-analysis.json` — deep codebase analysis via Recursive Language Model + Kimi
- [x] **backend-next/** — exists, contains `pom.xml`, `scripts/`, `src/`, `target/` (a Java/Maven project mirror)
- [x] **frontend-next/** — exists, contains full Vue app: `vite.config.ts`, `src/`, `public/`, `index.html`, `package.json`, TypeScript configs (`tsconfig.json`, etc.)

## Key Decisions
- **Worktree + sync workflow**: `sync-to-test.sh` copies main code to `-next` dirs for agent editing; `sync-from-test.sh` copies back after verification — enables safe AI-driven refactoring without touching originals directly
- **Neo4j as primary store**: DB migrations go from SQLite → Neo4j; `update-graph.sh` + git hook keep graph in sync with codebase
- **Electron bundles backend**: `main.ts` spawns the Spring Boot JAR as a child process and pings `/api/health` before showing the window — app is self-contained desktop package

## Next Steps
1. Research or modify specific scripts (e.g., `migrate-to-neo4j.py`, `sync-*.sh` integration, `dev.sh` expansion)
2. Extend CI/CD or kubernetes chart for staging/production environments
3. Update `.env.example` with any new service vars (graph, mempalace auth)
4. Add e2e tests for more node types / execution modes
5. Write additional workflow templates (e.g., backend refactoring, full-stack analysis)
6. Harden Electron `main.ts` (error handling, OS-specific paths, autoupdate signing)

## Critical Context
- **Project root**: `/Users/evgenijtihomirov/git/Axolotl/Axolotl/`
- **Main stack**: Spring Boot (Java 21) + Vue 3 (Vite) + PostgreSQL + Neo4j + MemPalace + Electron + Docker + K8s Helm chart
- **Scripts dependency**: Python 3 with `mempalace>=3.3.0` and `neo4j>=5.0.0`
- **Sync pattern**: `backend/` ↔ `backend-next/` and `frontend/` ↔ `frontend-next/` via rsync with `--delete`
- **CI runs on**: `ubuntu-latest`, JDK 21 (temurin), Node 20
- **Docker images**: published to `ghcr.io` under the org
- **Docs**: VitePress, bilingual (EN/RU), auto-deployed to GitHub Pages on push to `main` with `docs/**` changes
- **E2E**: Playwright, single spec `axolotl.spec.ts` testing create → connect → execute flow
- **Templates**: All stored as Axolotl workflow JSON files in `templates/`, loadable via `id` (e.g., `tpl-ui-ux-review`, `tpl-refactor-frontend`)

## File Operations
### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/.env.example`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/.github/workflows`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/.github/workflows/ci.yml`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/.github/workflows/docs.yml`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/.github/workflows/release.yml`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/docker-compose.yml`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/e2e/axolotl.spec.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/main.ts` (partial: first 30 lines)
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/package.json`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/electron/preload.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/kubernetes/axolotl`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/dev.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/migrate-schemas.py`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/migrate-to-neo4j.py`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/requirements.txt`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/setup-graph-hook.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/setup-worktree.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/sync-from-test.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/sync-to-test.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/teardown-worktree.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/update-graph.sh`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/templates/refactor-frontend-simple.json`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/templates/refactor-frontend.json`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/templates/rllm-project-analysis.json`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/templates/rlm-kimi-code-analysis.json`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/templates/ui-ux-review.json`

### Modified
- (none)
