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

        when(llmService.chat(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"plan\": \"ok\"}");

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
        assertNull(result.get("questions"));
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
        assertNull(result.get("questions"));
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
