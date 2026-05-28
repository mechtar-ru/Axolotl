# LangChain4j Integration — Implementation Plan

**Goal:** Integrate LangChain4j as an optional, feature-flagged LLM orchestration provider that implements the existing `LlmProvider` interface, providing standardized chat/streaming, tool dispatch, and chain constructs while preserving Axolotl's Neo4j persistence, WebSocket streaming, and sandbox security.

**Architecture:** Adapter-first — `LangChain4jProvider` implements `LlmProvider`, adapting LangChain4j `ChatLanguageModel` + `ToolSpecification` to Axolotl's tool-execution model via a thin `LangChainToolAdapter`. Most code paths (`LlmService`, `AgentNodeStrategy`, `NodeRouter`, `ExecutionWebSocketHandler`) remain unchanged. LangChain4j is gated behind a Maven profile (`langchain4j`) and a Spring property (`axolotl.features.langchain4j`).

**Design:** `thoughts/shared/designs/2026-05-26-langchain4j-integration-design.md`

**Estimated total: 6–8 weeks (team of 1–2 engineers) | Spike: 1 week**

---

## Dependency Graph

```
Batch 1 (parallel — 3 tasks): 1.1, 1.2, 1.3   [Foundation — Maven config, feature flag, skeleton]
Batch 2 (parallel — 3 tasks): 2.1, 2.2, 2.3   [Core — Provider, ToolAdapter, LlmService registration]
Batch 3 (parallel — 2 tasks): 3.1, 3.2         [Integration — AgentNodeStrategy tools wiring, WS streaming]
Batch 4 (parallel — 2 tasks): 4.1, 4.2         [Settings & Config — backend settings, frontend UI]
Batch 5 (parallel — 2 tasks): 5.1, 5.2         [Testing & Canary — e2e smoke, canary schema, monitoring]
Batch 6 (serial — 1 task):    6.1              [Rollout — feature flag flip, docs, changelog]
```

---

## Batch 1: Foundation (parallel — 3 implementers)

All tasks in this batch have NO dependencies on each other.

### Task 1.1: Maven POM — LangChain4j BOM + profile + connectors
**File:** `backend/pom.xml`
**Test:** none (build verification only)
**Depends:** none
**Effort:** 2 hours

Add the LangChain4j BOM under `dependencyManagement`, then add core + OpenAI + Ollama connectors under a new Maven profile `langchain4j` that is NOT active by default.

**Key decisions:**
- First-class connectors: **OpenAI** (langchain4j-open-ai) and **Ollama** (langchain4j-ollama) per design's open questions — these cover cloud and local use cases
- All under `dev.langchain4j` group
- Profile gates the entire dependency tree so `mvn compile` without `-Plangchain4j` produces zero LangChain4j classes on the classpath
- Add `axolotl.langchain4j.version` property for single-version management

**Implementation sketch (add to pom.xml):**

```xml
<properties>
  <!-- ...existing properties... -->
  <langchain4j.version>1.0.0-beta2</langchain4j.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-bom</artifactId>
      <version>${langchain4j.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<profiles>
  <profile>
    <id>langchain4j</id>
    <activation>
      <activeByDefault>false</activeByDefault>
    </activation>
    <dependencies>
      <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-core</artifactId>
      </dependency>
      <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
      </dependency>
      <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-ollama</artifactId>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

**Verify:** `cd backend && mvn compile` (passes without profile). Then `cd backend && mvn compile -Plangchain4j` (passes with profile, LangChain4j imports resolve).

**Commit:** `feat(build): add LangChain4j BOM and Maven profile for optional integration`

---

### Task 1.2: Feature flag — Spring property + conditional config
**File:** `backend/src/main/java/com/agent/orchestrator/config/LangChain4jConfig.java`
**Test:** none (config file, tested implicitly via integration tests)
**Depends:** none
**Effort:** 30 min

Create a `@Configuration` class with `@ConditionalOnProperty(name = "axolotl.features.langchain4j", havingValue = "true", matchIfMissing = false)`. This class will be the entry point for all LangChain4j-related beans. Initially it will be a no-op shell; Batch 2 will register beans here.

Add the property to `application.yml`:

```yaml
axolotl:
  features:
    langchain4j: ${AXOLOTL_FEATURE_LANGCHAIN4J:false}
```

**Implementation:**
```java
package com.agent.orchestrator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация LangChain4j. Активируется только при axolotl.features.langchain4j=true.
 * Все LangChain4j-бины регистрируются здесь (условно),
 * чтобы избежать ClassNotFoundException при отсутствии профиля.
 */
@Configuration
@ConditionalOnProperty(name = "axolotl.features.langchain4j", havingValue = "true", matchIfMissing = false)
public class LangChain4jConfig {
    // Beans will be registered in Batch 2 (Task 2.1 and 2.2)
}
```

**Verify:** `cd backend && mvn compile` passes. Backend starts with `axolotl.features.langchain4j=false` (default) without any LangChain4j classloading.

**Commit:** `feat(config): add langchain4j feature flag with conditional configuration`

---

### Task 1.3: LangChain4jProvider skeleton — implements LlmProvider (chat only, no streaming, no tools)
**File:** `backend/src/main/java/com/agent/orchestrator/llm/LangChain4jProvider.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/llm/LangChain4jProviderTest.java`
**Depends:** none (implements interface only; no LangChain4j imports until the file-level feature gate is added)
**Effort:** 4 hours

**IMPORTANT:** This file must NOT use LangChain4j imports at the class level, because it may be compiled without the langchain4j profile. Instead, use reflection or a conditional bean pattern. The safest approach: make this class `@ConditionalOnBean(LangChain4jConfig.class)` so Spring only loads it when the config is active, and the Maven profile ensures compile-time resolution.

Create a skeleton provider that:
- Implements `LlmProvider`
- Uses constructor injection for `SettingsService` (to read provider config)
- Exposes `getName()` = `"langchain4j"`
- `chat()` delegates to a mocked/internal `String chat(...)` that will be wired in Batch 2.1
- `isAvailable()` returns true (gate kept soft — actual connector readiness checked later)
- `listModels()` returns empty list for now
- Annotated with `@Component` but gated via `@ConditionalOnBean(LangChain4jConfig.class)`

**Decision rationale:** The skeleton must compile and function without the LangChain4j profile active at the source level for the initial spike. Since we add the profile in 1.1 and this task runs in parallel, we use `@ConditionalOnBean` which is pure Spring — no LangChain4j imports needed yet in the skeleton.

**Test requirements:**
- `getName_returnsLangchain4j` — assert `getName().equals("langchain4j")`
- `chat_returnsResult` — mock internal behavior, verify interface contract
- `supportsStreaming_defaultFalse` (will enable in Batch 2.3)
- `isAvailable_returnsTrue`
- Verify provider is NOT loaded when feature flag is off

**Verify:** `cd backend && mvn test -Dtest=LangChain4jProviderTest`

**Commit:** `feat(llm): add LangChain4jProvider skeleton with interface implementation`

---

## Batch 2: Core Modules (parallel — 3 implementers)

All depend on Batch 1 completing.

### Task 2.1: LangChain4jProvider — full chat() and streamingChat() with ChatLanguageModel
**File:** `backend/src/main/java/com/agent/orchestrator/llm/LangChain4jProvider.java` (modify)
**Test:** `backend/src/test/java/com/agent/orchestrator/llm/LangChain4jProviderTest.java` (extend)
**Depends:** 1.1, 1.2, 1.3 (LangChain4j on classpath, feature flag, skeleton in place)
**Effort:** 8 hours

Fill in the skeleton with real LangChain4j wiring:
- Accept `@Value("${axolotl.llm.langchain4j...}")` config for base URL, API key, default model
- On first `chat()` call, construct a `ChatLanguageModel` via `OpenAiChatModel.builder()` or `OllamaChatModel.builder()` based on `axolotl.llm.langchain4j.connector` property
- Cache the model instance (reuse per provider)
- `chat()` → call `model.generate(...)` and return the response text
- `streamingChat()` → use `model.generate(...)` with streaming callback; call `onToken.accept(token)` for each token; return full response at end
- `supportsStreaming()` returns `true` when a streaming-capable model is configured
- `listModels()` → probe the connector API or return known defaults

**Configuration in `application.yml`:**
```yaml
axolotl:
  llm:
    langchain4j:
      enabled: false
      connector: ${AXOLOTL_LANGCHAIN4J_CONNECTOR:openai}
      base-url: ${AXOLOTL_LANGCHAIN4J_BASE_URL:}
      api-key: ${AXOLOTL_LANGCHAIN4J_API_KEY:}
      default-model: ${AXOLOTL_LANGCHAIN4J_MODEL:gpt-4o}
```

**Key decision:** We cache the `ChatLanguageModel` as a field on the provider, constructed lazily on first call. This avoids startup failures when the connector is not reachable and aligns with how other providers work (e.g., `OllamaProvider` uses `HttpClient` reused per-request, but `ChatLanguageModel` is heavier so we build once).

**Test requirements:**
- Mock `ChatLanguageModel` using Mockito
- `chat_withMock_returnsResponse` — stub `generate()` to return `AiMessage.text("hello")`, verify `chat()` returns "hello"
- `streamingChat_withMock_emitsTokens` — use `StreamingChatLanguageModel` mock, capture callbacks
- `streamingChat_returnsFullResponse`
- `listModels_withOpenAiConnector_returnsModelList`
- Error path: `chat_whenModelThrows_returnsErrorMessage`

**Verify:** `cd backend && mvn test -Plangchain4j -Dtest=LangChain4jProviderTest`

**Commit:** `feat(llm): implement LangChain4jProvider chat() and streamingChat() with ChatLanguageModel`

---

### Task 2.2: LangChainToolAdapter — ToolSpecification ↔ ToolExecutor mapping
**File:** `backend/src/main/java/com/agent/orchestrator/llm/LangChainToolAdapter.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/llm/LangChainToolAdapterTest.java`
**Depends:** 1.1, 1.2 (feature flag + LangChain4j on classpath)
**Effort:** 6 hours

Create an adapter class that:
- Takes `ToolExecutor` (injected via constructor)
- `toToolSpecifications(List<String> toolIds)` — converts Axolotl tool IDs to LangChain4j `ToolSpecification` objects by looking up tool definitions from `ToolExecutor.getTool()`
- `executeTool(ToolSpecification, Map<String, Object> args)` — calls `ToolExecutor.execute()` synchronously with sandbox validation, wraps the `ToolResult` back into a format LangChain4j chains can consume
- `validateSandboxPath(requestedPath, permission, schemaTargetPath)` — delegates to `ToolExecutor.validateSandboxPath()` for all file-write operations
- Returns `ToolExecutionResult` containing either success output or error message

**Key design decisions:**
- Synchronous-first per design constraints — `ToolExecutor.execute()` is synchronous, so the adapter is synchronous
- Tool descriptions are extracted from `Tool.getDescription()` and `Tool.getJsonSchema()` to build LangChain4j `ToolSpecification`
- Input validation: for `file_write` and `bash` tools, add extra parameter descriptions for `allowedPaths` and `blockedCommands` at the LangChain4j spec level so the model has visibility into constraints
- All security checks (`validateSandboxPath`, command allowlist) happen inside `ToolExecutor`, not in the adapter — adapter only passes through

**Test requirements:**
- `toToolSpecifications_translatesToolIds` — verify `ToolSpecification` name/description for known tools
- `executeTool_callsToolExecutor` — mock `ToolExecutor`, verify it's called with correct params
- `executeTool_whenSandboxBlocked_returnsError` — stub `ToolExecutor` to return a sandbox error, verify adapter returns error result
- `executeTool_fileWrite_callsValidateSandbox` — verify the sandbox path check is invoked
- `toToolSpecifications_unknownTool_skipped` — unknown tool IDs are silently skipped

**Verify:** `cd backend && mvn test -Plangchain4j -Dtest=LangChainToolAdapterTest`

**Commit:** `feat(llm): add LangChainToolAdapter for ToolSpecification-to-ToolExecutor mapping`

---

### Task 2.3: LlmService — register langchain4j provider + model routing rules
**Files:**
- `backend/src/main/java/com/agent/orchestrator/llm/LlmService.java` (modify)
- `backend/src/main/java/com/agent/orchestrator/llm/LlmService.java` (test — extend existing Test)
**Depends:** 1.2, 1.3, 2.1 (needs the provider + feature flag)
**Effort:** 3 hours

Changes to `LlmService`:
- Add `"langchain4j"` to the `resolveProvider()` switch with a prefix rule: any model starting with `"lc4j-"` routes to LangChain4j provider
- Update `getProvidersInfo()` to include LangChain4j when the provider bean is present (it auto-registers via `@Component` + `@ConditionalOnBean`)
- Ensure `preloadProviderCache()` handles LangChain4j gracefully: if connector key is missing, `isAvailable()` returns false but doesn't crash

**Key decision:** Using `lc4j-` prefix to route to the LangChain4j provider. This is intentionally distinct from model names like `gpt-4o` or `claude-3` so users explicitly opt in. The prefix is documented.

**Test requirements:**
- `resolveProvider_withLc4jPrefix_returnsLangchain4j` — model `"lc4j-gpt-4o"` → provider name `"langchain4j"`
- `getProvidersInfo_includesLangchain4j` — when LangChain4jProvider bean is present
- `refreshProviderCache_handlesLangchain4jGracefully` — no crash when connector key missing

**Verify:** `cd backend && mvn test -Dtest=LlmServiceTest`

**Commit:** `feat(llm): register LangChain4j provider with lc4j- model prefix routing`

---

## Batch 3: Integration (parallel — 2 implementers)

### Task 3.1: AgentNodeStrategy — pass tools to LangChain4jProvider
**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/AgentNodeStrategy.java` (modify — small change)
- `backend/src/main/java/com/agent/orchestrator/llm/LlmProvider.java` (modify — add tool-aware interface if needed)
**Depends:** 2.1, 2.2, 2.3 (all core modules)
**Effort:** 4 hours

**Design requires:** "When building tools for an agent node, ensure tool descriptors are passed to LlmService which can pass them to LangChain4jProvider."

The current `LlmProvider.chat()` and `streamingChat()` signatures accept `Map<String, Object> config`. We pass tool descriptors via this config map:

1. Add a key convention: `AgentNodeStrategy` puts tool IDs into the config map under `"_tools"` as `List<String>`
2. `LangChain4jProvider.chat()` (or a new method) checks for `"_tools"` in config
3. If present, calls `LangChainToolAdapter.toToolSpecifications()` and constructs a tool-enabled LangChain4j chain
4. The chain runs: model generates → if tool call emitted → adapter calls `ToolExecutor` → feeds result back for continuation
5. Tokens and tool results are forwarded via the existing `onToken` consumer

**Changes to `LlmProvider` interface — optional:** To avoid interface breakage, we add a default method:

```java
default String chatWithTools(String model, String systemPrompt, String userPrompt,
                              List<String> toolIds, Map<String, Object> config,
                              Consumer<String> onToken) {
    // Default: fall back to streamingChat (no tools)
    return streamingChat(model, systemPrompt, userPrompt, config, onToken);
}
```

`LangChain4jProvider` overrides this to build a tool-enabled chain. Other providers ignore tools.

**Changes to `AgentNodeStrategy.executeToolAgentNode()`:**
- Build the tool ID list from `enabledTools`
- When calling `llmService.chat()`, pass `"_tools"` key in config containing the tool IDs
- OR use the new `chatWithTools()` method

**Key decision:** Rather than a new interface method, we use the existing `config` map with an internal `"_tools"` key. This avoids breaking all 8 existing provider implementations. `LangChain4jProvider.chat()` checks `config` for tools and delegates to the adapter chain if present.

**Test requirements:**
- Extend `LangChain4jProviderTest` to verify tool flow: `chat_withToolsInConfig_buildsToolChain`
- Mock `ToolExecutor` and `LangChainToolAdapter` in provider test
- Verify `AgentNodeStrategy` passes `_tools` in config when tools are enabled

**Verify:** `cd backend && mvn test -Plangchain4j -Dtest=LangChain4jProviderTest,AgentNodeStrategyTest`

**Commit:** `feat(execution): wire tool descriptors through config map for LangChain4jProvider`

---

### Task 3.2: Streaming parity — WebSocket token delivery for LangChain4j
**Files:**
- `backend/src/main/java/com/agent/orchestrator/llm/LangChain4jProvider.java` (modify — streaming chain)
- `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java` (test only — no prod changes needed)
**Depends:** 2.1, 3.1 (streaming chain with tools)
**Effort:** 3 hours

**Design requires:** "Streaming tokens flow to ExecutionWebSocketHandler and client shows same UX."

The existing `streamingChat()` contract already:
- Accepts `Consumer<String> onToken`
- Returns full response at end

`LangChain4jProvider.streamingChat()` must:
- Build a `StreamingChatLanguageModel` (via the LangChain4j connector)
- Register a `StreamingResponseHandler` that calls `onToken.accept()` for each `onNext(word)`
- If tools are enabled, use LangChain4j's built-in streaming tool call handling (which also emits tokens)
- Handle `onComplete()` by completing the response string
- Handle `onError()` by logging and returning partial response

**Key decision:** LangChain4j 1.0 has `StreamingChatLanguageModel` which handles both text tokens and tool-call tokens in the same stream. The adapter maps `StreamingResponseHandler.onNext()` → `onToken.accept()` for text, and intercepts tool-call segments to run them through `LangChainToolAdapter` while still streaming text tokens around them.

No changes needed to `ExecutionWebSocketHandler` — it already consumes tokens generically via the `Consumer<String>` interface.

**Test requirements:**
- `streamingChat_withMock_emitsTokensViaConsumer` — mock `StreamingChatLanguageModel`, capture consumer invocations
- `streamingChat_emitsToolCallTokens` — test tool-call token streaming alongside text tokens
- `streamingChat_onError_returnsPartialResponse` — partial tokens returned on stream error
- Verify token latency < 2x baseline (benchmark test, not CI assertion)

**Verify:** `cd backend && mvn test -Plangchain4j -Dtest=LangChain4jProviderTest`

**Commit:** `feat(streaming): implement LangChain4j streaming token delivery via WebSocket consumer`

---

## Batch 4: Settings & Config (parallel — 2 implementers)

### Task 4.1: SettingsService/SettingsController — LangChain4j provider config + health check
**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/SettingsService.java` (modify)
- `backend/src/main/java/com/agent/orchestrator/controller/SettingsController.java` (modify)
- `backend/src/main/java/com/agent/orchestrator/llm/LangChain4jProvider.java` (modify — health check support)
**Depends:** 2.1, 2.3 (provider works, LlmService routes to it)
**Effort:** 4 hours

**Design requires:** "Expose provider config in SettingsView, add test-before-save, and one-click sample config for popular connectors."

Backend changes:
1. `SettingsService` — add `getLangChain4jConfig()` / `saveLangChain4jConfig(Map)` that persists to Neo4j `GraphProviderSetting` (reusing the existing pattern for provider settings)
2. `SettingsController` — add `GET /api/settings/langchain4j` and `PUT /api/settings/langchain4j` endpoints
3. `GET /api/settings/langchain4j/health` — calls `LlmService.checkProviderHealth("langchain4j")` which triggers `LangChain4jProvider.isAvailable()` (pings the configured connector endpoint)
4. `LangChain4jProvider.isAvailable()` — now reads config from `SettingsService` instead of (or in addition to) `@Value`, allowing runtime config changes

**Key decision:** Reuse `GraphProviderSetting` Neo4j node pattern with key prefix `langchain4j.connector`, `langchain4j.baseUrl`, `langchain4j.apiKey`, etc. This is the same pattern used for custom LLM endpoints.

**Test requirements:**
- `SettingsControllerTest` — verify `GET /api/settings/langchain4j` returns current config
- `PUT /api/settings/langchain4j` — verify config is persisted and returned
- `GET /api/settings/langchain4j/health` — verify health check delegates to provider
- `LangChain4jProvider` — `isAvailable` returns success/failure based on SettingsService data

**Verify:** `cd backend && mvn test -Dtest=SettingsControllerTest,SettingsServiceTest`

**Commit:** `feat(settings): add LangChain4j provider configuration endpoints with health check`

---

### Task 4.2: Frontend — SettingsView provider config UI for LangChain4j
**File:** `frontend/src/views/SettingsView.vue` (modify)
**Test:** `frontend/src/views/__tests__/SettingsView.test.ts` (modify/extend)
**Depends:** 4.1 (backend endpoints exist)
**Effort:** 4 hours

Add "LangChain4j" section to the SettingsView with:
- Toggle to enable/disable (sets `axolotl.features.langchain4j` — or maps to the backend config)
- Connector dropdown: OpenAI / Ollama / Custom
- Base URL input (pre-filled with defaults per connector)
- API Key input (masked)
- Default model input
- "Test Connection" button that calls `GET /api/settings/langchain4j/health` and shows status
- "Save" button that `PUT`s to `/api/settings/langchain4j`
- Help text linking to LangChain4j docs for each connector

**Key decision:** Per design, "provide clear SettingsView defaults, sensible test-before-save." We use an accordion section similar to existing provider settings, with pre-populated defaults:
- OpenAI: `https://api.openai.com/v1`, model `gpt-4o`
- Ollama: `http://localhost:11434`, model `llama3.1`

**Test requirements:**
- Renders LangChain4j section when backend config includes it
- "Test Connection" calls the health endpoint
- Save button PUTs config
- Connector dropdown changes default model/base URL

**Verify:** `cd frontend && npm run test:unit`

**Commit:** `feat(ui): add LangChain4j provider configuration panel in SettingsView`

---

## Batch 5: Testing & Canary (parallel — 2 implementers)

### Task 5.1: Integration tests — SpringBootTest with mocked ChatLanguageModel
**File:** `backend/src/test/java/com/agent/orchestrator/llm/LangChain4jIntegrationTest.java`
**Test:** (this IS the test file)
**Depends:** 2.1, 2.2, 2.3, 3.1 (all backend integration works)
**Effort:** 6 hours

Create a `@SpringBootTest` that:
- Uses `@TestConfiguration` to provide a mocked `ChatLanguageModel` and `StreamingChatLanguageModel`
- Verifies full round-trip: `AgentNodeStrategy` → `LlmService` → `LangChain4jProvider` → tool call → `ToolExecutor` → result → WebSocket event
- Tests both non-tool and tool-enabled agent nodes
- Tests verifier/review nodes that rely on JSON verdict parsing (but use LangChain4j as provider)
- Tests error paths: provider timeout, tool failure, streaming disconnect
- Does NOT require actual Neo4j — mocks the repository layer

**Mocking strategy:**
```java
@TestConfiguration
static class TestConfig {
    @Bean
    @ConditionalOnProperty(name = "axolotl.features.langchain4j", havingValue = "true")
    public ChatLanguageModel mockChatModel() {
        ChatLanguageModel mock = mock(ChatLanguageModel.class);
        when(mock.generate(any())).thenReturn(Response.from(
            AiMessage.from("Mocked response")
        ));
        return mock;
    }
}
```

**Test matrix:**

| Test | Scenario | Assertion |
|------|----------|-----------|
| `fullRoundTrip_withoutTools` | Schema with agent node, no tools | Status COMPLETED, result = expected |
| `fullRoundTrip_withTools` | Agent node with file_write tool | `ToolExecutor.execute()` called, file written |
| `streaming_deliversTokensToWS` | Verify `onToken` consumer is called for each token | Token count > 0 |
| `providerFailure_bubblesAsError` | Mock throws `RuntimeException` | Node FAILED, error message returned |
| `toolFailure_chainContinues` | Tool returns error, chain continues | Agent still completes |

**Verify:** `cd backend && mvn test -Plangchain4j -Dtest=LangChain4jIntegrationTest`

**Commit:** `test(integration): add SpringBootTest for full LangChain4j execution round-trip`

---

### Task 5.2: E2E Playwright smoke test + canary schema
**Files:**
- `frontend/e2e/langchain4j-smoke.spec.ts` (new)
- `templates/langchain4j-canary.json` (new — canary schema)
**Depends:** 3.1, 4.2 (features reachable from UI)
**Effort:** 6 hours

**Playwright smoke test:**
- Start backend with `axolotl.features.langchain4j=true`
- Navigate to Studio
- Create a schema with: Source → Agent (model `lc4j-gpt-4o`, tool: `file_write`) → Output
- Execute pipeline
- Verify:
  - Pipeline status completes (poll, no `waitForTimeout`)
  - NodeExecution output is persisted (check API)
  - WebSocket events received (`token`, `toolCall`, `complete`)
- Optionally verify a file was created by the tool call

**Canary schema (`templates/langchain4j-canary.json`):**
- Simple pipeline: Receive text → Agent (langchain4j, file_write to temp sandbox) → Output
- Tool writes to a safe temp directory (e.g., `/tmp/langchain4j-canary/`)
- Used for manual/scheduled canary runs during rollout

**Test requirements:**
- Runs against backend on `:8082` with frontend on `:5173`
- No live LangChain4j dependency — must work with mocked provider
- Poll for execution status instead of fixed timeouts

**Verify:** `cd frontend && npm run test:e2e -- --grep "langchain4j-smoke"`

**Commit:** `test(e2e): add Playwright smoke test and canary template for LangChain4j`

---

## Batch 6: Rollout (serial — 1 task)

### Task 6.1: Feature flag flip — enable LangChain4j, docs, changelog, rollout
**Files:**
- `backend/src/main/resources/application.yml` (flip default to `true`)
- `CHANGELOG.md` (add entry)
- `AGENTS.md` (document provider selection)
- `docs/` (add provider setup guide)
**Depends:** All previous batches
**Effort:** 4 hours

**Rollout steps:**
1. Merge all feature branches into a release branch
2. Verify all unit/integration/e2e tests pass in CI with profile enabled
3. Deploy to canary environment with `axolotl.features.langchain4j=true`
4. Monitor for 72 hours (metrics from monitoring plan)
5. On Go decision: flip default to `true` in `application.yml`
6. Document in `CHANGELOG.md`:
   ```
   - feat: integrate LangChain4j as optional LLM orchestration provider (#XXX)
     - New provider: "langchain4j" with lc4j- model prefix
     - Supported connectors: OpenAI, Ollama (extensible)
     - Tool adapter: LangChain4j ToolSpecification → ToolExecutor mapping
     - Streaming: token-by-token delivery via existing WebSocket contract
     - Feature-flagged: enable via axolotl.features.langchain4j=true or -Plangchain4j
   ```
7. Update `AGENTS.md` with "Choosing a Provider" section explaining when to use langchain4j vs. direct providers

**Verify:** Full CI suite passes. Canary metrics green. No regression in existing provider paths.

**Commit:** `feat: enable LangChain4j integration by default`

---

## Effort Summary

| Batch | Tasks | Total Effort | Calendar (1 dev) | Calendar (2 devs) |
|-------|-------|-------------|-------------------|-------------------|
| 1 — Foundation | 3 | 6.5h | 1 day | 0.5 day |
| 2 — Core Modules | 3 | 17h | 2.5 days | 1.5 days |
| 3 — Integration | 2 | 7h | 1 day | 0.5 day |
| 4 — Settings | 2 | 8h | 1 day | 0.5 day |
| 5 — Testing | 2 | 12h | 1.5 days | 1 day |
| 6 — Rollout | 1 | 4h | 0.5 day | 0.5 day |
| **Total** | **13** | **54.5h** | **~7 working days** | **~4 working days** |

**Spike (Batch 1 + 2.1 only):** 1 week (tasks 1.1, 1.2, 1.3, 2.1 ≈ 14.5h)
**Full integration (all batches):** 6–8 weeks allowing for review cycles, flaky test fixes, and canary monitoring

---

## CI/Test Matrix

| Task | Unit Tests | Integration Tests | E2E/Playwright | Mocking Strategy |
|------|-----------|-------------------|----------------|-------------------|
| 1.1 POM | `mvn compile -Plangchain4j` | none | none | N/A — build only |
| 1.2 Config | none (implicit) | `@SpringBootTest` with flag on/off | none | `@ConditionalOnProperty` |
| 1.3 Skeleton | `LangChain4jProviderTest` | none | none | Mockito mocks |
| 2.1 Chat/Streaming | `LangChain4jProviderTest` | none | none | Mock `ChatLanguageModel` |
| 2.2 Tool Adapter | `LangChainToolAdapterTest` | none | none | Mock `ToolExecutor` |
| 2.3 LlmService | `LlmServiceTest` | none | none | Mock `LlmProvider` |
| 3.1 Tools Wiring | `LangChain4jProviderTest` | `AgentNodeStrategyTest` | none | Mock `ChatLanguageModel` + `ToolExecutor` |
| 3.2 Streaming | `LangChain4jProviderTest` | none | none | Mock `StreamingChatLanguageModel` |
| 4.1 Settings | `SettingsControllerTest` | None (uses mocked repo) | none | Mock `SettingsService` |
| 4.2 Frontend | `SettingsView.test.ts` | none | none | `vi.mock()` for API calls |
| 5.1 Integration | — | `LangChain4jIntegrationTest` | none | `@TestConfiguration` with mocked model |
| 5.2 E2E | — | — | `langchain4j-smoke.spec.ts` | Mocked provider returns canned responses |
| 6.1 Rollout | All unit tests | All integration tests | All e2e | All mocks in place |

### CI Mocking LangChain4j

All tests avoid real network calls to LangChain4j connectors:

1. **Unit tests:** Mockito mocks for `ChatLanguageModel` and `StreamingChatLanguageModel` — no connector needed
2. **Integration tests:** `@TestConfiguration` provides mock beans that replace the real `ChatLanguageModel`
3. **E2E tests:** Backend starts with mocked provider that returns canned responses (no real LLM call)
4. **CI without profile:** `mvn test` (no `-Plangchain4j`) compiles and runs all non-LangChain4j tests normally. LangChain4j-specific tests are excluded via Maven profile activation
5. **CI with profile:** `mvn test -Plangchain4j -Dtest=LangChain4j*` runs only LangChain4j tests

**Recommended CI pipeline:**
```yaml
# Always run: all non-LangChain4j tests
- run: cd backend && mvn test

# Run LangChain4j tests only when relevant files change:
- run: cd backend && mvn test -Plangchain4j -Dtest="LangChain4j*"
  if: changes('backend/pom.xml', 'backend/src/**/langchain4j/**', 'backend/src/**/LangChain4j*')
```

---

## PR Checklist

For every PR in this plan:

### Pre-merge
- [ ] All unit tests pass: `cd backend && mvn test`
- [ ] LangChain4j tests pass with profile: `cd backend && mvn test -Plangchain4j -Dtest=LangChain4j*`
- [ ] Compilation without profile: `cd backend && mvn compile` (zero LangChain4j classes on classpath)
- [ ] Compilation with profile: `cd backend && mvn compile -Plangchain4j`
- [ ] No warnings in `mvn dependency:tree -Plangchain4j` for version conflicts
- [ ] Frontend tests pass (if applicable): `cd frontend && npm run test:unit`
- [ ] E2E tests pass (if applicable): `cd frontend && npm run test:e2e -- --grep "langchain4j"`
- [ ] Feature flag defaults to `false` (except final rollout PR)
- [ ] Code follows project conventions: no Lombok, no field injection, SLF4J logging in Russian, etc.
- [ ] Java 21 features used appropriately (records, text blocks, pattern matching)

### Post-merge
- [ ] No regression in existing provider health-check endpoints
- [ ] `GET /api/settings` still returns all providers correctly
- [ ] Canary schema can be imported and run manually
- [ ] Logs are clean (no spurious LangChain4j errors when flag is off)

### Acceptance Criteria for Final PR
- [ ] Feature flag `axolotl.features.langchain4j=true` enables the provider
- [ ] Provider name = `"langchain4j"`, model prefix = `"lc4j-"`
- [ ] Chat and streamingChat work via `LlmService.chat()`
- [ ] Tool-enabled agent nodes execute tools via `LangChainToolAdapter`
- [ ] Streaming tokens delivered to WebSocket clients
- [ ] Settings UI shows connector config (OpenAI/Ollama)
- [ ] Health check works from Settings
- [ ] Canary schema runs successfully in temp sandbox
- [ ] All existing provider paths unchanged (no behavioral regression)

---

## Rollout Plan

### Canary Phase (72 hours post-deployment)

**Metrics to monitor (Prometheus/Grafana):**

| Metric | Source | Alert Threshold |
|--------|--------|-----------------|
| Run success rate | `ExecutionRun.status` in Neo4j | < 95% over 5m window |
| Verifier FAIL rate | `NodeExecution.status` for verifier nodes | > 5pp above baseline |
| Token latency p50/p95 | Metric from `LangChain4jProvider` timings | p95 > 2x baseline |
| Tool-call error rate | ToolExecutor returns `success=false` | > 3% in 5m |
| Sandbox rejections | `validateSandboxPath` SecurityException count | > 0 (any rejection is critical) |
| Provider health failures | `LlmService.checkProviderHealth()` | > 1% in 5m |
| JVM memory | Micrometer JVM metrics | > 25% above baseline |
| Flaky test rate | CI runner | > 2% over last 10 runs |

**Go / No-Go Criteria:**

| Criterion | Go | No-Go |
|-----------|-----|-------|
| Run success rate | >= 95% (baseline) | < 93% |
| Verifier FAIL rate | <= +2pp from baseline | > +5pp |
| Token latency p95 | < 1.5x baseline | > 2x baseline |
| Tool-call error rate | <= 1% | > 3% |
| Critical data loss | Zero incidents | Any incident |
| Flaky test rate | <= 2% | > 5% |

### Rollback Steps

If No-Go criteria are hit:

1. **Toggle feature flag:** Set `axolotl.features.langchain4j=false` in production config → hot-reloads via Actuator
2. **Revert Maven profile:** Remove `-Plangchain4j` from build pipeline
3. **Reroute schemas** that were using `lc4j-` model prefix → they fall back to `resolveProvider()` default (ollama)
4. **Mark canary runs as suspect** in Neo4j (set `ExecutionRun.metadata.langchain4j_canary = "suspect"`)
5. **Investigate** — check logs for the specific failure (tool semantic mismatch, connector error, streaming regression)
6. **Hotfix** per failure type:
   - Connector failure: fix credentials/URL → redeploy with flag
   - Tool semantic mismatch: restrict tool signatures → redeploy adapter
   - Streaming regression: fall back to non-streaming `chat()` → property toggle
7. **Re-canary** with fix deployed

### Post-deployment monitoring (first 72h)
- All metrics from the table above
- Dashboard widget comparing LangChain4j runs vs all other provider runs
- Weekly review of canary run quality for 2 weeks post-rollout

---

## Risk Mapping (Design Premortem Items)

| Premortem Risk | Mitigation Task(s) | Detection Task(s) |
|---------------|-------------------|-------------------|
| **1. Tool semantic mismatch → dangerous side-effects** | 2.2 conservative sync adapter + sandbox checks | 5.1 integration test with mock tool failure |
| **2. Provider/connector availability → route failures** | 2.1 connection pooling + lazy init; 4.1 health check | 4.1 health endpoint; `preloadProviderCache` graceful handling |
| **3. Streaming regressions → UX flicker** | 3.2 adapter streaming; 5.1 integration test for token ordering | 5.2 e2e smoke test for streaming tokens; latency monitoring |
| **4. Behavioral divergence → verifier failures** | 3.1 prompt shape preserved in config passthrough | 5.1 verifier node test with LangChain4j provider |
| **5. Dependency/packaging → classpath conflicts** | 1.1 Maven profile isolation; `mvn dependency:tree` in CI | CI build step with profile |
| **6. Test coverage gaps → flaky tests** | 5.1 integration test; 5.2 Playwright e2e | CI extra runs; flakiness detection dashboard |
| **7. Performance regression → latency/memory** | 2.1 ChatLanguageModel instance reuse | Task 2.1 + 3.2 benchmark tests; canary latency monitoring |
| **8. Operational complexity → support tickets** | 4.2 Settings UI with defaults; 4.1 health check | Documentation in 6.1 rollout |

---

## Open Questions (from Design)

| Question | Decision in this Plan |
|----------|-----------------------|
| Which LangChain4j connectors first-class? | **OpenAI + Ollama** — cover cloud and local. Custom HTTP connector deferred. |
| Multi-tool chains or single tool per turn? | **Multi-tool chains** — LangChain4j natively supports this, adapter maps them safely through sandbox validation. Chain continues after tool call. |
| Enabled by default in dev builds? | **No, opt-in behind `-Plangchain4j` profile + feature flag**. Only rollout PR (6.1) flips default. |
| Tool invocation ownership? | **Keep Axolotl in control** — adapter calls `ToolExecutor` synchronously; chain must wait for result. No raw LangChain4j tool dispatch without our sandbox. |

---

## Summary

This plan delivers the LangChain4j integration in **6 batches, 13 tasks, ~54 hours total**:

- **Week 1 (Spike):** Batches 1-2 — POM, feature flag, skeleton, chat/streaming, tool adapter, LlmService registration
- **Weeks 2-3 (Integration):** Batches 3-4 — AgentNodeStrategy wiring, streaming parity, setting endpoints, frontend UI
- **Weeks 4-5 (Hardening):** Batch 5 — Integration tests, Playwright e2e, canary schema
- **Week 6 (Rollout):** Batch 6 — Feature flag flip, docs, changelog, monitoring

**Key risk-reduction measures:**
- Maven profile + feature flag prevent any classpath/startup impact when disabled
- All LangChain4j tests mock `ChatLanguageModel` — no network dependency in CI
- Tool adapter is synchronous-first with full sandbox pass-through
- Streaming tokens follow the exact same `Consumer<String>` contract as existing providers
- Canary period with explicit Go/No-Go metrics before full rollout
