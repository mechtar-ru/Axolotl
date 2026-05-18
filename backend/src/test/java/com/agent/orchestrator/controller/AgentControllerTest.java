package com.agent.orchestrator.controller;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.PlanningService;
import com.agent.orchestrator.service.SchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock SchemaService schemaService;
    @Mock AgentService agentService;
    @Mock LlmService llmService;
    @Mock MemPalaceClient memPalaceClient;
    @Mock PlanningService planningService;

    @InjectMocks AgentController controller;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void health_returnsOk() throws Exception {
        when(llmService.isProviderAvailable("ollama")).thenReturn(true);

        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.ollama").value(true));
    }

    @Test
    void getAllSchemas_returnsList() throws Exception {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("1");
        schema.setName("Test");
        when(schemaService.getSchemasByUserId(null)).thenReturn(List.of(schema));

        mockMvc.perform(get("/api/schemas"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("1"))
            .andExpect(jsonPath("$[0].name").value("Test"));
    }

    @Test
    void getProviders_returnsList() throws Exception {
        when(llmService.getProvidersInfo()).thenReturn(List.of(
            Map.of("name", "ollama", "available", true, "baseUrl", "http://localhost:11434", "models", List.of())
        ));

        mockMvc.perform(get("/api/settings/providers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("ollama"));
    }

    @Test
    void executeSchema_returnsStarted() throws Exception {
        mockMvc.perform(post("/api/schemas/test-id/execute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("started"))
            .andExpect(jsonPath("$.schemaId").value("test-id"));
    }

    @Test
    void stopSchema_returnsStopped() throws Exception {
        mockMvc.perform(post("/api/schemas/test-id/stop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("stopped"));
    }

    @Test
    void getOutputFile_returnsContent() throws Exception {
        when(schemaService.getOutputFileContent("schema-1", "node-1")).thenReturn("file content here");

        mockMvc.perform(get("/api/outputs/schema-1/node-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("file content here"));
    }

    @Test
    void getOutputFile_notFound() throws Exception {
        when(schemaService.getOutputFileContent("schema-1", "node-1")).thenReturn(null);

        mockMvc.perform(get("/api/outputs/schema-1/node-1"))
            .andExpect(status().isNotFound());
    }

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

    @Test
    void submitReviewFeedback_returnsOk() throws Exception {
        mockMvc.perform(post("/api/execution/exec-1/feedback")
                .param("nodeId", "node-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"feedback": "Looks good, continue", "history": [{"role": "user", "content": "test"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Feedback received and review node resumed"));
    }
}
