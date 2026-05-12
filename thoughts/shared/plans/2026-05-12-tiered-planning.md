# Tiered Planning Implementation Plan

**Goal:** Add 3-level planning pipeline (Concept → Outline → Refine → Execute) to DesignWorkspaceUI

**Architecture:**
- Backend: new `PlanningService.java` handles LLM calls for outline (`fast` model) and refine (`medium` model). System prompts are Java string constants in the service. New endpoint `POST /api/schemas/{id}/plan` in `AgentController.java`. New field `planningModels` in `WorkflowSchema.java`.
- Frontend: new `PlanningModelsPicker.vue` overlay for selecting fast/medium models. `DesignWorkspaceUI.vue` gets 3-phase state machine. `api.ts` gets new `plan()` method. Types updated in `index.ts`.

**Design:** `thoughts/shared/designs/2026-05-12-tiered-planning-design.md`

---

## Dependency Graph

```
Batch 1 (parallel - 3 tasks): 1.1, 1.2, 1.3 [foundation - no deps]
Batch 2 (parallel - 2 tasks): 2.1, 2.2 [depends on batch 1]
Batch 3 (parallel - 2 tasks): 3.1, 3.2 [depends on batch 2]
```

---

## Batch 1: Foundation (parallel - 3 implementers)

All tasks have NO dependencies and run simultaneously. These are data/type/config changes — no business logic yet.

### Task 1.1: Add `planningModels` field to `WorkflowSchema.java`
**File:** `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java`
**Test:** none (pure POJO getter/setter)
**Depends:** none

**Implementation — add the following after the `appType` field (line 22):**

```java
    private Map<String, String> planningModels;
```

**And after the `appType` getter/setter (after line 67), add:**

```java
    public Map<String, String> getPlanningModels() { return planningModels; }
    public void setPlanningModels(Map<String, String> planningModels) { this.planningModels = planningModels; }
```

**Also add the import for `Map` if not present (line 5):**

```java
import java.util.Map;
```

**Verify:** `cd backend && mvn compile`
**Commit message:** `feat(model): add planningModels field to WorkflowSchema`

---

### Task 1.2: Update frontend types — add `PlanningModels` + `PlanRequest`/`PlanResponse` types
**File:** `frontend/src/types/index.ts`
**Test:** none (type-only)
**Depends:** none

**Implementation — add these interfaces after `WorkflowSchema` (after line 97):**

```typescript
export interface PlanningModels {
  fast: string;      // model for outline (e.g. "gpt-4o-mini")
  medium: string;    // model for refine (e.g. "deepseek-chat")
}

export interface PlanQuestion {
  id: string;
  text: string;
  defaultAnswer: string;
  options?: string[];
}

export interface PlanRequest {
  prompt: string;
  level: 'outline' | 'refine';
  model: string;
  context?: {
    outline: string;
    userEdits: string;
    answers: Record<string, string>;
  };
}

export interface PlanResponse {
  type: 'outline' | 'refine';
  content: string;
  questions?: PlanQuestion[];
}
```

**And add `planningModels` to `WorkflowSchema` (after line 92, add `planningModels?: PlanningModels;`):**

```typescript
export interface WorkflowSchema {
  id: string;
  name: string;
  description: string;
  version: string;
  nodes: FlowNode[];
  edges: FlowEdge[];
  defaultModel?: string;
  planningModels?: PlanningModels;  // <-- ADD THIS LINE
  metadata?: Record<string, any>;
  createdAt?: string;
  updatedAt?: string;
  appType?: string;
}
```

**Verify:** `cd frontend && npx vue-tsc --noEmit`
**Commit message:** `feat(types): add planning models types and PlanRequest/PlanResponse`

---

### Task 1.3: Add `plan()` and `updatePlanningModels()` to `schemaApi`
**File:** `frontend/src/services/api.ts`
**Test:** none (collaboratively tested via components)
**Depends:** none

**Implementation — add these imports at the top (after line 2):**

```typescript
import type { PlanningModels, PlanRequest, PlanResponse } from '../types';
```

**Add these methods to the `schemaApi` object (before the closing `};` after `generateFromPrompt`, at line 82):**

```typescript
  async plan(id: string, request: PlanRequest): Promise<PlanResponse> {
    const response = await api.post(`/schemas/${id}/plan`, request);
    return response.data;
  },

  async updatePlanningModels(id: string, models: PlanningModels): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningModels = models;
    return this.updateSchema(id, schema);
  },
```

**Verify:** `cd frontend && npx vue-tsc --noEmit`
**Commit message:** `feat(api): add plan() and updatePlanningModels() to schemaApi`

---

## Batch 2: Core Modules (parallel - 2 implementers)

Both tasks depend on Batch 1 types being available.

### Task 2.1: Create `PlanningService.java` with outline/refine LLM logic
**File:** `backend/src/main/java/com/agent/orchestrator/service/PlanningService.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/PlanningServiceTest.java`
**Depends:** 1.1 (uses `WorkflowSchema.getPlanningModels()`)

**Implementation — `PlanningService.java`:**

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    // ── System Prompts ─────────────────────────────────────────────
    private static final String OUTLINE_SYSTEM_PROMPT = """
            You are a planning assistant. Based on the user's prompt, create a high-level plan and ask clarifying questions.

            Respond ONLY with valid JSON (no markdown fences):
            {
              "plan": "markdown outline of the plan (3-7 bullet points)",
              "questions": [
                {
                  "id": "q1",
                  "text": "Clear question text?",
                  "defaultAnswer": "reasonable default",
                  "options": ["option1", "option2", "option3"]
                }
              ]
            }

            Include 2-4 questions that help refine the direction. Questions can have options for pick-lists or just a defaultAnswer for free text.
            """;

    private static final String REFINE_SYSTEM_PROMPT = """
            You are a design document writer. The user has:
            - An original prompt
            - A high-level outline (from a previous step)
            - Their own edits and clarifications
            - Answers to clarifying questions

            Create a detailed design document that expands the outline into a complete specification.
            Be specific, include architecture decisions, component descriptions, data flow, and implementation notes.

            Respond ONLY with valid JSON (no markdown fences):
            {
              "plan": "Complete markdown design document with sections for Overview, Architecture, Components, Data Flow, Implementation Notes"
            }
            """;

    private final LlmService llmService;
    private final Neo4jSchemaRepository schemaRepository;
    private final ObjectMapper objectMapper;

    public PlanningService(LlmService llmService, Neo4jSchemaRepository schemaRepository) {
        this.llmService = llmService;
        this.schemaRepository = schemaRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate an outline (Level 1) — single LLM call to fast model.
     */
    public Map<String, Object> generateOutline(String schemaId, String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String resolvedModel = resolveOutlineModel(schemaId, model);

            log.info("Planning outline for schema {} using model {}", schemaId, resolvedModel);

            String llmResponse = llmService.chat(resolvedModel, OUTLINE_SYSTEM_PROMPT, prompt, null);

            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }

            return parseLlmResponse(llmResponse, "outline");
        } catch (Exception e) {
            log.error("Outline generation failed for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Refine a plan (Level 2) — single LLM call to medium model.
     */
    public Map<String, Object> refinePlan(String schemaId, String prompt, String model,
                                          String outline, String userEdits, Map<String, String> answers) {
        Map<String, Object> result = new HashMap<>();
        try {
            String resolvedModel = resolveRefineModel(schemaId, model);

            // Build a detailed user prompt from the context
            String userPrompt = buildRefineUserPrompt(prompt, outline, userEdits, answers);

            log.info("Refining plan for schema {} using model {}", schemaId, resolvedModel);

            String llmResponse = llmService.chat(resolvedModel, REFINE_SYSTEM_PROMPT, userPrompt, null);

            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }

            return parseLlmResponse(llmResponse, "refine");
        } catch (Exception e) {
            log.error("Plan refinement failed for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Resolve the outline model: prefer user-specified, then schema.planningModels.fast, then schema.defaultModel.
     */
    private String resolveOutlineModel(String schemaId, String userModel) {
        if (userModel != null && !userModel.isBlank()) return userModel;
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getPlanningModels() != null) {
            String fast = schema.getPlanningModels().get("fast");
            if (fast != null && !fast.isBlank()) return fast;
        }
        if (schema != null && schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            return schema.getDefaultModel();
        }
        return "gpt-4o-mini";
    }

    /**
     * Resolve the refine model: prefer user-specified, then schema.planningModels.medium, then schema.defaultModel.
     */
    private String resolveRefineModel(String schemaId, String userModel) {
        if (userModel != null && !userModel.isBlank()) return userModel;
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getPlanningModels() != null) {
            String medium = schema.getPlanningModels().get("medium");
            if (medium != null && !medium.isBlank()) return medium;
        }
        if (schema != null && schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            return schema.getDefaultModel();
        }
        return "deepseek-chat";
    }

    private String buildRefineUserPrompt(String originalPrompt, String outline,
                                          String userEdits, Map<String, String> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Prompt\n").append(originalPrompt).append("\n\n");
        if (outline != null && !outline.isBlank()) {
            sb.append("## Current Outline\n").append(outline).append("\n\n");
        }
        if (userEdits != null && !userEdits.isBlank()) {
            sb.append("## User Edits / Feedback\n").append(userEdits).append("\n\n");
        }
        if (answers != null && !answers.isEmpty()) {
            sb.append("## Answers to Questions\n");
            for (Map.Entry<String, String> e : answers.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Please create a detailed design document based on all of the above.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLlmResponse(String raw, String type) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", type);

        try {
            String jsonStr = raw.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
            }

            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);

            String planContent = parsed.containsKey("plan") ? (String) parsed.get("plan") : jsonStr;
            result.put("content", planContent);
            result.put("success", true);

            if ("outline".equals(type) && parsed.containsKey("questions")) {
                result.put("questions", parsed.get("questions"));
            }

            if ("refine".equals(type)) {
                result.put("content", planContent);
            }

            return result;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, using raw text: {}", e.getMessage());
            result.put("success", true);
            result.put("content", raw);
            return result;
        }
    }
}
```

**Test — `PlanningServiceTest.java`:**

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanningServiceTest {

    @Mock LlmService llmService;
    @Mock Neo4jSchemaRepository schemaRepository;

    PlanningService planningService;

    @BeforeEach
    void setUp() {
        planningService = new PlanningService(llmService, schemaRepository);
    }

    @Test
    void generateOutline_returnsParsedPlan() {
        String schemaId = "test-1";
        String prompt = "Build a tower defense game";

        String mockJson = """
                {
                  "plan": "- Core game loop\\n- Tower types\\n- Enemy waves",
                  "questions": [
                    {"id": "q1", "text": "Theme?", "defaultAnswer": "medieval", "options": ["medieval", "sci-fi"]}
                  ]
                }
                """;

        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn(mockJson);

        Map<String, Object> result = planningService.generateOutline(schemaId, prompt, "gpt-4o-mini");

        assertTrue((Boolean) result.get("success"));
        assertEquals("outline", result.get("type"));
        assertNotNull(result.get("content"));
        assertNotNull(result.get("questions"));
    }

    @Test
    void generateOutline_usesPlanningModelsFromSchema() {
        String schemaId = "test-1";
        String prompt = "Build a game";

        WorkflowSchema schema = new WorkflowSchema();
        schema.setDefaultModel("gpt-4o");
        schema.setPlanningModels(Map.of("fast", "gpt-4o-mini", "medium", "deepseek-chat"));

        when(schemaRepository.findById(schemaId)).thenReturn(schema);
        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"plan\": \"ok\"}");

        Map<String, Object> result = planningService.generateOutline(schemaId, prompt, null);

        assertTrue((Boolean) result.get("success"));
        verify(llmService).chat(eq("gpt-4o-mini"), anyString(), anyString(), any());
    }

    @Test
    void generateOutline_fallsBackToDefaultModel() {
        String schemaId = "test-2";
        String prompt = "Build a game";

        WorkflowSchema schema = new WorkflowSchema();
        schema.setDefaultModel("gpt-4o");

        when(schemaRepository.findById(schemaId)).thenReturn(schema);
        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"plan\": \"ok\"}");

        Map<String, Object> result = planningService.generateOutline(schemaId, prompt, null);

        assertTrue((Boolean) result.get("success"));
        verify(llmService).chat(eq("gpt-4o"), anyString(), anyString(), any());
    }

    @Test
    void generateOutline_usesUserSpecifiedModel() {
        String schemaId = "test-1";
        String prompt = "Build a game";

        WorkflowSchema schema = new WorkflowSchema();
        schema.setPlanningModels(Map.of("fast", "gpt-4o-mini"));

        when(schemaRepository.findById(schemaId)).thenReturn(schema);
        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"plan\": \"ok\"}");

        // Even though schema has gpt-4o-mini, user-specified claude-sonnet should win
        Map<String, Object> result = planningService.generateOutline(schemaId, prompt, "claude-sonnet");

        assertTrue((Boolean) result.get("success"));
        verify(llmService).chat(eq("claude-sonnet"), anyString(), anyString(), any());
    }

    @Test
    void generateOutline_handlesNonJsonResponse() {
        String schemaId = "test-1";
        String prompt = "Build a game";

        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("Here is a plan: step 1, step 2, step 3");

        Map<String, Object> result = planningService.generateOutline(schemaId, prompt, "gpt-4o-mini");

        assertTrue((Boolean) result.get("success"));
        assertEquals("outline", result.get("type"));
        assertNotNull(result.get("content"));
        assertNull(result.get("questions")); // no questions in non-JSON
    }

    @Test
    void refinePlan_returnsParsedPlan() {
        String schemaId = "test-1";
        String prompt = "Build a tower defense game";
        String outline = "- Core game loop\n- Tower types";
        String userEdits = "Make it co-op";
        Map<String, String> answers = Map.of("q1", "medieval");

        String mockJson = """
                {
                  "plan": "# Design Document\\n\\n## Overview\\nCo-op tower defense..."
                }
                """;

        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn(mockJson);

        Map<String, Object> result = planningService.refinePlan(schemaId, prompt, "deepseek-chat",
                outline, userEdits, answers);

        assertTrue((Boolean) result.get("success"));
        assertEquals("refine", result.get("type"));
        assertNotNull(result.get("content"));
        assertNull(result.get("questions")); // refine should not have questions
    }

    @Test
    void generateOutline_handlesEmptyLlmResponse() {
        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("");

        Map<String, Object> result = planningService.generateOutline("test-1", "prompt", "gpt-4o-mini");

        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("error"));
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=PlanningServiceTest`
**Commit message:** `feat(service): add PlanningService with outline and refine LLM calls`

---

### Task 2.2: Create `PlanningModelsPicker.vue` overlay component
**File:** `frontend/src/components/live/PlanningModelsPicker.vue`
**Test:** `frontend/src/components/live/PlanningModelsPicker.test.ts`
**Depends:** 1.2 (uses `PlanningModels` type)

**Implementation — `PlanningModelsPicker.vue`:**

```vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PlanningModels } from '@/types'

const props = defineProps<{
  modelValue: PlanningModels | null
  defaultModel: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PlanningModels]
  close: []
}>()

const fastModel = ref(props.modelValue?.fast || props.defaultModel || 'gpt-4o-mini')
const mediumModel = ref(props.modelValue?.medium || props.defaultModel || 'deepseek-chat')

function save() {
  emit('update:modelValue', {
    fast: fastModel.value,
    medium: mediumModel.value,
  })
  emit('close')
}

function close() {
  emit('close')
}
</script>

<template>
  <div class="picker-overlay" @click.self="close">
    <div class="picker-modal">
      <div class="picker-header">
        <h3>Planning Models</h3>
        <button class="close-btn" @click="close">&times;</button>
      </div>
      <div class="picker-body">
        <p class="picker-hint">
          Choose which LLM models to use for each planning stage.
          Fast model generates the initial outline; Medium model refines it into a detailed plan.
        </p>
        <div class="model-field">
          <label for="fast-model">Fast Model (Outline)</label>
          <input
            id="fast-model"
            v-model="fastModel"
            type="text"
            class="model-input"
            placeholder="e.g. gpt-4o-mini"
          />
          <span class="field-desc">Used for the first draft outline. Should be cheap & fast.</span>
        </div>
        <div class="model-field">
          <label for="medium-model">Medium Model (Refine)</label>
          <input
            id="medium-model"
            v-model="mediumModel"
            type="text"
            class="model-input"
            placeholder="e.g. deepseek-chat"
          />
          <span class="field-desc">Used to refine the outline into a detailed design document.</span>
        </div>
      </div>
      <div class="picker-footer">
        <button class="cancel-btn" @click="close">Cancel</button>
        <button class="save-btn" @click="save">Save</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.picker-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.picker-modal {
  background: var(--bg-primary, #1e1e2e);
  border: 1px solid var(--border-color, #333);
  border-radius: 12px;
  width: 480px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-color, #333);
}

.picker-header h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary, #eee);
}

.close-btn {
  background: none;
  border: none;
  color: var(--text-muted, #888);
  font-size: 1.5rem;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}

.close-btn:hover {
  color: var(--text-primary, #eee);
}

.picker-body {
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.picker-hint {
  font-size: 0.8rem;
  color: var(--text-muted, #888);
  line-height: 1.5;
  margin: 0;
}

.model-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.model-field label {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary, #ccc);
}

.model-input {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color, #333);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-secondary, #2a2a3e);
  color: var(--text-primary, #eee);
  font-family: monospace;
}

.model-input:focus {
  outline: none;
  border-color: var(--accent, #6c63ff);
  box-shadow: 0 0 0 2px rgba(108, 99, 255, 0.2);
}

.field-desc {
  font-size: 0.75rem;
  color: var(--text-muted, #888);
}

.picker-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--border-color, #333);
}

.cancel-btn,
.save-btn {
  padding: 0.5rem 1rem;
  border-radius: 6px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.cancel-btn {
  background: transparent;
  border: 1px solid var(--border-color, #333);
  color: var(--text-secondary, #ccc);
}

.cancel-btn:hover {
  background: var(--accent-bg, rgba(108, 99, 255, 0.1));
  border-color: var(--accent, #6c63ff);
}

.save-btn {
  background: var(--accent, #6c63ff);
  border: none;
  color: white;
}

.save-btn:hover {
  background: var(--accent-light, #7c73ff);
}
</style>
```

**Test — `PlanningModelsPicker.test.ts`:**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PlanningModelsPicker from './PlanningModelsPicker.vue'

describe('PlanningModelsPicker', () => {
  it('renders with default values', () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    expect(wrapper.find('.picker-modal').exists()).toBe(true)
    expect(wrapper.find('h3').text()).toBe('Planning Models')
  })

  it('shows model inputs with provided values', () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: { fast: 'gpt-4o-mini', medium: 'deepseek-chat' },
        defaultModel: 'gpt-4o',
      },
    })
    const inputs = wrapper.findAll('input')
    expect(inputs.at(0)?.element.value).toBe('gpt-4o-mini')
    expect(inputs.at(1)?.element.value).toBe('deepseek-chat')
  })

  it('emits update:modelValue and close on save', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.save-btn').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual({
      fast: 'gpt-4o',
      medium: 'gpt-4o',
    })
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close on cancel', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.cancel-btn').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close on overlay click', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.picker-overlay').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t "PlanningModelsPicker"`
**Commit message:** `feat(ui): add PlanningModelsPicker overlay component`

---

## Batch 3: Integration (parallel - 2 implementers)

Both tasks depend on Batch 2.

### Task 3.1: Add `POST /api/schemas/{id}/plan` endpoint to `AgentController.java`
**File:** `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/controller/AgentControllerTest.java` (append test method)
**Depends:** 2.1 (needs `PlanningService`)

**Implementation — add `PlanningService` inject (after `MemPalaceClient` on line 29):**

```java
    private final PlanningService planningService;
```

**Update the constructor (replace the existing one around line 31):**

```java
    public AgentController(AgentService agentService, SchemaService schemaService,
                           LlmService llmService, MemPalaceClient memPalaceClient,
                           PlanningService planningService) {
        this.agentService = agentService;
        this.schemaService = schemaService;
        this.llmService = llmService;
        this.memPalaceClient = memPalaceClient;
        this.planningService = planningService;
    }
```

**Add the new endpoint before the `// === Settings ===` comment (after line 155):**

```java
    // ── Tiered Planning ────────────────────────────────────────

    @PostMapping("/schemas/{id}/plan")
    public Map<String, Object> generatePlan(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        String prompt = (String) body.get("prompt");
        String level = (String) body.get("level");
        String model = (String) body.get("model");

        if (prompt == null || prompt.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "prompt is required");
        }
        if (level == null || (!"outline".equals(level) && !"refine".equals(level))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "level must be 'outline' or 'refine'");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");

        if ("outline".equals(level)) {
            return planningService.generateOutline(id, prompt, model);
        } else {
            String outline = context != null ? (String) context.get("outline") : null;
            String userEdits = context != null ? (String) context.get("userEdits") : null;
            @SuppressWarnings("unchecked")
            Map<String, String> answers = context != null
                    ? (Map<String, String>) context.get("answers") : null;
            if (answers == null) answers = Map.of();
            return planningService.refinePlan(id, prompt, model, outline, userEdits, answers);
        }
    }
```

**Test — append to `AgentControllerTest.java` (before the closing `}`):**

```java
    @Test
    void generateOutline_callsPlanningService() throws Exception {
        when(planningService.generateOutline(eq("test-1"), eq("Build a game"), eq("gpt-4o-mini")))
            .thenReturn(Map.of("success", true, "type", "outline", "content", "- Plan"));

        mockMvc.perform(post("/api/schemas/test-1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt": "Build a game", "level": "outline", "model": "gpt-4o-mini"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.type").value("outline"));
    }

    @Test
    void refinePlan_callsPlanningService() throws Exception {
        when(planningService.refinePlan(eq("test-1"), eq("Build a game"), eq("deepseek-chat"),
                eq("- Plan"), eq("Add co-op"), anyMap()))
            .thenReturn(Map.of("success", true, "type", "refine", "content", "# Design Doc"));

        mockMvc.perform(post("/api/schemas/test-1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt": "Build a game", "level": "refine", "model": "deepseek-chat",
                     "context": {"outline": "- Plan", "userEdits": "Add co-op", "answers": {}}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.type").value("refine"));
    }

    @Test
    void generatePlan_rejectsInvalidLevel() throws Exception {
        mockMvc.perform(post("/api/schemas/test-1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt": "Build a game", "level": "invalid", "model": "gpt-4o-mini"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void generatePlan_rejectsEmptyPrompt() throws Exception {
        mockMvc.perform(post("/api/schemas/test-1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"prompt": "", "level": "outline", "model": "gpt-4o-mini"}
                    """))
            .andExpect(status().isBadRequest());
    }
```

**Note:** The test also needs a `@Mock PlanningService planningService;` field added to the test class (next to the other `@Mock` fields around line 28-31). The `@InjectMocks` will auto-wire it.

**Verify:** `cd backend && mvn test -Dtest=AgentControllerTest`
**Commit message:** `feat(api): add POST /api/schemas/{id}/plan endpoint`

---

### Task 3.2: Rewrite `DesignWorkspaceUI.vue` with three-phase planning flow
**File:** `frontend/src/components/live/DesignWorkspaceUI.vue`
**Test:** `frontend/src/components/live/DesignWorkspaceUI.test.ts`
**Depends:** 1.3 (imports `schemaApi.plan()`), 2.2 (imports `PlanningModelsPicker`)

Design requires 3-level planning flow: Concept → Outline → Refine → Execute. This is a full rewrite of the component replacing the old single-pass logic.

**Implementation — `DesignWorkspaceUI.vue`:**

```vue
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { DesignWorkspaceFile, WorkflowSchema, PlanningModels, PlanQuestion, PlanResponse } from '@/types'
import { schemaApi } from '@/services/api'
import PlanningModelsPicker from './PlanningModelsPicker.vue'

const props = defineProps<{
  appId: string
  appType: 'GAME' | 'GENERATOR'
  executionResult: any
}>()

// ── Phase & Tab State ──────────────────────────────────────

type PlanningPhase = 'concept' | 'outline' | 'refine' | 'execute'

const activeTab = ref<'concept' | 'review' | 'output'>('concept')
const conceptPrompt = ref('')
const phase = ref<PlanningPhase>('concept')
const isGenerating = ref(false)
const generationError = ref<string | null>(null)

// ── Planning State ─────────────────────────────────────────

const planningModels = ref<PlanningModels | null>(null)
const showModelPicker = ref(false)

// Outline state
const outlinePlan = ref<string | null>(null)
const questions = ref<PlanQuestion[]>([])
const questionAnswers = ref<Record<string, string>>({})

// Refine state
const refinedPlan = ref<string | null>(null)
const userEdits = ref('')

// Files from execution
const files = ref<DesignWorkspaceFile[]>([])

// ── Model Picker ───────────────────────────────────────────

async function loadPlanningModels() {
  try {
    const schema = await schemaApi.getSchema(props.appId)
    planningModels.value = schema.planningModels || null
  } catch {
    // Silently fail - defaults will be used
  }
}

async function savePlanningModels(models: PlanningModels) {
  planningModels.value = models
  await schemaApi.updatePlanningModels(props.appId, models)
}

// ── Level 1: Outline ───────────────────────────────────────

async function generateOutline() {
  const prompt = conceptPrompt.value.trim()
  if (!prompt || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    const model = planningModels.value?.fast || undefined
    const response = await schemaApi.plan(props.appId, {
      prompt,
      level: 'outline',
      model: model || '',
    })

    outlinePlan.value = response.content
    if (response.questions) {
      questions.value = response.questions
      // Initialize answers with defaults
      const answers: Record<string, string> = {}
      for (const q of response.questions) {
        answers[q.id] = q.defaultAnswer
      }
      questionAnswers.value = answers
    }

    phase.value = 'outline'
    activeTab.value = 'review'
  } catch (err: any) {
    generationError.value = err.message || 'Failed to generate outline'
    // Stay on concept tab on error — don't lose progress
  } finally {
    isGenerating.value = false
  }
}

// ── Level 2: Refine ────────────────────────────────────────

async function refinePlan() {
  if (!outlinePlan.value || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    const model = planningModels.value?.medium || undefined
    const response = await schemaApi.plan(props.appId, {
      prompt: conceptPrompt.value.trim(),
      level: 'refine',
      model: model || '',
      context: {
        outline: outlinePlan.value,
        userEdits: userEdits.value,
        answers: questionAnswers.value,
      },
    })

    refinedPlan.value = response.content
    phase.value = 'refine'
  } catch (err: any) {
    generationError.value = err.message || 'Failed to refine plan'
    // Show error but keep outline visible — don't lose progress
  } finally {
    isGenerating.value = false
  }
}

// ── Level 3: Execute ───────────────────────────────────────

async function executePlan() {
  const planContent = refinedPlan.value || outlinePlan.value
  if (!planContent || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    // Use existing generateDraft logic: write plan to sourceNode sourceData → execute schema
    const schema = await schemaApi.getSchema(props.appId)
    const sourceNode = schema.nodes?.find(n => n.type === 'source')
    if (!sourceNode) {
      throw new Error('No source node found in this schema. Add a Source node to execute the plan.')
    }
    sourceNode.data = { ...sourceNode.data, sourceData: planContent }
    await schemaApi.updateSchema(props.appId, schema)
    await schemaApi.executeSchema(props.appId)

    phase.value = 'execute'
  } catch (err: any) {
    generationError.value = err.message || 'Failed to execute plan'
  } finally {
    isGenerating.value = false
  }
}

// ── Watch execution results ────────────────────────────────

watch(() => props.executionResult, (result) => {
  if (!result || isGenerating.value === false) return

  if (result.files && Array.isArray(result.files)) {
    files.value = result.files.map((f: any) => ({
      name: f.name || 'unnamed',
      content: f.content || '',
      type: f.type || 'text/plain',
      size: f.size ?? (f.content ? new Blob([f.content]).size : undefined),
    }))
    isGenerating.value = false
    generationError.value = null
    activeTab.value = 'output'
  }
})

// ── Init ───────────────────────────────────────────────────

loadPlanningModels()

// ── Helpers ────────────────────────────────────────────────

const tabLabels: Record<string, string> = {
  concept: 'Concept',
  review: 'Review',
  output: 'Output',
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function downloadFile(file: DesignWorkspaceFile) {
  const blob = new Blob([file.content], { type: file.type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = file.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function downloadAll() {
  files.value.forEach(f => downloadFile(f))
}
</script>

<template>
  <div class="design-workspace">
    <!-- Model Picker Overlay -->
    <PlanningModelsPicker
      v-if="showModelPicker"
      :model-value="planningModels"
      :default-model="'gpt-4o'"
      @update:model-value="savePlanningModels"
      @close="showModelPicker = false"
    />

    <!-- Tabs -->
    <div class="tabs">
      <button
        v-for="tab in (['concept', 'review', 'output'] as const)"
        :key="tab"
        class="tab-btn"
        :class="{ active: activeTab === tab }"
        @click="activeTab = tab"
      >
        {{ tabLabels[tab] }}
      </button>
    </div>

    <div class="tab-content">
      <!-- ========== Concept Tab ========== -->
      <div v-if="activeTab === 'concept'" class="tab-pane concept-pane">
        <h3 class="pane-title">Describe what you'd like to build</h3>
        <textarea
          v-model="conceptPrompt"
          class="concept-textarea"
          :placeholder="appType === 'GAME' ? 'Describe your game idea...' : 'Describe what you want to generate...'"
          rows="12"
        />
        <div class="action-row">
          <button
            class="action-btn"
            :disabled="!conceptPrompt.trim() || isGenerating"
            @click="generateOutline"
          >
            <span v-if="isGenerating" class="spinner" />
            {{ isGenerating ? 'Generating Outline...' : 'Generate Draft' }}
          </button>
          <button
            class="icon-btn"
            title="Choose planning models"
            @click="showModelPicker = true"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
            </svg>
          </button>
        </div>
        <p v-if="generationError" class="error-text">{{ generationError }}</p>
        <p v-else-if="isGenerating" class="hint-text">
          Generating your plan outline...
        </p>
        <p v-else-if="!conceptPrompt" class="hint-text">
          Write a description above and click "Generate Draft" to start.
        </p>
      </div>

      <!-- ========== Review Tab ========== -->
      <div v-if="activeTab === 'review'" class="tab-pane review-pane">
        <!-- Outline phase: plan + editable questions -->
        <div v-if="outlinePlan && !refinedPlan" class="review-layout">
          <div class="review-plan">
            <h3 class="pane-title">Outline Plan</h3>
            <div class="plan-content">{{ outlinePlan }}</div>

            <!-- Questions -->
            <div v-if="questions.length > 0" class="questions-section">
              <h3 class="pane-title">Clarifying Questions</h3>
              <div class="questions-list">
                <div v-for="q in questions" :key="q.id" class="question-item">
                  <label class="question-label">{{ q.text }}</label>
                  <div v-if="q.options && q.options.length > 0" class="question-options">
                    <label
                      v-for="opt in q.options"
                      :key="opt"
                      class="option-label"
                      :class="{ selected: questionAnswers[q.id] === opt }"
                    >
                      <input
                        type="radio"
                        :name="'q-' + q.id"
                        :value="opt"
                        v-model="questionAnswers[q.id]"
                      />
                      {{ opt }}
                    </label>
                  </div>
                  <input
                    v-else
                    v-model="questionAnswers[q.id]"
                    type="text"
                    class="question-input"
                    placeholder="Your answer..."
                  />
                </div>
              </div>
            </div>
          </div>
          <div class="review-sidebar">
            <h3 class="pane-title">Feedback / Edits</h3>
            <textarea
              v-model="userEdits"
              class="critique-textarea"
              placeholder="Add your feedback, corrections, or additional requirements..."
              rows="8"
            />
            <button
              class="action-btn"
              :disabled="isGenerating"
              @click="refinePlan"
            >
              <span v-if="isGenerating" class="spinner" />
              {{ isGenerating ? 'Refining...' : 'Refine Plan' }}
            </button>
            <p v-if="generationError" class="error-text">{{ generationError }}</p>
            <p class="hint-text">
              Edit questions above and add feedback, then click "Refine Plan" to generate a detailed design document.
            </p>
          </div>
        </div>

        <!-- Refined plan phase: detailed document + execute -->
        <div v-else-if="refinedPlan" class="review-layout">
          <div class="review-plan">
            <h3 class="pane-title">Refined Design Document</h3>
            <div class="plan-content">{{ refinedPlan }}</div>
          </div>
          <div class="review-sidebar">
            <h3 class="pane-title">Ready to Execute?</h3>
            <p class="hint-text">
              The plan above will be used as source data for your workflow. Click "Execute Plan" to run it.
            </p>
            <button
              class="action-btn execute-btn"
              :disabled="isGenerating"
              @click="executePlan"
            >
              <span v-if="isGenerating" class="spinner" />
              {{ isGenerating ? 'Executing...' : 'Execute Plan' }}
            </button>
            <button
              class="action-btn secondary-btn"
              :disabled="isGenerating"
              @click="refinedPlan = null; userEdits = ''"
            >
              Back to Outline
            </button>
            <p v-if="generationError" class="error-text">{{ generationError }}</p>
          </div>
        </div>

        <!-- Empty state -->
        <div v-else class="empty-state">
          <p>Generate a draft from the <strong>Concept</strong> tab to see results here.</p>
        </div>
      </div>

      <!-- ========== Output Tab ========== -->
      <div v-if="activeTab === 'output'" class="tab-pane output-pane">
        <div v-if="files.length === 0" class="empty-state">
          <p>No files generated yet. Refine your plan and execute it from the <strong>Review</strong> tab.</p>
        </div>
        <div v-else class="output-content">
          <div class="output-header">
            <h3 class="pane-title">Generated Files ({{ files.length }})</h3>
            <button
              v-if="files.length > 1"
              class="action-btn secondary-btn"
              @click="downloadAll"
            >
              Download All
            </button>
          </div>
          <div class="file-list">
            <div
              v-for="(file, index) in files"
              :key="index"
              class="file-item"
            >
              <div class="file-info">
                <svg class="file-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                </svg>
                <span class="file-name">{{ file.name }}</span>
                <span v-if="file.size !== undefined" class="file-size">{{ formatSize(file.size) }}</span>
              </div>
              <button class="download-btn" @click="downloadFile(file)" title="Download">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                  <polyline points="7 10 12 15 17 10"/>
                  <line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.design-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

/* Tabs */
.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border-color);
  padding: 0 1rem;
  flex-shrink: 0;
}

.tab-btn {
  padding: 0.75rem 1.25rem;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}

.tab-btn:hover {
  color: var(--text-secondary);
  background: var(--accent-bg);
}

.tab-btn.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.tab-content {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
}

.tab-pane {
  height: 100%;
}

.pane-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

/* Concept tab */
.concept-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
  min-height: 200px;
  box-sizing: border-box;
}

.concept-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.action-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-secondary);
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s;
  padding: 0;
}

.icon-btn:hover {
  background: var(--accent-bg);
  border-color: var(--accent);
  color: var(--accent);
}

/* Review tab */
.review-layout {
  display: flex;
  gap: 1.5rem;
  height: 100%;
}

.review-plan {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.review-sidebar {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.plan-content {
  flex: 1;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
  color: var(--text-primary);
  font-family: monospace;
  min-height: 200px;
  max-height: 500px;
}

/* Questions */
.questions-section {
  margin-top: 1rem;
}

.questions-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.question-item {
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.question-label {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.option-label {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.8rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s;
  background: var(--bg-primary);
}

.option-label:hover {
  border-color: var(--accent);
}

.option-label.selected {
  border-color: var(--accent);
  background: var(--accent-bg);
  color: var(--accent);
}

.option-label input {
  display: none;
}

.question-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: inherit;
  box-sizing: border-box;
}

.question-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

/* Critique textarea */
.critique-textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
  min-height: 120px;
  box-sizing: border-box;
  width: 100%;
}

.critique-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

/* Output tab */
.output-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  transition: border-color 0.15s;
}

.file-item:hover {
  border-color: var(--accent);
}

.file-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.file-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.file-name {
  font-size: 0.85rem;
  color: var(--text-primary);
  font-family: monospace;
}

.file-size {
  font-size: 0.75rem;
  color: var(--text-muted);
  font-family: monospace;
}

.download-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-primary);
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
  transition: all 0.15s;
}

.download-btn:hover {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

/* Action buttons */
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  align-self: flex-start;
  transition: background 0.15s;
  white-space: nowrap;
}

.action-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.execute-btn {
  background: #22c55e;
}

.execute-btn:hover:not(:disabled) {
  background: #16a34a;
}

.secondary-btn {
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.secondary-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: var(--accent);
  color: var(--text-primary);
}

/* Empty state */
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
  min-height: 200px;
}

/* Error text */
.error-text {
  font-size: 0.8rem;
  color: var(--danger);
  margin-top: 0.5rem;
  line-height: 1.5;
  padding: 0.5rem 0.75rem;
  background: var(--danger-bg);
  border-radius: 6px;
  border: 1px solid var(--danger);
}

/* Spinner */
.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Hint text */
.hint-text {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.5rem;
  line-height: 1.5;
}
</style>
```

**Test — `DesignWorkspaceUI.test.ts` (full rewrite):**

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import DesignWorkspaceUI from './DesignWorkspaceUI.vue'

vi.mock('@/services/api', () => ({
  schemaApi: {
    getSchema: vi.fn(),
    updateSchema: vi.fn(),
    executeSchema: vi.fn(),
    plan: vi.fn(),
    updatePlanningModels: vi.fn(),
  }
}))

import { schemaApi } from '@/services/api'

const DEFAULT_PROPS = {
  appId: 'test-app-1',
  appType: 'GAME' as const,
  executionResult: null,
}

describe('DesignWorkspaceUI', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(schemaApi.getSchema).mockResolvedValue({
      id: 'test-app-1',
      nodes: [],
      planningModels: null,
    } as any)
  })

  it('renders with correct props', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    expect(wrapper.exists()).toBe(true)
  })

  it('shows Concept tab by default', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const conceptBtn = wrapper.findAll('.tab-btn').at(0)
    expect(conceptBtn?.text()).toContain('Concept')
    expect(conceptBtn?.classes()).toContain('active')
  })

  it('disables Generate Draft when prompt is empty', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeDefined()
  })

  it('enables Generate Draft when prompt is filled', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a platformer game')
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeUndefined()
  })

  it('calls schemaApi.plan on Generate Draft', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan',
      questions: [{ id: 'q1', text: 'Theme?', defaultAnswer: 'fantasy' }],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a platformer game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()

    expect(schemaApi.plan).toHaveBeenCalledWith('test-app-1', {
      prompt: 'Create a platformer game',
      level: 'outline',
      model: '',
    })
  })

  it('shows outline in Review tab after generation', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan\n- More items',
      questions: [],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.plan-content').exists()).toBe(true)
    expect(wrapper.find('.plan-content').text()).toContain('Game plan')
  })

  it('shows clarifyng questions with editable fields', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan',
      questions: [
        { id: 'q1', text: 'Theme?', defaultAnswer: 'fantasy', options: ['fantasy', 'sci-fi'] },
        { id: 'q2', text: 'Difficulty?', defaultAnswer: 'medium' },
      ],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Should show option labels for q1 (radio-style)
    const optionLabels = wrapper.findAll('.option-label')
    expect(optionLabels.length).toBe(2) // fantasy, sci-fi

    // Should show text input for q2
    const textInputs = wrapper.findAll('.question-input')
    expect(textInputs.length).toBe(1)
  })

  it('calls schemaApi.plan on Refine Plan with context', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValueOnce({
      type: 'outline',
      content: '- Outline plan',
      questions: [],
    }).mockResolvedValueOnce({
      type: 'refine',
      content: '# Detailed Design',
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    // Generate outline
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Click Refine Plan
    const refineBtn = wrapper.findAll('button').find(b => b.text().includes('Refine Plan'))
    await refineBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(schemaApi.plan).toHaveBeenCalledTimes(2)
    expect(schemaApi.plan).toHaveBeenLastCalledWith('test-app-1', expect.objectContaining({
      level: 'refine',
      context: expect.objectContaining({
        outline: '- Outline plan',
      }),
    }))
  })

  it('calls executePlan flow: getSchema → updateSchema → executeSchema', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValueOnce({
      type: 'outline',
      content: '- Outline',
      questions: [],
    }).mockResolvedValueOnce({
      type: 'refine',
      content: '# Refined plan with details',
    })

    const mockSchema = {
      id: 'test-app-1',
      nodes: [{ id: 'src-1', type: 'source', data: { sourceData: '' } }],
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    // Step 1: Generate outline
    const allBtns1 = wrapper.findAll('button')
    const generateBtn = allBtns1.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Step 2: Refine
    const allBtns2 = wrapper.findAll('button')
    const refineBtn = allBtns2.find(b => b.text().includes('Refine Plan'))
    await refineBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Step 3: Execute
    const allBtns3 = wrapper.findAll('button')
    const executeBtn = allBtns3.find(b => b.text().includes('Execute Plan'))
    await executeBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(schemaApi.getSchema).toHaveBeenCalledWith('test-app-1')
    expect(schemaApi.updateSchema).toHaveBeenCalled()
    expect(schemaApi.executeSchema).toHaveBeenCalledWith('test-app-1')
  })

  it('shows generated files when executionResult arrives', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })

    await wrapper.setProps({
      executionResult: {
        files: [
          { name: 'game.html', content: '<html></html>', type: 'text/html' },
        ],
      },
    })

    const fileItems = wrapper.findAll('.file-item')
    expect(fileItems.length).toBe(1)
    expect(fileItems.at(0)?.text()).toContain('game.html')
  })

  it('shows settings icon button in Concept tab', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const iconBtn = wrapper.find('.icon-btn')
    expect(iconBtn.exists()).toBe(true)
  })

  it('shows PlanningModelsPicker when settings icon is clicked', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('.icon-btn').trigger('click')
    expect(wrapper.findComponent({ name: 'PlanningModelsPicker' }).exists()).toBe(true)
  })

  it('shows error on failed outline generation', async () => {
    vi.mocked(schemaApi.plan).mockRejectedValue(new Error('API timeout'))

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const errorText = wrapper.find('.error-text')
    expect(errorText.exists()).toBe(true)
    expect(errorText.text()).toContain('API timeout')
  })

  it('renders for GENERATOR app type', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { ...DEFAULT_PROPS, appType: 'GENERATOR' },
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.design-workspace').exists()).toBe(true)
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t "DesignWorkspaceUI"`
**Commit message:** `feat(ui): implement 3-phase planning flow (outline → refine → execute)`

---

## Summary

| Batch | Tasks | Description | Parallel |
|-------|-------|-------------|----------|
| 1 | 3 | Foundation (model field, types, api.ts) | ✅ 3 implementers |
| 2 | 2 | Core (PlanningService, PlanningModelsPicker) | ✅ 2 implementers |
| 3 | 2 | Integration (controller endpoint, DesignWorkspaceUI) | ✅ 2 implementers |
| **Total** | **7** | **All files + tests** | **7 implementers across 3 waves** |

### Key Design Decisions
1. **System prompts** live in `PlanningService.java` as string constants (not resource files) — simpler, co-located with the logic, easy to iterate
2. **Model resolution** cascades: user-specified → schema.planningModels → schema.defaultModel → hardcoded fallback
3. **Level 3 (Execute)** reuses existing `generateDraft()` flow via sourceData update — no changes to execution engine
4. **Error resilience**: outline failure stays on Concept tab with error, refine failure keeps outline visible, non-JSON LLM responses display as raw text
5. **Questions** render as radio buttons (when options present) or text inputs — both are editable
