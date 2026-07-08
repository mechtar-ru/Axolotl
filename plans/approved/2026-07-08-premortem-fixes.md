# Plan: Premortem Round 5 — 10 Fixes Across Security, Concurrency, Housekeeping

**Date:** 2026-07-08  
**Source:** Full system premortem after pull `67e162d1`  
**Branch:** `release/0.4.0` (no worktree — fixes apply directly to current branch)

## Scope

10 items prioritized by severity. 3 phases:

| Phase | Items | Risk Level | Est. Files |
|-------|-------|------------|------------|
| 1 — Security | 1–3 | CRITICAL | 1 |
| 2 — Security + Concurrency | 4–7 | HIGH | 4 |
| 3 — Housekeeping | 8–10 | MEDIUM | 5 |

---

## Phase 1: CRITICAL — VerifierNodeStrategy Code Execution Fixes

### Task 1.1 — Fix Python `-c` filename injection

**File:** `backend/src/main/java/com/agent/orchestrator/service/VerifierNodeStrategy.java:372`  
**Problem:** Filename concatenated into `python3 -c` string. Escaping is bash-style (`'\\''`) which is a no-op in ProcessBuilder — a crafted filename executes arbitrary Python.  
**Fix:** Replace `ProcessBuilder("python3", "-c", "import py_compile; py_compile.compile('" + escapedPath + "', doraise=True)")` with `ProcessBuilder("python3", "-m", "py_compile", fullPath.toString())`. No string interpolation needed.

### Task 1.2 — Fix import check arbitrary code execution

**File:** `VerifierNodeStrategy.java:393`  
**Problem:** Extracted `import`/`from` lines from LLM-generated code passed directly to `python3 -c` — executes arbitrary Python from untrusted output.  
**Fix:** Replace `ProcessBuilder("python3", "-c", importBlock.toString())` with `ProcessBuilder("python3", "-c", "import ast, sys; ast.parse(sys.stdin.read())")`, piping file content via stdin. This validates syntax without executing anything.

### Task 1.3 — Fix non-recursive file walk

**File:** `VerifierNodeStrategy.java:364`  
**Problem:** `Files.list(dir)` returns only direct children — nested `.py` files in subdirectories are missed.  
**Fix:** Replace with `Files.walk(fullPath).filter(f -> f.toString().endsWith(".py"))` limited to depth 10.

---

## Phase 2: HIGH — Plugin System + Concurrency

### Task 2.1 — PluginService: Convert string concat → ProcessBuilder list form

**Files:** `backend/src/main/java/com/agent/orchestrator/service/PluginService.java:63,114-125`  
**Problems:**
- `buildInstallCommand()` returns `"pip install " + name` — shell injection via plugin name
- Both `start()` and `runCommand()` use `ProcessBuilder("bash", "-c", command)`  
**Fixes:**
- `buildInstallCommand()` returns `List.of("pip", "install", name)` etc.
- `start()` changes from `"bash", "-c", startCommand` to the list directly
- `runCommand()` splits command into tokens and uses list form (or accepts `List<String>`)

### Task 2.2 — PluginService: Add timeout to process.waitFor() in completion handler

**File:** `PluginService.java:74`  
**Problem:** `process.waitFor()` in `runAsync` completion handler waits indefinitely.  
**Fix:** Add `process.waitFor(30, TimeUnit.SECONDS)` with destroyForcibly fallback.

### Task 2.3 — NodeRouter: Replace Thread.sleep with parkNanos/delayedExecutor

**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java:244`  
**Problem:** `Thread.sleep(5000 * attempt)` inside virtual thread pins carrier thread for 5–15s during retries.  
**Fix:** Replace with `CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS)` or `LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(waitMs))`.

### Task 2.4 — PipelineServiceImpl: Propagate cancel to inner futures

**File:** `backend/src/main/java/com/agent/orchestrator/service/PipelineServiceImpl.java:140-168`  
**Problem:** `cancelFlag` is set but inner `CompletableFuture` tasks submitted to `pipelineLevelExecutor` never check it. The outer `cancel(true)` only cancels the immediate wrapper, not the stage executions.  
**Fix:** 
- Add `cancelFlag` check at each level loop iteration in `runPipelineStages()`
- When `cancelFlag.get()`, cancel all pending futures and break

### Task 2.5 — ExecutionUtilityService: Add awaitTermination after shutdownNow

**File:** `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java:152`  
**Problem:** `exec.shutdownNow()` without `awaitTermination()` may leak threads if running tasks don't respond to interrupt.  
**Fix:** Add `exec.awaitTermination(5, TimeUnit.SECONDS)` after `shutdownNow()` with log warning on timeout.

---

## Phase 3: MEDIUM — Housekeeping

### Task 3.1 — Add eviction to unbounded maps

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/ExecutionStateManager.java:34` — `generatedFilesRegistry`
- `backend/src/main/java/com/agent/orchestrator/service/PipelineStatusManager.java:20` — `stageResults`

**Fix:** Replace `ConcurrentHashMap` with bounded size (e.g., `new ConcurrentHashMap<>(256, 0.75f, 16)` + scheduled periodic size-trim) or add a `@Scheduled` cleanup for stale entries.

### Task 3.2 — gitignore tracked build artifacts

**Problem:** `snake/generated_*` (50+ files) and `backend/.dart_tool/` (4 files) tracked in git.  
**Fix:**
- Add `snake/generated_*` to `.gitignore`  
- `git rm --cached` for `backend/.dart_tool/*` files

---

## Implementation Order

```
Phase 1 ─► Phase 2 ─► Phase 3
  (3 tasks)    (5 tasks)    (2 tasks)
```

Each phase is independent — no ordering constraints within a phase. All fixes are scoped to <10 LoC each for minimal diff risk.

## Verification

```bash
# Backend compiles
cd backend && mvn compile -q

# Tests pass
cd backend && mvn test -q

# Frontend type-check
cd frontend && npm run type-check -- --noEmit
```
