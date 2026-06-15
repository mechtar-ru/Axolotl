package com.agent.orchestrator.controller;

import com.agent.orchestrator.config.GlobalExceptionHandler;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.service.PlanService;
import com.agent.orchestrator.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private Neo4jSchemaRepository neo4jSchemaRepository;

    @Mock
    private PlanService planService;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private SettingsService settingsService;

    @Mock
    private LlmService llmService;

    @InjectMocks
    private SessionController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testSessionPlan() throws Exception {
        // Mock schema
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-id");
        schema.setName("test");
        schema.setDescription("Test project");
        schema.setDefaultModel("gpt-4o");
        when(neo4jSchemaRepository.findById("test-id")).thenReturn(schema);

        // Mock LLM response
        String expectedReply = "Suggested features:\n1. Add authentication";
        when(llmService.chat(eq("gpt-4o"), eq(""), any(String.class), anyMap()))
                .thenReturn(LlmResponse.textOnly(expectedReply));

        // Mock plan and execution to return empty (triggers catch blocks — no plan/runs exist)
        when(planService.getPlanBySchemaId("test-id")).thenReturn(null);
        when(executionRepository.getRunsBySchema("test-id")).thenReturn(List.of());

        // Execute
        mockMvc.perform(post("/api/schemas/test-id/session/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"What should I build next?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(expectedReply));
    }

    @Test
    void testSessionPlan_withHistory() throws Exception {
        // Mock schema
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-id");
        schema.setName("test");
        schema.setDefaultModel("gpt-4o");
        when(neo4jSchemaRepository.findById("test-id")).thenReturn(schema);

        // Mock LLM response
        String expectedReply = "Let's build a login feature.";
        when(llmService.chat(eq("gpt-4o"), eq(""), any(String.class), anyMap()))
                .thenReturn(LlmResponse.textOnly(expectedReply));

        // Mock plan and execution to return empty
        when(planService.getPlanBySchemaId("test-id")).thenReturn(null);
        when(executionRepository.getRunsBySchema("test-id")).thenReturn(List.of());

        // Execute with history
        mockMvc.perform(post("/api/schemas/test-id/session/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "What next?",
                                    "history": [
                                        {"role": "user", "content": "Hello"},
                                        {"role": "assistant", "content": "How can I help?"}
                                    ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(expectedReply));
    }

    @Test
    void testSessionPlan_schemaNotFound() throws Exception {
        when(neo4jSchemaRepository.findById("nonexistent")).thenReturn(null);

        mockMvc.perform(post("/api/schemas/nonexistent/session/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"hello\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"));
    }

    @Test
    void testSessionPlan_fallbackWhenLlmReturnsNull() throws Exception {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-id");
        schema.setName("test");
        schema.setDefaultModel("gpt-4o");
        when(neo4jSchemaRepository.findById("test-id")).thenReturn(schema);

        // LLM returns null
        when(llmService.chat(eq("gpt-4o"), eq(""), any(String.class), anyMap()))
                .thenReturn(null);

        when(planService.getPlanBySchemaId("test-id")).thenReturn(null);
        when(executionRepository.getRunsBySchema("test-id")).thenReturn(List.of());

        mockMvc.perform(post("/api/schemas/test-id/session/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("I can see the project state. What would you like to build next?"));
    }
}
