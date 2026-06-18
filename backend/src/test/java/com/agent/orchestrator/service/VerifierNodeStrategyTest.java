package com.agent.orchestrator.service;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerifierNodeStrategyTest {

    @Mock ExecutionUtilityService utilityService;
    @Mock AgentNodeStrategy agentStrategy;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionStateManager stateManager;

    VerifierNodeStrategy strategy;

    private Node node;
    private WorkflowSchema schema;
    private Map<String, Map<String, String>> nodeResults;

    @BeforeEach
    void setUp() {
        strategy = new VerifierNodeStrategy(utilityService, agentStrategy, llmService,
                webSocketHandler, schemaRepository, stateManager,
                null, // ReasoningCapture
                new ObjectMapper());

        node = new Node();
        node.setId("v1");
        node.setName("Verifier");
        node.setType("verifier");
        Node.NodeData data = new Node.NodeData();
        data.setModel("test-model");
        Map<String, Object> config = new ConcurrentHashMap<>();
        Map<String, Object> checks = new ConcurrentHashMap<>();
        checks.put("syntaxCheck", true);
        checks.put("requiredPatterns", java.util.List.of("main", "class"));
        checks.put("testCommand", "python3 -m pytest");
        checks.put("maxFileSizeKb", 100);
        config.put("checks", checks);
        config.put("rewriteOnFail", false);
        data.setConfig(config);
        node.setData(data);

        schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setName("Test Schema");
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("v1");
        schema.setEdges(java.util.List.of(edge));

        nodeResults = new ConcurrentHashMap<>();
    }

    @Test
    void executeVerifierNode_collectsPredecessorResultsAndUsesAgentStrategy() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of("upstream_node", "def fib(n): return n"));
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [{\"name\": \"syntax\", \"passed\": true}], \"summary\": \"All good\"}");
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
        verify(utilityService).collectPredecessorResults(schema, "v1");
        verify(agentStrategy).executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull());
    }

    @Test
    void executeVerifierNode_withNullModel_resolvesModel() {
        Node nodeNoModel = new Node();
        nodeNoModel.setId("v2");
        nodeNoModel.setName("NoModel");
        nodeNoModel.setType("verifier");
        Node.NodeData nd = new Node.NodeData();
        nd.setModel(null);
        nd.setConfig(Map.of("checks", Map.of()));
        nodeNoModel.setData(nd);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v2")).thenReturn(Map.of("up", "code"));
        when(utilityService.resolveModel(isNull(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(agentStrategy.executeToolAgentNode(eq(nodeNoModel), eq("schema-1"), eq("resolved-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [], \"summary\": \"OK\"}");
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(nodeNoModel, "schema-1", null);

        assertNotNull(result);
        verify(utilityService).resolveModel(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void executeVerifierNode_withPremortem_callsLlmService() {
        Node premortemNode = new Node();
        premortemNode.setId("v3");
        premortemNode.setName("Premortem");
        premortemNode.setType("verifier");
        Node.NodeData pd = new Node.NodeData();
        pd.setModel("test-model");
        Map<String, Object> pConfig = new ConcurrentHashMap<>();
        Map<String, Object> pChecks = new ConcurrentHashMap<>();
        pChecks.put("syntaxCheck", true);
        pChecks.put("premortem", true);
        pConfig.put("checks", pChecks);
        pConfig.put("rewriteOnFail", false);
        pd.setConfig(pConfig);
        premortemNode.setData(pd);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v3")).thenReturn(Map.of("up", "def foo(): pass"));
        when(llmService.chat(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(textOnly("- Potential null pointer\n- Missing error handling"));
        when(agentStrategy.executeToolAgentNode(eq(premortemNode), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [{\"name\": \"syntax\", \"passed\": true}], \"summary\": \"OK\"}");
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(premortemNode, "schema-1", "test-model");

        assertNotNull(result);
        verify(llmService).chat(anyString(), isNull(), anyString(), isNull());
    }

    @Test
    void executeVerifierNode_withRewriteOnFail_retriesOnFail() {
        node.getData().getConfig().put("rewriteOnFail", true);
        node.getData().getConfig().put("maxRewriteRetries", 2);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of("up", "def fib(n): return n"));
        when(llmService.chat(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(textOnly("def fib(n): return n if n < 2 else fib(n-1) + fib(n-2)"));

        // First call FAIL, second call PASS
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"FAIL\", \"checks\": [{\"name\": \"syntax\", \"passed\": false, \"error\": \"indent error\"}]}")
                .thenReturn("{\"status\": \"PASS\", \"checks\": [{\"name\": \"syntax\", \"passed\": true}], \"summary\": \"Fixed\"}");

        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
        verify(agentStrategy, times(2)).executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull());
    }

    @Test
    void executeVerifierNode_returnsFailResult_whenAllChecksFail() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of("up", "bad code"));
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"FAIL\", \"checks\": [{\"name\": \"syntax\", \"passed\": false, \"error\": \"syntax error\"}], \"summary\": \"Failed\"}");
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        // Default rewriteOnFail is false, so it should return FAIL status
        assertTrue(result.contains("FAIL"));
    }

    @Test
    void executeVerifierNode_handlesEmptyPredecessorResults() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of());
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [], \"summary\": \"No content to verify\"}");
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        verify(agentStrategy).executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull());
    }

    @Test
    void executeVerifierNode_detectsZeroFileWrites() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of("source1", "def foo(): pass"));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(stateManager.getFileChanges(eq("schema-1"), eq("source1"))).thenReturn(null);
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [], \"summary\": \"OK\"}");

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
        verify(stateManager).getFileChanges(eq("schema-1"), eq("source1"));
    }

    @Test
    void executeVerifierNode_withFileWrites_skipsZeroFileCheck() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "v1")).thenReturn(Map.of("source1", "def foo(): return 42"));
        when(stateManager.getNodeResults()).thenReturn(nodeResults);
        when(stateManager.getOutputFileRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(stateManager.getFileChanges(eq("schema-1"), eq("source1"))).thenReturn(Map.of("file1.py", "created"));
        when(agentStrategy.executeToolAgentNode(eq(node), eq("schema-1"), eq("test-model"), isNull()))
                .thenReturn("{\"status\": \"PASS\", \"checks\": [], \"summary\": \"All good\"}");

        String result = strategy.executeVerifierNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("PASS") || result.contains("\"status\""));
        verify(stateManager).getFileChanges(eq("schema-1"), eq("source1"));
    }
}
