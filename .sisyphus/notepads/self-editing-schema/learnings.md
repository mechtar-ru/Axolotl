# Self-Editing Schema - Session Learnings

## Completed Tasks (T0-T9)
- T0: PluginManager API + PluginService/PluginController ✅
- T1: Git worktree setup/teardown scripts ✅
- T2: Workspace path + resolvePath() in ToolExecutor ✅
- T3: grep tool (Java NIO, regex, glob filter) ✅
- T4: file_delete + file_move tools ✅
- T5: git tool (whitelisted ops: status/diff/add/commit/log/branch/checkout/merge/push) ✅
- T6: verify_build tool (npm run build + type-check) ✅
- T7: MemPalaceClient wired (field + setter + real search/addDrawer calls) ✅
- T8: btca_ask tool (ProcessBuilder, graceful error when not installed) ✅
- T9: docs_lookup tool (Context7 2-step API: resolve + query) ✅

## Key Implementation Decisions
- Used `java.net.http.HttpClient` (Java 21 built-in) for Context7 — no external HTTP lib needed
- MemPalaceClient wired same pattern as LlmService (setter injection)
- Git whitelist uses `Set.of(...)` — immutable, clear intent
- All ProcessBuilder tools default cwd to workspacePath
- Graceful degradation: btca not installed → message, MemPalace disabled → placeholder message, Context7 no key → message

## Remaining Tasks
- T10: OpenCode Skill Import Endpoint + Tool (Wave 3, can run parallel)
- T11: Refactoring Schema Template (Wave 3)
- T12: End-to-End Test (after T10-T11)
- F1: Plan Compliance Audit (oracle)
- F2: Code Quality Review (unspecified-high)

## Build Status
- `mvn clean compile` passes (107 source files)
- Pre-existing warning: SchemaService.java unchecked operations (not our change)
