package com.agent.orchestrator.service;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewNodeStrategyTest {

    @Mock ExecutionUtilityService utilityService;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionStateManager stateManager;
    @Mock PlanService planService;
    @Mock ExecutionRepository executionRepository;

    ReviewNodeStrategy strategy;

    private Node node;
    private WorkflowSchema schema;
    private Map<String, Map<String, String>> nodeResults;

    @BeforeEach
    void setUp() {
        strategy = new ReviewNodeStrategy(utilityService, llmService, webSocketHandler,
                schemaRepository, stateManager, planService,                 executionRepository,
                null); // ReasoningCapture

        node = new Node();
        node.setId("r1");
        node.setName("Reviewer");
        node.setType("review");
        Node.NodeData data = new Node.NodeData();
        data.setModel("test-model");
        Map<String, Object> config = new ConcurrentHashMap<>();
        Map<String, Object> checks = new ConcurrentHashMap<>();
        checks.put("premortem", true);
        checks.put("prism", false);
        checks.put("postmortem", false);
        config.put("checks", checks);
        config.put("generatePlan", true);
        config.put("mode", "auto");
        config.put("maxAutoIterations", 2);
        data.setConfig(config);
        node.setData(data);

        schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setName("Test Schema");
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("r1");
        schema.setEdges(java.util.List.of(edge));

        nodeResults = new ConcurrentHashMap<>();
    }

    @Test
    void executeReviewNode_collectsPredecessorAndCallsLlm() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r1")).thenReturn(Map.of("input", "Build a REST API"));
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    String userPrompt = invocation.getArgument(2);
                    return textOnly("{\"status\": \"PASS\", \"findings\": [], \"summary\": \"Plan looks good\"}");
                });
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        String result = strategy.executeReviewNode(node, "schema-1", "test-model");

        assertNotNull(result);
        verify(utilityService).collectPredecessorResults(schema, "r1");
        verify(llmService, atLeastOnce()).streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class));
    }

    @Test
    void executeReviewNode_withNullModel_resolvesModel() {
        Node nodeNoModel = new Node();
        nodeNoModel.setId("r2");
        nodeNoModel.setName("NoModel");
        nodeNoModel.setType("review");
        Node.NodeData nd = new Node.NodeData();
        nd.setModel(null);
        nd.setConfig(Map.of("checks", Map.of(), "generatePlan", false, "mode", "manual"));
        nodeNoModel.setData(nd);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r2")).thenReturn(Map.of("input", "Test plan"));
        when(utilityService.resolveModel(isNull(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(textOnly("{\"status\":\"PASS\",\"findings\":[],\"summary\":\"OK\"}"));

        String result = strategy.executeReviewNode(nodeNoModel, "schema-1", null);

        assertNotNull(result);
        verify(utilityService).resolveModel(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void executeReviewNode_withoutGeneratePlan_usesInputAsPlan() {
        Node nodeWithoutPlanGen = new Node();
        nodeWithoutPlanGen.setId("r3");
        nodeWithoutPlanGen.setName("NoPlanGen");
        nodeWithoutPlanGen.setType("review");
        Node.NodeData nd = new Node.NodeData();
        nd.setModel("test-model");
        nd.setConfig(Map.of("checks", Map.of(), "generatePlan", false, "mode", "manual"));
        nodeWithoutPlanGen.setData(nd);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r3")).thenReturn(Map.of("input", "Direct plan text"));
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenReturn(textOnly("{\"status\": \"PASS\", \"findings\": [{\"severity\": \"info\", \"message\": \"Looks good\"}], \"summary\": \"OK\"}"));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        String result = strategy.executeReviewNode(nodeWithoutPlanGen, "schema-1", "test-model");

        assertNotNull(result);
        // Should only call streamingChat once for analysis, not for plan generation
        verify(llmService, times(1)).streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class));
    }

    @Test
    void executeReviewNode_withAutoMode_retriesOnRewrite() {
        Map<String, Object> checks = new ConcurrentHashMap<>();
        checks.put("premortem", true);
        checks.put("prism", false);
        checks.put("postmortem", false);
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("checks", checks);
        config.put("generatePlan", true);
        config.put("mode", "auto");
        config.put("maxAutoIterations", 3);
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r1")).thenReturn(Map.of("input", "Build a complex system"));
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        // First call: plan generation (returns plain text plan)
        // Second call: analysis returns REWRITE
        // Third call: re-review returns PASS
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenReturn(textOnly("Initial plan: build a complex system with tests"))
                .thenReturn(textOnly("{\"status\": \"REWRITE\", \"findings\": [{\"severity\": \"warning\", \"message\": \"Missing test plan\"}], \"summary\": \"Needs tests\", \"rewrittenPlan\": \"Improved plan that includes tests\"}"))
                .thenReturn(textOnly("{\"status\": \"PASS\", \"findings\": [], \"summary\": \"Now includes tests\"}"));

        String result = strategy.executeReviewNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
        // Called for plan gen + 2 analysis calls (1st analysis + 1 re-review)
        verify(llmService, times(3)).streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class));
    }

    @Test
    void executeReviewNode_withManualMode_returnsAwaitingApprovalOnRewrite() {
        Map<String, Object> config = new ConcurrentHashMap<>();
        Map<String, Object> checks = new ConcurrentHashMap<>();
        checks.put("premortem", true);
        config.put("checks", checks);
        config.put("generatePlan", true);
        config.put("mode", "manual");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r1")).thenReturn(Map.of("input", "Build something"));
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenReturn(textOnly("{\"status\": \"REWRITE\", \"findings\": [{\"severity\": \"critical\", \"message\": \"Security issue\"}], \"summary\": \"Fix security\", \"rewrittenPlan\": \"Secure version\"}"));

        String result = strategy.executeReviewNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("AWAITING_APPROVAL"));
        verify(webSocketHandler).sendLiveUpdate(eq("schema-1"), eq("review_awaiting_approval"), anyMap());
    }

    @Test
    void executeReviewNode_withManualModeAndPass_returnsPass() {
        Map<String, Object> checks = new ConcurrentHashMap<>();
        checks.put("premortem", false);
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("checks", checks);
        config.put("generatePlan", true);
        config.put("mode", "manual");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r1")).thenReturn(Map.of("input", "Build something"));
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenReturn(textOnly("{\"status\": \"PASS\", \"findings\": [], \"summary\": \"All good\"}"));

        String result = strategy.executeReviewNode(node, "schema-1", "test-model");

        assertNotNull(result);
        // Manual mode always shows approval dialog even when LLM returns PASS
        assertTrue(result.contains("AWAITING_APPROVAL"));
        verify(webSocketHandler).sendLiveUpdate(anyString(), eq("review_awaiting_approval"), anyMap());
    }

    @Test
    void executeReviewNode_handlesEmptyInput() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "r1")).thenReturn(Map.of());
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class)))
                .thenReturn(textOnly("{\"status\": \"PASS\", \"findings\": [], \"summary\": \"Empty input\"}"));

        String result = strategy.executeReviewNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
    }
}
