# Quick Start — Generate Pipeline from Description + Live Tab Changes

**Goal:** Add a "Quick Start" button in StudioTopBar that opens a dialog for describing an app in natural language, calls LLM to generate pipeline nodes/edges, and adds them to the canvas. Simultaneously remove the Live tab, demoting execution view to an overlay within BlueprintView.

**Architecture:** Single new backend endpoint `POST /api/schemas/{id}/generate-nodes` on the existing `AgentController` that reuses `PROMPT_TO_SCHEMA_SYSTEM` but saves nodes/edges onto the existing schema (instead of creating a new orphan schema). Frontend: new `QuickStartDialog.vue`, changes to `StudioTopBar.vue` (remove Live tab, add Quick Start button), changes to `StudioView.vue` (remove Live mode, add execution overlay state), changes to `BlueprintView.vue` (render execution overlay when running). LiveView.vue itself is preserved and rendered as an overlay.

**Key decisions:**
- Design requires "single backend endpoint + frontend dialog + Live tab demotion". The endpoint reuses the existing `PROMPT_TO_SCHEMA_SYSTEM` constant but constrains the prompt to only return `{ nodes, edges }` — no name/description needed since we're updating an existing schema.
- The `generateNodes` method in SchemaService is modeled after `generateSchemaFromPrompt` (line 825) but loads the existing schema first and only extracts nodes/edges.
- QuickStartDialog uses the same modal pattern as CreateAppDialog (`var(--bg-primary)` background, `var(--border-color)` border, 12px radius).
- LiveView is NOT deleted — it's rendered as a fullscreen overlay in BlueprintView when `showExecutionOverlay` is true.
- Model selector dropdown fetches providers from `settingsApi.getProviders()` and sets `selectedModel` from the first available provider's `defaultModel` or user default.

**Design:** `thoughts/shared/designs/2026-05-14-quick-start-design.md`

---

## Dependency Graph

```
Batch 1 (parallel - 3 files):  1.1, 1.2, 1.3  [backend endpoint + frontend API - no deps]
Batch 2 (parallel - 3 files):  2.1, 2.2, 2.3  [frontend dialog + topbar + studio - deps batch 1]
Batch 3 (parallel - 2 files):  3.1, 3.2        [execution overlay + test - deps batch 2]
```

---

## Batch 1: Backend Endpoint + Frontend API (parallel — 3 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: Add `generateNodes()` to SchemaService

**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/SchemaServiceTest.java` (extend existing)
**Depends:** none

**What to change:** Add a new method `generateNodes(String schemaId, String prompt, String model)` after `generateSchemaFromPrompt()` (line 947). This method:
1. Loads the existing schema via `schemaRepository.findById(schemaId)`
2. Reuses the same `PROMPT_TO_SCHEMA_SYSTEM` but constrains the LLM to return only a `nodes` and `edges` JSON object
3. Parses the LLM response extracting nodes and edges
4. Overwrites the existing schema's nodes/edges
5. Saves via `schemaRepository.save()`
6. Returns `Map<String, Object>` with `{ success, schema }` or `{ success, error }`

**Implementation change (SchemaService.java, add after line 947, before closing brace):**

```java
    /**
     * Generate nodes and edges for an existing schema from a natural language prompt.
     * Reuses PROMPT_TO_SCHEMA_SYSTEM but constrains LLM to return only nodes/edges.
     * Saves the new nodes/edges onto the existing schema.
     */
    public Map<String, Object> generateNodes(String schemaId, String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. Load existing schema
            WorkflowSchema schema = schemaRepository.findById(schemaId);
            if (schema == null) {
                result.put("success", false);
                result.put("error", "Schema not found: " + schemaId);
                return result;
            }

            // 2. Resolve model
            String resolvedModel = model;
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = settingsService.getGlobalDefaultModel();
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = "minimax-max";
            }

            log.info("Generating nodes for schema '{}' (model={}): {}", schemaId, resolvedModel, prompt.substring(0, Math.min(100, prompt.length())));

            // 3. Build system prompt — constrain to ONLY return nodes/edges
            String nodeSystemPrompt = PROMPT_TO_SCHEMA_SYSTEM + "\n\nIMPORTANT: The schema already exists. Return ONLY a JSON object with \"nodes\" and \"edges\" fields. Do NOT include \"name\", \"description\", \"version\", \"planExplanation\", or any other top-level fields. Only the nodes and edges arrays.";

            // 4. Call LLM
            String llmResponse = llmService.chat(resolvedModel, nodeSystemPrompt, prompt, null);

            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }

            // 5. Parse JSON (strip markdown code fences if present)
            String jsonStr = llmResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            try {
                root = mapper.readTree(jsonStr);
            } catch (Exception e) {
                result.put("success", false);
                result.put("error", "Failed to parse LLM response as JSON: " + e.getMessage());
                result.put("raw", llmResponse);
                return result;
            }

            // 6. Extract nodes
            List<Node> nodes = new ArrayList<>();
            if (root.has("nodes")) {
                for (JsonNode n : root.get("nodes")) {
                    Node schemaNode = new Node();
                    schemaNode.setId(n.has("id") ? n.get("id").asText() : UUID.randomUUID().toString());
                    schemaNode.setType(n.has("type") ? n.get("type").asText() : "agent");
                    schemaNode.setName(n.has("name") ? n.get("name").asText() : "Node");
                    if (n.has("position")) {
                        Node.Position pos = new Node.Position();
                        pos.setX(n.get("position").has("x") ? n.get("position").get("x").asInt() : 100);
                        pos.setY(n.get("position").has("y") ? n.get("position").get("y").asInt() : 200);
                        schemaNode.setPosition(pos);
                    }
                    if (n.has("data")) {
                        Node.NodeData data = new Node.NodeData();
                        JsonNode d = n.get("data");
                        if (d.has("userPrompt")) data.setUserPrompt(d.get("userPrompt").asText());
                        if (d.has("systemPrompt")) data.setSystemPrompt(d.get("systemPrompt").asText());
                        if (d.has("model")) data.setModel(d.get("model").asText());
                        if (d.has("agentType")) data.setAgentType(d.get("agentType").asText());
                        if (d.has("sourceData")) data.setSourceData(d.get("sourceData").asText());
                        if (d.has("enabledTools") && d.get("enabledTools").isArray()) {
                            List<String> tools = new ArrayList<>();
                            for (JsonNode t : d.get("enabledTools")) tools.add(t.asText());
                            data.setEnabledTools(tools);
                        }
                        if (d.has("maxToolCalls")) data.setMaxToolCalls(d.get("maxToolCalls").asInt());
                        schemaNode.setData(data);
                    }
                    nodes.add(schemaNode);
                }
            }

            if (nodes.isEmpty()) {
                result.put("success", false);
                result.put("error", "No nodes generated. Try a more specific description.");
                return result;
            }

            // 7. Extract edges
            List<Edge> edges = new ArrayList<>();
            if (root.has("edges")) {
                for (JsonNode e : root.get("edges")) {
                    Edge edge = new Edge();
                    edge.setId(UUID.randomUUID().toString());
                    edge.setSource(e.has("source") ? e.get("source").asText() : "");
                    edge.setTarget(e.has("target") ? e.get("target").asText() : "");
                    edges.add(edge);
                }
            }

            // 8. Update existing schema with new nodes/edges
            schema.setNodes(nodes);
            schema.setEdges(edges);
            schema.setUpdatedAt(Instant.now().toString());
            schemaRepository.save(schema);

            log.info("Generated {} nodes and {} edges for schema '{}'", nodes.size(), edges.size(), schemaId);

            result.put("success", true);
            result.put("schema", schema);

        } catch (Exception e) {
            log.error("Failed to generate nodes for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
```

**Test (extend SchemaServiceTest.java, after existing tests at line 232):**

```java
    @Test
    void generateNodes_returnsSuccessWithSchema() {
        String schemaId = "test-schema-id";
        String prompt = "Create a calculator app";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        existingSchema.setName("Test App");
        existingSchema.setNodes(new ArrayList<>());
        existingSchema.setEdges(new ArrayList<>());

        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), eq(prompt), isNull()))
            .thenReturn("{\"nodes\":[{\"id\":\"n1\",\"type\":\"agent\",\"name\":\"Calc Agent\"}],\"edges\":[]}");

        Map<String, Object> result = schemaService.generateNodes(schemaId, prompt, null);

        assertTrue((Boolean) result.get("success"));
        assertNotNull(result.get("schema"));
        verify(schemaRepository).save(any(WorkflowSchema.class));
    }

    @Test
    void generateNodes_returnsErrorWhenSchemaNotFound() {
        String schemaId = "nonexistent";
        when(schemaRepository.findById(schemaId)).thenReturn(null);

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", "model");

        assertFalse((Boolean) result.get("success"));
        assertEquals("Schema not found: nonexistent", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorWhenEmptyResponse() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertEquals("LLM returned empty response", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorWhenNoNodes() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("{\"nodes\":[],\"edges\":[]}");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertEquals("No nodes generated. Try a more specific description.", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorOnInvalidJson() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("not json at all");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertTrue(((String) result.get("error")).startsWith("Failed to parse"));
    }
```

**Verify:** `cd backend && mvn test -Dtest=SchemaServiceTest`
**Commit:** `feat(service): add generateNodes() for in-place schema node generation`

---

### Task 1.2: Add POST /api/schemas/{id}/generate-nodes endpoint

**File:** `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/controller/AgentControllerTest.java` (NEW)
**Depends:** none (independent — only references SchemaService interface, not implementation)

**What to change:** Add a new endpoint after `generateSchemaFromPrompt` (line 329). The endpoint:
- `@PostMapping("/schemas/{id}/generate-nodes")`
- Takes `Map<String, String>` body with `prompt` and optional `model`
- Validates prompt is not blank
- Calls `schemaService.generateNodes(id, prompt, model)`
- Returns the result map directly

**Implementation change (AgentController.java, add after line 329, before closing brace):**

```java
    @PostMapping("/schemas/{id}/generate-nodes")
    public Map<String, Object> generateNodes(@PathVariable String id, @RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        String model = body.getOrDefault("model", null);

        if (prompt == null || prompt.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "prompt is required");
        }

        return schemaService.generateNodes(id, prompt, model);
    }
```

**Test (new file: `backend/src/test/java/com/agent/orchestrator/controller/AgentControllerTest.java`):**

```java
package com.agent.orchestrator.controller;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.PlanningService;
import com.agent.orchestrator.service.SchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock AgentService agentService;
    @Mock SchemaService schemaService;
    @Mock LlmService llmService;
    @Mock MemPalaceClient memPalaceClient;
    @Mock PlanningService planningService;

    AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentService, schemaService, llmService,
                memPalaceClient, planningService);
    }

    @Test
    void generateNodes_callsServiceAndReturnsResult() {
        String schemaId = "test-id";
        Map<String, String> body = new HashMap<>();
        body.put("prompt", "Create a calculator");
        body.put("model", "gpt-4");

        Map<String, Object> expected = new HashMap<>();
        expected.put("success", true);
        expected.put("schema", new Object());

        when(schemaService.generateNodes(schemaId, "Create a calculator", "gpt-4"))
            .thenReturn(expected);

        Map<String, Object> result = controller.generateNodes(schemaId, body);

        assertEquals(expected, result);
        verify(schemaService).generateNodes(schemaId, "Create a calculator", "gpt-4");
    }

    @Test
    void generateNodes_throwsWhenPromptIsBlank() {
        Map<String, String> body = new HashMap<>();
        body.put("prompt", "");

        assertThrows(ResponseStatusException.class, () -> {
            controller.generateNodes("test-id", body);
        });
    }

    @Test
    void generateNodes_callsServiceWithNullModelWhenNotProvided() {
        String schemaId = "test-id";
        Map<String, String> body = new HashMap<>();
        body.put("prompt", "Create a calculator");

        Map<String, Object> expected = new HashMap<>();
        expected.put("success", true);

        when(schemaService.generateNodes(schemaId, "Create a calculator", null))
            .thenReturn(expected);

        Map<String, Object> result = controller.generateNodes(schemaId, body);

        assertEquals(expected, result);
        verify(schemaService).generateNodes(schemaId, "Create a calculator", null);
    }

    @Test
    void generateNodes_returnsErrorFromService() {
        String schemaId = "test-id";
        Map<String, String> body = new HashMap<>();
        body.put("prompt", "Create a calculator");

        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("success", false);
        errorResult.put("error", "LLM returned empty response");

        when(schemaService.generateNodes(schemaId, "Create a calculator", null))
            .thenReturn(errorResult);

        Map<String, Object> result = controller.generateNodes(schemaId, body);

        assertFalse((Boolean) result.get("success"));
        assertEquals("LLM returned empty response", result.get("error"));
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=AgentControllerTest`
**Commit:** `feat(controller): add POST /api/schemas/{id}/generate-nodes endpoint`

---

### Task 1.3: Add `generateNodes()` method to frontend API service

**File:** `frontend/src/services/api.ts`
**Test:** none (type-only addition, verified by TypeScript compilation when used in subsequent tasks)
**Depends:** none

**What to change:** Add `generateNodes()` method to the `schemaApi` object (after `generateFromPrompt`, line 81).

**Implementation change (api.ts, after line 81, inside `schemaApi`):**

```typescript
  async generateNodes(id: string, prompt: string, model?: string): Promise<{ success: boolean; schema?: WorkflowSchema; error?: string }> {
    const response = await api.post(`/schemas/${id}/generate-nodes`, { prompt, model });
    return response.data;
  },
```

**Verify:** `cd frontend && npm run type-check` (no type errors)
**Commit:** `feat(api): add generateNodes method to schemaApi`

---

## Batch 2: Frontend Core Components (parallel — 3 implementers)

All tasks in this batch depend on Batch 1 completing (Task 1.3 for the API method). They are independent of each other.

### Task 2.1: Create QuickStartDialog.vue

**File:** `frontend/src/components/studio/QuickStartDialog.vue` (NEW)
**Test:** `frontend/src/components/studio/__tests__/QuickStartDialog.test.ts` (Task 3.2)
**Depends:** 1.3 (uses `schemaApi.generateNodes`)

**Implementation (new file: `frontend/src/components/studio/QuickStartDialog.vue`):**

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { schemaApi, settingsApi, type ProviderInfo } from '@/services/api'
import type { WorkflowSchema } from '@/types'

const props = defineProps<{
  visible: boolean
  appId: string
}>()

const emit = defineEmits<{
  close: []
  'add-to-canvas': [schema: WorkflowSchema]
}>()

const prompt = ref('')
const selectedModel = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const result = ref<WorkflowSchema | null>(null)
const providers = ref<ProviderInfo[]>([])
const generatedNodeCount = ref(0)
const generatedEdgeCount = ref(0)

// Fetch available providers on mount
providers.value = await settingsApi.getProviders()
// Set default model from first available provider
const defaultModel = providers.value.find(p => p.available)?.defaultModel
if (defaultModel) {
  selectedModel.value = defaultModel
}

// Reset state when dialog opens
watch(() => props.visible, (newVal) => {
  if (newVal) {
    prompt.value = ''
    selectedModel.value = defaultModel || ''
    loading.value = false
    error.value = null
    result.value = null
    generatedNodeCount.value = 0
    generatedEdgeCount.value = 0
  }
})

async function generate() {
  if (!prompt.value.trim()) return

  loading.value = true
  error.value = null
  result.value = null

  try {
    const response = await schemaApi.generateNodes(
      props.appId,
      prompt.value,
      selectedModel.value || undefined
    )

    if (response.success && response.schema) {
      result.value = response.schema
      generatedNodeCount.value = response.schema.nodes?.length || 0
      generatedEdgeCount.value = response.schema.edges?.length || 0
    } else {
      error.value = response.error || 'Generation failed. Try a more specific description.'
    }
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'Network error. Please try again.'
  } finally {
    loading.value = false
  }
}

function addToCanvas() {
  if (result.value) {
    emit('add-to-canvas', result.value)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && !loading.value) {
    emit('close')
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="quickstart-overlay" @click.self="!loading && emit('close')" @keydown="handleKeydown">
      <div class="quickstart-dialog">
        <!-- Header -->
        <div class="dialog-header">
          <h2 class="dialog-title">Quick Start — Describe Your App</h2>
          <button class="close-btn" @click="emit('close')" :disabled="loading" aria-label="Close">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
              <path d="M18 6L6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>

        <!-- Input area -->
        <div class="dialog-body">
          <label class="input-label" for="quickstart-prompt">Describe the pipeline you want to build:</label>
          <textarea
            id="quickstart-prompt"
            v-model="prompt"
            class="prompt-input"
            rows="4"
            placeholder="E.g. Build a Sokoban game in Python with pygame, 5 levels..."
            :disabled="loading"
          />

          <div class="model-row">
            <label class="input-label" for="quickstart-model">Model:</label>
            <select
              id="quickstart-model"
              v-model="selectedModel"
              class="model-select"
              :disabled="loading || providers.length === 0"
            >
              <option value="" disabled>Select a model</option>
              <template v-for="provider in providers" :key="provider.name">
                <option
                  v-for="model in provider.models"
                  :key="model"
                  :value="model"
                  :selected="model === selectedModel"
                >
                  {{ model }}
                </option>
              </template>
            </select>
          </div>

          <!-- Generate button -->
          <button
            class="generate-btn"
            :disabled="!prompt.trim() || loading"
            @click="generate"
          >
            <template v-if="loading">
              <span class="spinner" />
              Generating pipeline...
            </template>
            <template v-else>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <path d="M13 2L12 10H21L11 22L12 14H3L13 2Z" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              Generate Pipeline
            </template>
          </button>

          <!-- Error section -->
          <div v-if="error" class="error-section">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="10"/>
              <path d="M15 9l-6 6M9 9l6 6" stroke-linecap="round"/>
            </svg>
            {{ error }}
          </div>

          <!-- Result section -->
          <div v-if="result" class="result-section">
            <div class="result-indicator">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
                <path d="M22 11.08V12a10 10 0 11-5.93-9.14" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M22 4L12 14.01l-3-3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>Generated {{ generatedNodeCount }} node{{ generatedNodeCount !== 1 ? 's' : '' }} and {{ generatedEdgeCount }} edge{{ generatedEdgeCount !== 1 ? 's' : '' }}</span>
            </div>
            <div class="result-actions">
              <button class="add-btn" @click="addToCanvas">Add to Canvas</button>
              <button class="regenerate-btn" @click="generate" :disabled="loading">Regenerate</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.quickstart-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.quickstart-dialog {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  width: 100%;
  max-width: 560px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--border-color);
}

.dialog-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.close-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dialog-body {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.input-label {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.prompt-input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-canvas);
  color: var(--text-primary);
  font-size: 0.9rem;
  font-family: inherit;
  resize: vertical;
  min-height: 100px;
  box-sizing: border-box;
}

.prompt-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 20%, transparent);
}

.prompt-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.model-row {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.model-select {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-canvas);
  color: var(--text-primary);
  font-size: 0.85rem;
  cursor: pointer;
}

.model-select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.75rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, opacity 0.15s;
}

.generate-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.generate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-section {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.75rem;
  border-radius: 8px;
  background: color-mix(in srgb, #ef4444 10%, transparent);
  color: #ef4444;
  font-size: 0.85rem;
  line-height: 1.4;
}

.error-section svg {
  flex-shrink: 0;
  margin-top: 2px;
}

.result-section {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1rem;
  border-radius: 8px;
  background: color-mix(in srgb, #22c55e 8%, transparent);
  border: 1px solid color-mix(in srgb, #22c55e 20%, transparent);
}

.result-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: #16a34a;
  font-size: 0.9rem;
  font-weight: 500;
}

.result-actions {
  display: flex;
  gap: 0.5rem;
}

.add-btn {
  flex: 1;
  padding: 0.6rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.add-btn:hover {
  background: var(--accent-light);
}

.regenerate-btn {
  padding: 0.6rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.regenerate-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.regenerate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
```

**Verify:** `cd frontend && npm run type-check` (verify types compile)
**Commit:** `feat(ui): create QuickStartDialog.vue for AI pipeline generation`

---

### Task 2.2: Update StudioTopBar.vue — remove Live tab, add Quick Start button

**File:** `frontend/src/components/studio/StudioTopBar.vue`
**Test:** none (tested manually via type-check)
**Depends:** none

**What to change:**
1. Change `StudioMode` type to exclude `'live'`
2. Remove the `{ id: 'live', ... }` entry from the `modes` array
3. Add a "Quick Start" button between mode tabs and the Run button
4. Add `'show-quick-start': []` to `defineEmits`
5. Remove any Live tab auto-switch related concerns

**Implementation change (StudioTopBar.vue):**

Replace lines 1-20 (script section) with:

```vue
<script setup lang="ts">
type StudioMode = 'blueprint' | 'timeline'

const props = defineProps<{
  appName: string
  activeMode: StudioMode
  isRunning: boolean
}>()

const emit = defineEmits<{
  'set-mode': [mode: StudioMode]
  'toggle-run': []
  'back': []
  'show-quick-start': []
}>()

const modes: { id: StudioMode; label: string; icon: string }[] = [
  { id: 'blueprint', label: 'Blueprint', icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z' },
  { id: 'timeline', label: 'Timeline', icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z' }
]
</script>
```

Replace lines 47-62 (template right section) with:

```vue
    <div class="topbar-right">
      <button
        class="quickstart-btn"
        @click="emit('show-quick-start')"
        title="Generate pipeline from description"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
          <path d="M13 2L12 10H21L11 22L12 14H3L13 2Z" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        Quick Start
      </button>

      <button
        :class="['run-btn', { running: isRunning }]"
        @click="emit('toggle-run')"
      >
        <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
          <rect x="6" y="4" width="4" height="16" rx="1"/>
          <rect x="14" y="4" width="4" height="16" rx="1"/>
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
          <path d="M8 5v14l11-7z"/>
        </svg>
        {{ isRunning ? 'Stop' : 'Run' }}
      </button>
    </div>
```

Add styles for the quickstart button (add after the `.run-btn` styles, before `@keyframes`):

```css
.quickstart-btn {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.85rem;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: transparent;
  color: var(--accent);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  white-space: nowrap;
}

.quickstart-btn:hover {
  background: var(--accent);
  color: white;
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(ui): remove Live tab, add Quick Start button to StudioTopBar`

---

### Task 2.3: Update StudioView.vue — remove Live mode, add Quick Start handler, add execution overlay state

**File:** `frontend/src/views/StudioView.vue`
**Test:** none
**Depends:** none

**What to change:**
1. Change `StudioMode` type to `'blueprint' | 'timeline'` (remove `'live'`)
2. Remove `LiveView` import (line 10)
3. Remove the `v-else-if` block that renders LiveView (template lines 211-214)
4. Add `showExecutionOverlay` computed ref — true when `isRunning && activeMode === 'blueprint'`
5. Provide `showExecutionOverlay` to children for BlueprintView injection
6. Update auto-switch in `onComplete` handler: when timeline execution finishes, switch to `blueprint` mode instead of `live` (line 97)
7. Add Quick Start dialog state and handlers

**Implementation changes:**

**A) Script section changes:**

Replace line 10 (`import LiveView from '@/components/studio/LiveView.vue'`) — remove it.

Replace line 13:
```typescript
type StudioMode = 'blueprint' | 'live' | 'timeline'
```
with:
```typescript
type StudioMode = 'blueprint' | 'timeline'
```

After line 43 (`provide('appState', ...)`), add:
```typescript
const showExecutionOverlay = computed(() => isRunning.value && activeMode.value === 'blueprint')
provide('showExecutionOverlay', showExecutionOverlay)
```

Remove line 47 (`provide('executionError', executionError)`).

After the `execState` and `stepCounter` setup (after line 127), add Quick Start state:
```typescript
const showQuickStart = ref(false)

function onShowQuickStart() {
  showQuickStart.value = true
}

function onAddToCanvas(schema: WorkflowSchema) {
  schemaStore.updateSchema(schema)
  showQuickStart.value = false
}
```

Note: Add import for `WorkflowSchema` type — the current imports from `vue-router` at line 2. Add:
```typescript
import type { WorkflowSchema } from '@/types'
```

**B) Auto-switch change (line 96-98):**  
Replace:
```typescript
      if (activeMode.value === 'timeline') {
        activeMode.value = 'live'
      }
```
with:
```typescript
      if (activeMode.value === 'timeline') {
        activeMode.value = 'blueprint'
      }
```

This now shows the execution overlay within Blueprint when execution finishes while on Timeline.

**C) Template changes:**

Replace the `<StudioTopBar>` call (lines 197-204) to add the new event:
```vue
    <StudioTopBar
      :app-name="app?.name || 'Untitled'"
      :active-mode="activeMode"
      :is-running="isRunning"
      @set-mode="setMode"
      @toggle-run="toggleRun"
      @back="goToDashboard"
      @show-quick-start="onShowQuickStart"
    />
```

Replace the conditional rendering (lines 207-221) to remove LiveView:
```vue
    <div class="studio-content">
      <BlueprintView
        v-if="activeMode === 'blueprint'"
        :app-id="appId"
      />
      <TimelineView
        v-else-if="activeMode === 'timeline'"
        @select-block="(blockId) => {
          if (blockId === '__execution__') return
          activeMode = 'blueprint'
        }"
      />
    </div>
```

Add the QuickStartDialog after the `.studio-content` div (before closing `</div>`):
```vue
    <QuickStartDialog
      :visible="showQuickStart"
      :app-id="appId"
      @close="showQuickStart = false"
      @add-to-canvas="onAddToCanvas"
    />
```

Add the import at the top (with other imports):
```typescript
import QuickStartDialog from '@/components/studio/QuickStartDialog.vue'
```

**D) Remove unused `provide('executionError', executionError)`**

Remove line 47: `provide('executionError', executionError)`

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(studio): remove Live mode, add Quick Start handler and execution overlay state`

---

## Batch 3: Execution Overlay + Tests (parallel — 2 implementers)

### Task 3.1: Add execution overlay to BlueprintView.vue

**File:** `frontend/src/components/studio/BlueprintView.vue`
**Test:** none (rendering overlay verified by type-check and manual testing)
**Depends:** 2.3 (StudioView provides `showExecutionOverlay`)

**What to change:**
1. Inject `showExecutionOverlay` from parent
2. Add a template overlay that renders LiveView when execution is running
3. Hide palette and config panel during execution via `v-if="!showExecutionOverlay"`
4. The overlay: absolute/fullscreen positioned above the canvas with a backdrop

**Implementation changes:**

**A) Script — add imports and injection:**

After line 11 (`import BlockConfigPanel`), add:
```typescript
import LiveView from '@/components/studio/LiveView.vue'
import { inject } from 'vue'
```

Add after the `configPanelOpen` ref (line 40):
```typescript
const showExecutionOverlay = inject('showExecutionOverlay', ref(false))
```

**B) Template — wrap palette and config panel with conditional:**

Replace the palette wrapper section (lines 189-191):
```vue
    <!-- Block Palette -->
    <div v-if="!showExecutionOverlay" class="palette-wrapper">
      <BlockPalette />
    </div>
```

Replace the config panel section (lines 216-221):
```vue
    <!-- Config Panel -->
    <BlockConfigPanel
      v-if="!showExecutionOverlay && configPanelOpen && selectedBlockId"
      :block-id="selectedBlockId"
      @close="configPanelOpen = false"
    />
```

Add the execution overlay after the config panel (before closing `</div>`):
```vue
    <!-- Execution Overlay -->
    <div v-if="showExecutionOverlay" class="execution-overlay">
      <LiveView :app-id="appId" />
    </div>
```

**C) Add styles for the overlay (after existing styles):**

```css
.execution-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: var(--bg-canvas);
  overflow: hidden;
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(ui): add execution overlay to BlueprintView`

---

### Task 3.2: Add QuickStartDialog test

**File:** `frontend/src/components/studio/__tests__/QuickStartDialog.test.ts` (NEW)
**Depends:** 2.1 (QuickStartDialog.vue exists)

**Implementation (new test file):**

```typescript
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import QuickStartDialog from '../QuickStartDialog.vue'

// Mock the API module
vi.mock('@/services/api', () => ({
  schemaApi: {
    generateNodes: vi.fn(),
  },
  settingsApi: {
    getProviders: vi.fn().mockResolvedValue([
      {
        name: 'openai',
        available: true,
        baseUrl: 'https://api.openai.com',
        models: ['gpt-4', 'gpt-4o-mini'],
        defaultModel: 'gpt-4o-mini',
      },
      {
        name: 'anthropic',
        available: true,
        baseUrl: 'https://api.anthropic.com',
        models: ['claude-sonnet-4', 'claude-haiku-4'],
        defaultModel: 'claude-sonnet-4',
      },
    ]),
  },
}))

import { schemaApi } from '@/services/api'

describe('QuickStartDialog', () => {
  const baseProps = {
    visible: true,
    appId: 'test-app-id',
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders when visible is true', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    expect(wrapper.text()).toContain('Quick Start')
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('does not render when visible is false', () => {
    const wrapper = mount(QuickStartDialog, { props: { ...baseProps, visible: false } })

    expect(wrapper.find('.quickstart-overlay').exists()).toBe(false)
  })

  it('has disabled generate button when prompt is empty', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    const generateBtn = wrapper.find('.generate-btn')
    expect(generateBtn.attributes('disabled')).toBeDefined()
  })

  it('enables generate button when prompt has text', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a Sokoban game')
    const generateBtn = wrapper.find('.generate-btn')
    expect(generateBtn.attributes('disabled')).toBeUndefined()
  })

  it('calls generateNodes on generate click', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      nodes: [{ id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} }],
      edges: [],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a Sokoban game')
    await wrapper.find('.generate-btn').trigger('click')
    await flushPromises()

    expect(schemaApi.generateNodes).toHaveBeenCalledWith(
      'test-app-id',
      'Create a Sokoban game',
      undefined
    )
  })

  it('shows result section after successful generation', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      nodes: [
        { id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} },
        { id: 'n2', type: 'source', name: 'Source 1', position: { x: 100, y: 300 }, data: {} },
        { id: 'n3', type: 'output', name: 'Output 1', position: { x: 100, y: 400 }, data: {} },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'n2' },
      ],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a game')
    await wrapper.find('.generate-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Generated 3 nodes and 1 edge')
    expect(wrapper.find('.add-btn').exists()).toBe(true)
    expect(wrapper.find('.regenerate-btn').exists()).toBe(true)
  })

  it('shows error section on generation failure', async () => {
    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: false,
      error: 'LLM returned empty response',
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a game')
    await wrapper.find('.generate-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('LLM returned empty response')
  })

  it('emits add-to-canvas when Add to Canvas is clicked', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      nodes: [{ id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} }],
      edges: [],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a game')
    await wrapper.find('.generate-btn').trigger('click')
    await flushPromises()

    await wrapper.find('.add-btn').trigger('click')

    expect(wrapper.emitted('add-to-canvas')).toBeTruthy()
    expect(wrapper.emitted('add-to-canvas')![0]).toEqual([mockSchema])
  })

  it('emits close event on overlay click', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('.quickstart-overlay').trigger('click')

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('shows loading state during generation', async () => {
    // Don't resolve the promise immediately so we can see loading state
    vi.mocked(schemaApi.generateNodes).mockReturnValue(new Promise(() => {}))

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    await wrapper.find('textarea').setValue('Create a game')
    await wrapper.find('.generate-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Generating pipeline')
    expect(wrapper.find('.spinner').exists()).toBe(true)
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- --run` (or `npx vitest run`)
**Commit:** `test(ui): add QuickStartDialog unit tests`

---

## Summary of All Changes

| Task | File | Type | Lines Changed |
|------|------|------|---------------|
| 1.1 | `SchemaService.java` | Modify | +120 lines (generateNodes method) |
| 1.2 | `AgentController.java` | Modify | +10 lines (endpoint) |
| 1.3 | `api.ts` | Modify | +4 lines (generateNodes method) |
| 2.1 | `QuickStartDialog.vue` | **NEW** | ~230 lines |
| 2.2 | `StudioTopBar.vue` | Modify | ~20 lines (type, modes, button, emit) |
| 2.3 | `StudioView.vue` | Modify | ~25 lines (mode, overlay, quick start handler) |
| 3.1 | `BlueprintView.vue` | Modify | ~20 lines (overlay + conditional palette) |
| 3.2 | `QuickStartDialog.test.ts` | **NEW** | ~180 lines (tests) |
| | `AgentControllerTest.java` | **NEW** | ~120 lines (tests) |

**5 modified files + 3 new files (dialog + 2 tests). Zero new dependencies.**

---

## Verification

After all tasks complete, run:

```bash
cd backend && mvn test -Dtest=SchemaServiceTest,AgentControllerTest
cd frontend && npm run type-check && npm run test:unit -- --run
```
