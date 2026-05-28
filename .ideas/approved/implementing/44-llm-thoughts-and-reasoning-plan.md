# LLM Thoughts & Reasoning — Axolotl Implementation Plan

**Status:** Planned  
**Priority:** Medium  
**Theme:** Provider Diversity / Observability  
**Dependencies:** LangChain4j core integration (43-langchain4j-integration-plan — must be done first, as all providers now use LangChain4j and need to be adjusted for reasoning extraction)

---

## Problem

Many LLM providers return a `reasoning_content` (or equivalent) field alongside the visible response — chain-of-thought from DeepSeek-R1, o1/o3 reasoning tokens from OpenAI, etc. Currently Axolotl **drops this data** entirely:

- `LlmProvider.chat()` returns `String` (text-only) — no way to pass reasoning
- LangChain4j 1.0.0-beta2 `AiMessage` has no `reasoning` field — the raw JSON `reasoning_content` is consumed and discarded by `OpenAiChatModel` before creating `AiMessage`
- Only `CustomLlmProvider`'s raw HTTP fallback reads `reasoning_content` — and only as a secondary content source, not as a distinct output

## Goal

Capture LLM reasoning/thoughts as a **separate, optional artifact** alongside the main response. Reasoning is:

- **Never mixed into the main text feed** — the normal output flow is unchanged
- **Persisted per node execution** — stored in Neo4j alongside `outputSummary`
- **Viewable on demand** — user accesses it via node interaction (thought bubble icon → panel)
- **Provider-specific** — only providers that expose reasoning (`reasoning_content` in OpenAI-compatible APIs) support it; others return `null`

---

## Architecture

### Data Layer

```java
// New record — replaces String as LlmProvider.chat() return type
public record LlmResponse(String text, String reasoning) {
    public static LlmResponse textOnly(String text) {
        return new LlmResponse(text, null);
    }
    public static LlmResponse withReasoning(String text, String reasoning) {
        return new LlmResponse(text, reasoning);
    }
}
```

```java
// NodeExecution gets a new field
@NodeEntity
public class NodeExecution {
    // ... existing fields ...
    private String reasoning; // optional, stored in Neo4j, null if no reasoning
}
```

### Provider Extraction Strategy

| Provider | Reasoning extraction | Method |
|----------|-------------------|--------|
| OpenAI | `reasoning_content` in `/chat/completions` response | Custom: use `DefaultOpenAiClient` to get raw `ChatCompletionResponse`, extract `reasoning_content` alongside `content` |
| DeepSeek | Same (OpenAI-compatible API) | Same as OpenAI |
| Zen/OpenCode | Same (OpenAI-compatible API) | Same as OpenAI |
| Custom | Same (OpenAI-compatible API) | Same as OpenAI, inherits the same approach |
| Anthropic | No reasoning field in standard API | `null` reasoning |
| Ollama | No reasoning field in standard API | `null` reasoning |

For OpenAI-compatible providers, LangChain4j's `OpenAiChatModel.chat()` internally parses the JSON response into `ChatCompletionResponse` → `ChatCompletionChoice` → `AssistantMessage`, then converts to `AiMessage`. The `reasoning_content` field is not mapped anywhere in this chain.

**Solution:** Use LangChain4j's own `DefaultOpenAiClient` directly instead of `OpenAiChatModel.chat()`. This gives us access to the raw `ChatCompletionResponse` JSON, from which we extract both `content` and `reasoning_content`. The `DefaultOpenAiClient` reuses the same builder configuration (baseUrl, apiKey, timeout) that we already set up.

```java
// Pattern for OpenAI-compatible providers
DefaultOpenAiClient client = DefaultOpenAiClient.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .build();

ChatCompletionRequest request = ChatCompletionRequest.builder()
    .model(modelName)
    .messages(List.of(systemMsg, userMsg))
    .build();

ChatCompletionResponse raw = client.chatCompletion(request).execute();

String content = raw.choices().get(0).message().content();
String reasoning = raw.choices().get(0).message().reasoningContent(); // TODO: check if AssistantMessage has this

// If AssistantMessage doesn't have reasoningContent():
// Access via raw JSON using ObjectMapper
ObjectMapper mapper = new ObjectMapper();
JsonNode root = mapper.readTree(raw.toString()); // or from the HTTP response directly
String reasoning = root.at("/choices/0/message/reasoning_content").asText(null);
```

For streaming: `OpenAiStreamingChatModel` uses SSE events. The `delta` in streaming SSE events can include `reasoning_content` alongside `content`. Since `StreamingChatResponseHandler.onPartialResponse()` only receives merged `content`, we use a **custom interceptor**:

1. Wrap the HTTP response stream to capture raw SSE lines
2. When `data: {"choices":[{"delta":{"reasoning_content":"..."}}]}` is received, store reasoning tokens separately
3. Forward `content` tokens to the normal streaming handler unchanged

### Execution Layer

When `NodeRouter`/`NodeExecutor` receives an `LlmResponse` from the provider:

```java
LlmResponse response = llmService.chat(...);

// Save outputSummary as before (text only)
nodeExecution.setOutputSummary(response.text());

// Save reasoning separately (if present)
if (response.reasoning() != null) {
    nodeExecution.setReasoning(response.reasoning());
}
```

Both fields are persisted to Neo4j via the existing `NodeExecution` save flow.

### API Layer

New endpoint to fetch reasoning for a specific node execution:

```http
GET /api/schemas/{schemaId}/runs/{runId}/nodes/{nodeExecutionId}/reasoning
→ 200 { "reasoning": "..." }
→ 404 (no reasoning available)
```

Including `reasoning` in the existing `GET /api/schemas/{id}/runs/{runId}/nodes` response (as a nullable field on each NodeExecution) is an alternative that avoids extra API calls.

### Frontend

**Two-tier access** — reasoning is never shown in the main streaming feed or node output:

1. **Thought bubble indicator** — A small inline SVG (24×24, thought bubble icon) appears on the node border in the canvas when `reasoning` is non-null for the latest execution:
   - Rendered as an overlay badge on the `AgentNode.vue` component
   - Also shown in `TimelineEntry.vue` for nodes with reasoning
   - Positioned discreetly (top-right corner of the node, outside the content area)

2. **Rich-click side panel** — Clicking the thought bubble opens a slide-out panel (`ThoughtsPanel.vue`):
   - Displays reasoning text in a monospace, read-only view
   - Has a "Copy" button
   - Styled with muted background to distinguish from main content
   - Panel width: ~50% of screen, overlay on the right
   - Also accessible via right-click context menu on the node

```
┌──────────────────────┐
│   Agent Node         │  💭  ← thought bubble badge
│   [visible output]   │         (shown only when reasoning exists)
│                      │
└──────────────────────┘
         ┃ click 💭
         ▼
┌─────────────────────────────┐
│  LLM Thoughts                │
│  ─────────────────────────── │
│                              │
│  "The user wants X, but      │
│   constraint Y says Z,       │
│   so I'll handle it by...    │
│   [step-by-step reasoning    │
│    the model used internally]"│
│                              │
│  [Copy]                      │
└─────────────────────────────┘
```

**Streaming behavior:** During live execution, reasoning tokens ARE streamed to the frontend via WebSocket, but they are stored in a separate `reasoningBuffer` (not mixed into `outputText`). When execution completes, if the buffer has content, the thought bubble appears. During streaming, a subtle pulsing indicator on the node shows "thinking..." without revealing content until the user clicks.

---

## Implementation Tasks

### Batch 1 — Data Model + Interface (Backend)

| # | Task | File | Description |
|---|------|------|-------------|
| 1.1 | Create `LlmResponse` record | `backend/src/main/java/com/agent/orchestrator/llm/LlmResponse.java` | `record LlmResponse(String text, String reasoning)` with static factories |
| 1.2 | Update `LlmProvider.chat()` return type | `LlmProvider.java` | Change `String` → `LlmResponse`. Update JavaDoc. |
| 1.3 | Fix `streamingChat()` default | `LlmProvider.java` | Update default impl to return `LlmResponse.textOnly(response)` |
| 1.4 | Update `LlmService.chat()` return type | `LlmService.java` | Propagate `LlmResponse` through LlmService. All internal LlmService methods that previously returned `String` now return `LlmResponse`. |

### Batch 2 — Provider Adaptations (Backend)

| # | Task | File | Description |
|---|------|------|-------------|
| 2.1 | Update `OpenAiProvider.chat()` | `OpenAiProvider.java` | Replace `OpenAiChatModel.chat()` with `DefaultOpenAiClient` to access raw `ChatCompletionResponse`. Extract `content` and `reasoning_content` from JSON. Return `LlmResponse`. Keep `OpenAiStreamingChatModel` for streaming but add raw SSE reasoning extraction. |
| 2.2 | Update `DeepSeekProvider.chat()` | `DeepSeekProvider.java` | Same pattern: `DefaultOpenAiClient` with custom `baseUrl`. |
| 2.3 | Update `OpencodeZenProvider.chat()` | `OpencodeZenProvider.java` | Same pattern: `DefaultOpenAiClient` with custom `baseUrl`. |
| 2.4 | Update `CustomLlmProvider.chat()` | `CustomLlmProvider.java` | Add reasoning extraction to both LangChain4j path and raw HTTP fallback. |
| 2.5 | Update `AnthropicProvider.chat()` | `AnthropicProvider.java` | Return `LlmResponse.textOnly(text)` — no reasoning support yet. |
| 2.6 | Update `OllamaProvider.chat()` | `OllamaProvider.java` | Return `LlmResponse.textOnly(text)` — no reasoning support yet. |
| 2.7 | Update provider tests | Various `*Test.java` | Adjust test assertions for `LlmResponse` return type. Add reasoning extraction tests for OpenAI-compatible providers. |

### Batch 3 — Streaming Reasoning Extraction (Backend)

| # | Task | File | Description |
|---|------|------|-------------|
| 3.1 | Create SSE interceptor for reasoning | New: `backend/src/main/java/com/agent/orchestrator/llm/ReasoningSseInterceptor.java` | Intercepts raw SSE lines from `OpenAiStreamingChatModel` HTTP response. Captures `delta.reasoning_content` tokens separately from `delta.content` tokens. Provides method `getReasoning()` after stream completes. |
| 3.2 | Integrate interceptor into streaming providers | `OpenAiProvider.java`, `DeepSeekProvider.java`, `OpencodeZenProvider.java` | Wrap the `StreamingChatResponseHandler` to also feed SSE data through `ReasoningSseInterceptor`. |
| 3.3 | Include reasoning in streaming WebSocket events | `ExecutionWebSocketHandler.java` | Add optional `reasoning` field to streaming events sent via WebSocket. Frontend stores in `reasoningBuffer` instead of `outputText`. |

### Batch 4 — Persistence (Backend)

| # | Task | File | Description |
|---|------|------|-------------|
| 4.1 | Add `reasoning` field to `NodeExecution` | `NodeExecution.java` | `private String reasoning;` — nullable, stored as Neo4j node property |
| 4.2 | Update `NodeRouter` to save reasoning | `NodeRouter.java` | After `llmService.chat()` returns `LlmResponse`, if `reasoning != null`, set on `NodeExecution` before persisting |
| 4.3 | Update `NodeExecution` repository queries | `Neo4jNodeExecutionRepository.java` | Include `reasoning` in result fields where needed |
| 4.4 | Add reasoning API endpoint | `AgentController.java` (or `ExecutionController.java`) | `GET /api/schemas/{id}/runs/{runId}/nodes/{nodeId}/reasoning` |

### Batch 5 — Frontend Display

| # | Task | File | Description |
|---|------|------|-------------|
| 5.1 | Create thought bubble SVG icon | `frontend/src/assets/icons/thought-bubble.svg` | 24×24 inline SVG, stroke-based (no emoji per project constraint) |
| 5.2 | Add reasoning state to node data | `schemaStore.ts` or `ExecutionState` | Track `reasoning: string | null` per node |
| 5.3 | Add thought bubble to AgentNode | `AgentNode.vue` | Show thought bubble badge when `reasoning` is non-null. Click emits event to open panel. |
| 5.4 | Create `ThoughtsPanel.vue` | New: `frontend/src/components/studio/ThoughtsPanel.vue` | Slide-out panel with monospace reasoning text, copy button, close button |
| 5.5 | Wire panel to StudioView | `StudioView.vue` | State management for panel visibility, pass reasoning text, handle close |
| 5.6 | Add thought bubble to Timeline | `TimelineEntry.vue` | Show thought icon in timeline rows for nodes with reasoning |
| 5.7 | Handle streaming reasoning in WS handler | `useWebSocket.ts` | Separate `reasoning_buffer` from `output_text` in streaming state, populate `reasoning` field on completion |
| 5.8 | Add "thinking" pulse indicator | `AgentNode.vue` | Subtle pulsing icon during streaming when reasoning tokens are arriving (before completion) |

### Batch 6 — Tests

| # | Task | Description |
|---|------|-------------|
| 6.1 | `LlmResponse` unit tests | Creation, equality, null handling |
| 6.2 | Provider reasoning extraction tests | Mock HTTP responses with `reasoning_content`, verify `LlmResponse.reasoning()` is extracted correctly |
| 6.3 | Streaming reasoning interceptor test | Simulate SSE lines with reasoning tokens, verify interceptor captures them |
| 6.4 | `NodeRouter` reasoning persistence test | After `chat()` returns `LlmResponse.withReasoning(...)`, verify `NodeExecution.reasoning` is set and persisted |
| 6.5 | API endpoint test | `GET /reasoning` returns expected content |
| 6.6 | Frontend unit test | `ThoughtsPanel.vue` renders reasoning, copy button works |
| 6.7 | E2E test | Create schema with reasoning-capable model, execute, click thought bubble, verify panel content |

---

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `DefaultOpenAiClient` is an internal LangChain4j class that may change in future versions | Medium | High — extraction code breaks on upgrade | Test with specific LC4j version in CI. Pin version in pom.xml. If LC4j adds native `reasoning` support to `AiMessage` in a future version, migrate away from `DefaultOpenAiClient`. |
| `reasoning_content` field name differs across OpenAI-compatible providers | Medium | Low — missing field = null reasoning | Use `ObjectMapper` to tolerate missing fields gracefully. Log at `DEBUG` when field is absent. |
| Streaming reasoning SSE parsing is fragile | Medium | Medium — incorrect interleaving of content/reasoning | Build with robust line-based SSE parsing. Test with real API recordings. Fall back to reasoning=null on parse error. |
| Anthropic adds reasoning field later | Low | Low — easy to add | Will be handled as a separate task when/if Anthropic releases it. |
| Reasoning content can be very long (chain-of-thought) | Low | Low — storage bloat | Neo4j handles large text fields. No indexing on `reasoning`. Consider truncation at 100KB if needed. |
| LangChain4j 1.0.0-beta2 HTTP client doesn't expose raw response | Low | High — blocking for non-streaming extraction | Alternative: make the raw HTTP call ourselves using `HttpClient` (same as CustomLlmProvider fallback). Reuses existing builder config. |

---

## Migration Notes

- The change to `LlmProvider.chat()` return type (`String` → `LlmResponse`) will break compilation of any external code implementing the interface. All internal providers are updated in Batch 2.
- `LlmService.chat()` returns `LlmResponse`. Existing callers that only need text call `.text()` on the result — no logic change, just a field access change.
- Existing persisted `NodeExecution` records will have `reasoning = null` — no migration needed.
- No changes to the pipeline or node execution orchestration logic. Reasoning is purely additive.

---

## QA Steps (Manual)

1. **Non-reasoning provider**: Run a schema using an Ollama model. Verify execution completes normally, no thought bubble appears on nodes.
2. **Reasoning provider (non-streaming)**: Run a schema using DeepSeek model (returns `reasoning_content`). After completion, click the thought bubble on the Agent node. Verify panel shows chain-of-thought text.
3. **Reasoning provider (streaming)**: Run the same schema with streaming enabled. Verify during execution: (a) main text appears in the output area as normal, (b) thought bubble is NOT shown until streaming completes, (c) after completion, thought bubble appears and shows full reasoning.
4. **Timeline access**: After execution, navigate to Timeline. Find the node entry. Verify thought bubble icon appears. Click to verify reasoning shows.
5. **Copy**: Open ThoughtsPanel, click Copy. Verify reasoning text is clipboard-accessible.
6. **Persistence**: Refresh the page. Navigate to the same schema. Verify thought bubble is still present without re-executing (reasoning loaded from Neo4j).
7. **API**: `curl -H "$CURL_HEADER" http://localhost:8082/api/schemas/{id}/runs/{runId}/nodes/{nodeId}/reasoning` returns JSON with `reasoning` field.

