package com.agent.orchestrator.service;

import com.agent.orchestrator.context.ContextAssembler;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import com.agent.orchestrator.llm.LlmUsage;
import static org.mockito.Mockito.*;
import static com.agent.orchestrator.llm.LlmResponse.textOnly;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentNodeStrategyTest {

    @Mock ExecutionUtilityService utilityService;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ProjectContextBuilder projectContextBuilder;
    @Mock ExecutionStateManager stateManager;
    @Mock PlanStepService planStepService;
    @Mock com.agent.orchestrator.context.ContextAssembler contextAssembler;

    AgentNodeStrategy strategy;

    private Node node;
    private Node toolNode;
    private WorkflowSchema schema;
    private Map<String, Map<String, String>> nodeResults;

    @BeforeEach
    void setUp() {
        when(contextAssembler.assemble(anyList(), anyInt()))
                .thenReturn(new com.agent.orchestrator.context.ContextAssembler.AssemblyResult(
                        "", 0, 0, 0, List.of()));

        strategy = new AgentNodeStrategy(utilityService, llmService, webSocketHandler,
                memPalaceClient, toolExecutor, schemaRepository,
                projectContextBuilder,
                stateManager,
                null,  // ReasoningCapture
                planStepService,
                contextAssembler);

        node = new Node();
        node.setId("n1");
        node.setName("TestAgent");
        node.setType("agent");
        Node.NodeData data = new Node.NodeData();
        data.setUserPrompt("Analyze the data");
        data.setSystemPrompt("You are a helpful assistant");
        data.setModel("test-model");
        data.setEnabledTools(null);
        node.setData(data);

        toolNode = new Node();
        toolNode.setId("n2");
        toolNode.setName("ToolAgent");
        toolNode.setType("agent");
        Node.NodeData toolData = new Node.NodeData();
        toolData.setUserPrompt("Use tools");
        toolData.setSystemPrompt("You have tools");
        toolData.setModel("test-model");
        toolData.setEnabledTools(List.of("file_read", "bash"));
        toolData.setMaxToolCalls(10);
        toolData.setAgentType("coder");
        toolNode.setData(toolData);

        schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setName("Test Schema");
        schema.setNodes(List.of(node, toolNode));
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("n1");
        schema.setEdges(List.of(edge));

        nodeResults = new ConcurrentHashMap<>();
    }

    @Test
    void executeAgentNode_callsUtilityAndLlmServices() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "n1")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class), any()))
                .thenReturn(textOnly("Test response"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeAgentNode(node, "schema-1", "resolved-model");

        assertNotNull(result);
        assertEquals("Test response", result);

        verify(utilityService).collectPredecessorResults(schema, "n1");
        verify(utilityService).buildContextBlock(anyMap());
        verify(utilityService, times(2)).interpolateVariables(anyString(), eq(schema), anyMap());
        verify(utilityService).extractGeneratedFiles("Test response");
        verify(llmService).streamingChat(eq("resolved-model"), anyString(), anyString(), isNull(), any(Consumer.class), any());
        verify(webSocketHandler, atLeastOnce()).sendProgress(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void executeAgentNode_extractsGeneratedFiles_whenPresent() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "n1")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(llmService.streamingChat(anyString(), anyString(), anyString(), isNull(), any(Consumer.class), any()))
                .thenReturn(textOnly("Response with files"));
        Map<String, Object> extractedFiles = Map.of("file1.txt", 150);
        when(utilityService.extractGeneratedFiles("Response with files")).thenReturn(extractedFiles);
        Map<String, Object> generatedFilesRegistry = new ConcurrentHashMap<>();
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(generatedFilesRegistry);
        when(memPalaceClient.isEnabled()).thenReturn(false);

        strategy.executeAgentNode(node, "schema-1", "test-model");

        assertTrue(generatedFilesRegistry.containsKey("schema-1:n1"));
        assertEquals(extractedFiles, generatedFilesRegistry.get("schema-1:n1"));
    }

    @Test
    void executeToolAgentNode_usesToolAgentFlow() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolInstructions(anyList())).thenReturn("tool instructions");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.parseToolCalls(anyString())).thenReturn(List.of());
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any())).thenReturn(textOnly("Tool response"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeToolAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
        verify(utilityService).buildToolInstructions(anyList());
        verify(utilityService).buildMessagesForToolCall(anyList());
        verify(llmService).chat(anyString(), isNull(), anyString(), isNull(), any());
    }

    @Test
    void executeToolAgentNode_withToolCalls_processesIterations() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolDefinitions(anyList())).thenReturn("tool definitions");
        when(utilityService.buildToolInstructions(anyList())).thenReturn("tool instructions");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);

        // First call returns a tool call, second call returns final text (no tools)
        Map<String, Object> toolCall = Map.of(
                "id", "call_1",
                "name", "bash",
                "arguments", Map.of("command", "ls")
        );
        when(utilityService.parseToolCalls(anyString()))
                .thenReturn(List.of(toolCall))
                .thenReturn(List.of());
        when(utilityService.executeToolCall(anyString(), anyMap(), eq(toolNode), anyString(), nullable(String.class), nullable(String.class)))
                .thenReturn("file1.txt\nfile2.txt");
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any()))
                .thenReturn(textOnly("{\"tool_calls\": [{\"name\": \"bash\", \"arguments\": {\"command\": \"ls\"}}]}"))
                .thenReturn(textOnly("Final result after tools"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeToolAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
        assertEquals("Final result after tools", result);
        verify(utilityService, times(2)).parseToolCalls(anyString());
        verify(utilityService).executeToolCall(eq("bash"), anyMap(), eq(toolNode), eq("schema-1"), nullable(String.class), nullable(String.class));
    }

    @Test
    void simulateAgentNode_returnsDryRunResponse() {
        String result = strategy.simulateAgentNode(node, "schema-1");

        assertNotNull(result);
        assertTrue(result.contains("[DRY_RUN]"));
        assertTrue(result.contains("test-model"));
        verify(webSocketHandler).sendProgress(anyString(), anyString(), anyString(), anyInt(), anyString());
        verify(webSocketHandler).sendLog(anyString(), anyString(), anyString(), anyString());
        verify(webSocketHandler).sendResult(anyString(), anyString(), anyString());
    }

    @Test
    void simulateAgentNode_withNullNodeData_returnsDryRunWithUnknownModel() {
        Node emptyNode = new Node();
        emptyNode.setId("n-empty");
        emptyNode.setData(null);

        String result = strategy.simulateAgentNode(emptyNode, "schema-1");

        assertNotNull(result);
        assertTrue(result.contains("[DRY_RUN]"));
        assertTrue(result.contains("unknown"));
        assertEquals("Анализируй данные", result.contains("Анализируй данные") ? "Анализируй данные" : "prompt check");
    }

    @Test
    void analyzeAgentNode_returnsAnalyzeResponse() {
        String result = strategy.analyzeAgentNode(node, "schema-1");

        assertNotNull(result);
        assertTrue(result.contains("[ANALYZE]"));
        assertTrue(result.contains("test-model"));
        verify(webSocketHandler).sendProgress(anyString(), anyString(), anyString(), anyInt(), anyString());
        verify(webSocketHandler).sendLog(anyString(), anyString(), anyString(), anyString());
        verify(webSocketHandler).sendResult(anyString(), anyString(), anyString());
    }

    @Test
    void analyzeAgentNode_withNullNodeData_returnsAnalyzeWithUnknownModel() {
        Node emptyNode = new Node();
        emptyNode.setId("n-empty");
        emptyNode.setData(null);

        String result = strategy.analyzeAgentNode(emptyNode, "schema-1");

        assertNotNull(result);
        assertTrue(result.contains("[ANALYZE]"));
        assertTrue(result.contains("unknown"));
    }

    @Test
    void executeAgentNode_withTools_routesToToolAgentNode() {
        // When a node has enabledTools, executeAgentNode should delegate to executeToolAgentNode
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolInstructions(anyList())).thenReturn("");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.parseToolCalls(anyString())).thenReturn(List.of());
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any())).thenReturn(textOnly("Tool result"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
    }

    @Test
    void executeToolAgentNode_withExpectedFileCount_warnsWhenTooFew() {
        Map<String, Object> cfg = new ConcurrentHashMap<>();
        cfg.put("expectedFileCount", 3);
        toolNode.getData().setConfig(cfg);
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolDefinitions(anyList())).thenReturn("tool definitions");
        when(utilityService.buildToolInstructions(anyList())).thenReturn("tool instructions");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.parseToolCalls(anyString())).thenReturn(List.of());
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any())).thenReturn(textOnly("Tool response"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(stateManager.getFileChanges(eq("schema-1"), eq("n2"))).thenReturn(Map.of("f1.py", "created"));
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeToolAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
        assertTrue(result.contains("Expected at least 3 file(s)"));
        assertTrue(result.contains("[WARNING]"));
        verify(webSocketHandler).sendLog(eq("schema-1"), eq("warning"),
                eq("Expected 3 file(s), created 1"), eq("n2"));
    }

    @Test
    void executeToolAgentNode_withExpectedFileCount_skipsWhenEnough() {
        Map<String, Object> cfg = new ConcurrentHashMap<>();
        cfg.put("expectedFileCount", 2);
        toolNode.getData().setConfig(cfg);
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolDefinitions(anyList())).thenReturn("tool definitions");
        when(utilityService.buildToolInstructions(anyList())).thenReturn("tool instructions");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.parseToolCalls(anyString())).thenReturn(List.of());
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any())).thenReturn(textOnly("Tool response"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(stateManager.getFileChanges(eq("schema-1"), eq("n2")))
                .thenReturn(Map.of("f1.py", "created", "f2.py", "created"));
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeToolAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
        assertFalse(result.contains("[WARNING]"));
    }

    @Test
    void executeToolAgentNode_withoutExpectedFileCount_skipsCheck() {
        // expectedFileCount not set in config
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(utilityService.collectPredecessorResults(schema, "n2")).thenReturn(Map.of());
        when(utilityService.buildContextBlock(Map.of())).thenReturn("");
        when(utilityService.interpolateVariables(anyString(), eq(schema), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(utilityService.buildToolDefinitions(anyList())).thenReturn("tool definitions");
        when(utilityService.buildToolInstructions(anyList())).thenReturn("tool instructions");
        when(utilityService.buildMessagesForToolCall(anyList())).thenReturn("<message>...</message>");
        when(utilityService.parseToolCalls(anyString())).thenReturn(List.of());
        when(utilityService.extractGeneratedFiles(anyString())).thenReturn(null);
        when(llmService.chat(anyString(), isNull(), anyString(), isNull(), any())).thenReturn(textOnly("Tool response"));
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new ConcurrentHashMap<>());
        when(stateManager.getFileChanges(eq("schema-1"), eq("n2"))).thenReturn(Map.of());
        when(memPalaceClient.isEnabled()).thenReturn(false);

        String result = strategy.executeToolAgentNode(toolNode, "schema-1", "resolved-model");

        assertNotNull(result);
        assertFalse(result.contains("[WARNING]"));
    }
}
