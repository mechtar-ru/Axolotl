# EIOS App Creation — Gap Analysis

> Analysis of Axolotl features still needed for successful creation of the
> Emotional Intelligence Operating System (EIOS) mobile app from
> `/Users/evgenijtihomirov/git/eios.md` (874-line design spec).
>
> Status: `release/0.4.0` at commit `2ab51b8a`

---

## 1. Model Quality & Tool Calling

### Current State
- Local models (qwen2.5-coder:7b/14b, Bonsai-8B on MLX) generate text plans
  reliably but fail to call tools (`file_write`, `bash`) in practice
- `execute_command` / `run_command` / `exec_command` aliases exist for bash
  (ToolExecutor.java:137-139) but no aliases for `file_write` variants
- `handleFileWriteWithSandbox` runs `dart analyze` after writing `.dart` files

### Gap
**No reliable tool-calling model** — the pipeline completes with zero `file_write`
calls, producing text output but no code on disk.

### Fix by User
- Connect Nemotron-3 via OpenRouter (CustomLlmProvider already supports
  OpenAI-compatible API with Bearer auth at `{baseUrl}/chat/completions`)
- Configure in Settings: base URL = `https://openrouter.ai/api/v1`,
  model = `nvidia/llama-3.1-nemotron-ultra-253b-v1:free`,
  auth = `bearer` with OpenRouter API key

### Axolotl-side Fixes Needed
| # | Fix | Effort | Priority |
|---|-----|--------|----------|
| 1a | Add `file_write` aliases for common variant names | 30 min | High |
| 1b | Detect zero-tool-call completion and surface as warning/error | 1 day | High |
| 1c | Model fallback chain — if primary model fails, try next | 1 day | Medium |

---

## 2. Pipeline Reliability for Code Generation

### Current State
- Pipeline executes 5 stages: Receive → Review → Think → Verify → Act
- Stage timeout = 20 minutes per stage
- If agent times out mid-generation, some files written, some not
- No retry on partial failure
- `autoBuildCheck` runs `flutter build apk --debug` after agent stage (opt-in)

### Gaps
| # | Gap | Effort | Priority |
|---|-----|--------|----------|
| 2a | No retry mechanism for failed stages (except review) | 2 days | High |
| 2b | Stage timeout kills all work — no way to continue from partial output | 3 days | Medium |
| 2c | Build checks only APK, not AAB or iOS | 1 day | Medium |
| 2d | No "files expected" count — agent can claim completion with 0 files written | 0.5 day | High |

---

## 3. iOS Build Chain

### Current State
- `build_app` tool runs: `flutter build apk --debug`, `flutter build appbundle --debug`,
  and `flutter build ios --no-codesign --debug` (macOS + Xcode permitting)
- `checkXcode()` now differentiates full Xcode.app vs CLI tools vs not found
- `checkAndroidSdk()` now scans common paths AND checks `flutter config --machine`
- CocoaPods validation added via `checkCocoaPods()`
- `autoBuildCheck` in `AgentNodeStrategy` now also runs `flutter build ios --no-codesign`

### Gaps
| # | Gap | Effort | Priority | Status |
|---|-----|--------|----------|--------|
| 3a | Need full Xcode.app (~8GB from App Store) for iOS builds | Manual | Critical | ⚠️ **Manual** — `checkXcode()` detects and prompts |
| 3b | `flutter build ios` needs CocoaPods | 0.5 day | High | ✅ `checkCocoaPods()` checks + prompts |
| 3c | iOS code signing certs — need Apple Developer account | Manual | High | ❌ Not automated — manual setup |
| 3d | `flutter build ios` in build / autoBuildCheck | 1 day | Medium | ✅ Done in both `build_app` and `AgentNodeStrategy` |
| 3e | No IPA generation step | 1 day | Low | ❌ Not implemented |
| 3f | `flutter build appbundle` for Play Store | 0.5 day | Medium | ✅ Done in `build_app` |

**Install command fix applied**: Xcode install now opens App Store page and gives
download link in addition to CLI tools (AgentController.java:349–353).

---

## 4. Multi-Session / Iterative Development

### Current State
- `ProjectContextBuilder` loads target path file tree into agent system prompt
- Each pipeline run is isolated — no cross-session memory
- `outputSummary` persists in Neo4j NodeExecution but is not injected into
  subsequent runs
- Pipeline runs always start from scratch

### Gaps
| # | Gap | Effort | Priority |
|---|-----|--------|----------|
| 4a | No cross-session context injection — agent doesn't see previous run's output | 3 days | High |
| 4b | No diff awareness — agent rewrites entire files, can't apply incremental changes | 4 days | Medium |
| 4c | No session plan — user can't specify "now add the analytics screen" | 2 days | Medium |
| 4d | No checkpoint/restore — can't save progress mid-session | 3 days | Low |

---

## 5. Provider & Model Support

### Current State
- CustomLlmProvider supports OpenAI-compatible API with Bearer/api-key auth
- `chatWithOpenAiClient()` sends to `{baseUrl}/chat/completions`
- OpenRouter API is at `https://openrouter.ai/api/v1/chat/completions`
- Custom endpoint can be configured: name, baseUrl, modelName, authType, apiKey
- OpenRouter requires `HTTP-Referer` and `X-Title` headers — **not currently sent**

### Gaps
| # | Gap | Effort | Priority |
|---|-----|--------|----------|
| 5a | Missing `HTTP-Referer` / `X-Title` headers required by OpenRouter | 0.5 day | High (if using OpenRouter) |
| 5b | No built-in OpenRouter provider (requires manual endpoint setup) | 2 days | Medium |
| 5c | No endpoint health check before execution — user discovers bad config mid-run | 1 day | Medium |
| 5d | No rate-limit retry for OpenRouter (Zen API has it, but not custom providers) | 1 day | Medium |

---

## 6. Quality Gates & Testing

### Current State
- Post-write `dart analyze` runs automatically (ToolExecutor VALIDATORS map)
- Stub detection (empty bodies, `// TODO`, `return null`) exists in
  `VerifierNodeStrategy` — configurable toggle per node
- `autoBuildCheck` runs `flutter build apk --debug` (opt-in)
- No unit/widget test execution

### Gaps
| # | Gap | Effort | Priority |
|---|-----|--------|----------|
| 6a | Stub detection not auto-enabled for agent nodes (only verifier nodes) | 0.5 day | High |
| 6b | No Flutter widget test execution | 2 days | Medium |
| 6c | No visual regression checks | 4 days | Low |
| 6d | No generated code size/style linting (e.g., file > 500 lines → warning) | 1 day | Low |

---

## 7. Project Type & Build Configuration

### Current State
- ProjectType.FLUTTER: `.dart` extension, `flutter build apk --debug` build,
  `dart analyze` validation, `pubspec.yaml` manifest
- Deps dialog offers Install All via brew (Flutter/Android SDK/CocoaPods)

### Gaps
| # | Gap | Effort | Priority |
|---|-----|--------|----------|
| 7a | Android APK build needs ANDROID_HOME set — no detection in deps_needed | 0.5 day | High |
| 7b | No iOS archive/export for TestFlight | 1 day | Medium |
| 7c | `flutter build apk --debug` builds for emulator only — need `--release` for production | 0.5 day | Low |
| 7d | No Flutter version check (latest stable vs old) | 0.5 day | Low |

---

## Summary: Blockers Ranked by Impact

| Rank | Item | Blocking | Can user work around? |
|------|------|----------|-----------------------|
| Rank | Item | Blocking | Status |
|------|------|----------|--------|
| P0 | Tool-calling model (Nemotron-3 via OpenRouter) | No code generation at all | ✅ CustomLlmProvider headers + rate-limit retry fixed |
| P1 | Model fallback chain | Primary model failure kills run | ✅ LlmService.buildModelChain() with fallback |
| P2 | Full Xcode.app not installed | No iOS build | ⚠️ Detected, App Store link shown — manual install |
| P3 | File_write aliases | Some models use wrong tool name | ✅ write_file / create_file / save_file aliases |
| P4 | Cross-session context | Each run starts blank | ✅ ProjectContextBuilder loads past runs |
| P5 | Zero-tool-call detection | Silent failures | ✅ File count check in AgentNodeStrategy |
| P6 | Stub detection | Low-quality code passes | ✅ VerifierNodeStrategy detects stubs |
| P7 | iOS build in autoBuildCheck | No iOS output | ✅ Both build_app and AgentNodeStrategy |
| P8 | AAB for Play Store | No appbundle | ✅ build_app builds AAB too |
| P9 | CocoaPods check | iOS build fails silently | ✅ checkCocoaPods() added |

---

## Quickstart: Connect OpenRouter to Axolotl

1. Create account at https://openrouter.ai
2. Generate API key → copy
3. In Axolotl Studio → **Settings** → scroll to Custom LLM Endpoints

```
Name:            openrouter
Base URL:        https://openrouter.ai/api/v1
Model Name:      nvidia/llama-3.1-nemotron-ultra-253b-v1:free
Auth Type:       bearer
API Key:         <your-openrouter-key>
```

4. Set schema default model to `openrouter:nvidia/llama-3.1-nemotron-ultra-253b-v1:free`
5. Execute pipeline — model should call tools reliably

> ⚠️ **Before OpenRouter works**: Axolotl must send `HTTP-Referer` and `X-Title`
> headers. See gap 5a — needs a code fix in `CustomLlmProvider.sendRawHttpRequest()`
> or `OpenAiChatClient.chat()`.

---

## `release/0.4.0` Fixes Applied

| Fix | File | What Changed |
|-----|------|-------------|
| Xcode install opens App Store | `AgentController.java:349-353` | `buildInstallCommand()` opens App Store + links |
| Deps dialog gets executionId | `StudioView.vue:196-203` | `onDepsNeeded` sets `currentExecutionId` from `data.schemaId` |
| **checkAndroidSdk** scans common paths | `ToolExecutor.java` | Scans 4 common SDK paths + `flutter config --machine` |
| **checkXcode** full vs CLI tools | `ToolExecutor.java` | Detects Xcode.app vs CLI tools vs missing |
| **checkCocoaPods** added | `ToolExecutor.java` | Checks for `pod` binary on macOS |
| **autoBuildCheck** runs iOS build | `AgentNodeStrategy.java` | New `buildAndReport()` method — runs main build + `flutter build ios --no-codesign` |
