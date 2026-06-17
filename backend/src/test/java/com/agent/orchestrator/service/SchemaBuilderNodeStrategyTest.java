package com.agent.orchestrator.service;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Plan;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaBuilderNodeStrategyTest {

    @Mock ExecutionUtilityService utilityService;
    @Mock NodeFileWriter nodeFileWriter;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock PlanService planService;
    @Mock ReasoningCapture reasoningCapture;

    SchemaBuilderNodeStrategy strategy;

    private Node node;
    private WorkflowSchema schema;

    @BeforeEach
    void setUp() {
        strategy = new SchemaBuilderNodeStrategy(utilityService, nodeFileWriter, llmService, webSocketHandler,
                schemaRepository, planService, reasoningCapture);

        node = new Node();
        node.setId("sb1");
        node.setName("SchemaBuilder");
        node.setType("schemabuilder");
        Node.NodeData data = new Node.NodeData();
        data.setModel("test-model");
        data.setConfig(Map.of("generateMd", false));
        node.setData(data);

        schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setName("Parent Schema");
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("sb1");
        schema.setEdges(List.of(edge));
        schema.setTargetPath("/test/path");
        schema.setWorkspaceId("workspace-1");
    }

    @Test
    void executeSchemaBuilderNode_callsLlmAndCreatesSchema() {
        String llmJson = "{\n" +
                "  \"name\": \"Generated Schema\",\n" +
                "  \"description\": \"A test workflow\",\n" +
                "  \"nodes\": [\n" +
                "    {\"id\": \"n1\", \"type\": \"agent\", \"name\": \"Analyzer\", \"position\": {\"x\": 100, \"y\": 200}, \"data\": {\"userPrompt\": \"Analyze\", \"model\": \"minimax-max\"}}\n" +
                "  ],\n" +
                "  \"edges\": [\n" +
                "    {\"source\": \"n1\", \"target\": \"n2\"}\n" +
                "  ]\n" +
                "}";

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb1")).thenReturn(Map.of("input", "Build a code analysis tool"));
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("test-model");
        when(llmService.chat(eq("test-model"), anyString(), anyString(), isNull())).thenReturn(textOnly(llmJson));
        doNothing().when(schemaRepository).save(any(WorkflowSchema.class));
        Plan mockPlan = new Plan();
        mockPlan.setId("plan-1");
        when(planService.getPlan("default")).thenReturn(mockPlan);
        Plan mockSubPlan = new Plan();
        mockSubPlan.setId("subplan-1");
        mockSubPlan.setName("Generated Schema");
        when(planService.importSchemaAsSubPlan(eq("default"), eq("plan-1"), anyString())).thenReturn(mockSubPlan);

        String result = strategy.executeSchemaBuilderNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("Schema created"));
        assertTrue(result.contains("Generated Schema"));
        verify(llmService).chat(eq("test-model"), anyString(), anyString(), isNull());
        verify(schemaRepository).save(any(WorkflowSchema.class));
        verify(planService).getPlan("default");
        verify(planService).importSchemaAsSubPlan(eq("default"), eq("plan-1"), anyString());
    }

    @Test
    void executeSchemaBuilderNode_withNullModel_resolvesModel() {
        Node nodeNoModel = new Node();
        nodeNoModel.setId("sb2");
        nodeNoModel.setName("NoModelSB");
        Node.NodeData nd = new Node.NodeData();
        nd.setModel(null);
        nd.setConfig(Map.of("generateMd", false));
        nodeNoModel.setData(nd);

        String llmJson = "{\"name\": \"Test\", \"nodes\": [{\"id\": \"n1\", \"type\": \"agent\", \"name\": \"Node1\", \"data\": {}}], \"edges\": []}";

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb2")).thenReturn(Map.of("input", "test"));
        when(utilityService.resolveModel(isNull(), isNull(), isNull(), isNull())).thenReturn("resolved-model");
        when(llmService.chat(eq("resolved-model"), anyString(), anyString(), isNull())).thenReturn(textOnly(llmJson));
        doNothing().when(schemaRepository).save(any(WorkflowSchema.class));
        Plan mockPlan = new Plan();
        mockPlan.setId("plan-1");
        when(planService.getPlan("default")).thenReturn(mockPlan);
        when(planService.importSchemaAsSubPlan(eq("default"), eq("plan-1"), anyString())).thenReturn(new Plan());

        String result = strategy.executeSchemaBuilderNode(nodeNoModel, "schema-1", null);

        assertNotNull(result);
        verify(utilityService).resolveModel(isNull(), isNull(), isNull(), isNull());
        verify(llmService).chat(eq("resolved-model"), anyString(), anyString(), isNull());
    }

    @Test
    void executeSchemaBuilderNode_returnsErrorOnEmptyInput() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb1")).thenReturn(Map.of());

        String result = strategy.executeSchemaBuilderNode(node, "schema-1", "test-model");

        assertTrue(result.startsWith("Error"));
        verify(llmService, never()).chat(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void executeSchemaBuilderNode_returnsErrorOnFailedLlmCall() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb1")).thenReturn(Map.of("input", "test data"));
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("test-model");
        when(llmService.chat(eq("test-model"), anyString(), anyString(), isNull())).thenReturn(textOnly("Error: LLM unavailable"));

        String result = strategy.executeSchemaBuilderNode(node, "schema-1", "test-model");

        assertTrue(result.startsWith("Error"));
    }

    @Test
    void executeSchemaBuilderNode_handlesLlmResponseWithMarkdown() {
        String llmWithMarkdown = "```json\n{\"name\": \"Test\", \"nodes\": [{\"id\": \"n1\", \"type\": \"source\", \"name\": \"Source\", \"data\": {}}], \"edges\": []}\n```";

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb1")).thenReturn(Map.of("input", "test data"));
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("test-model");
        when(llmService.chat(eq("test-model"), anyString(), anyString(), isNull())).thenReturn(textOnly(llmWithMarkdown));
        doNothing().when(schemaRepository).save(any(WorkflowSchema.class));
        Plan mockPlan = new Plan();
        mockPlan.setId("plan-1");
        when(planService.getPlan("default")).thenReturn(mockPlan);
        when(planService.importSchemaAsSubPlan(eq("default"), eq("plan-1"), anyString())).thenReturn(new Plan());

        String result = strategy.executeSchemaBuilderNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("Schema created"));
        verify(schemaRepository).save(any(WorkflowSchema.class));
    }

    @Test
    void executeSchemaBuilderNode_createsPlanTasksFromNodes() {
        String llmJson = "{\"name\": \"Full Schema\", \"nodes\": [{\"id\": \"n1\", \"type\": \"agent\", \"name\": \"Analyze\", \"data\": {}}, {\"id\": \"n2\", \"type\": \"agent\", \"name\": \"Implement\", \"data\": {}}], \"edges\": [{\"source\": \"n1\", \"target\": \"n2\"}]}";

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "sb1")).thenReturn(Map.of("input", "test data"));
        when(utilityService.resolveModel(anyString(), isNull(), isNull(), isNull())).thenReturn("test-model");
        when(llmService.chat(eq("test-model"), anyString(), anyString(), isNull())).thenReturn(textOnly(llmJson));
        doNothing().when(schemaRepository).save(any(WorkflowSchema.class));
        Plan mockPlan = new Plan();
        mockPlan.setId("plan-1");
        when(planService.getPlan("default")).thenReturn(mockPlan);
        when(planService.importSchemaAsSubPlan(eq("default"), eq("plan-1"), anyString())).thenReturn(new Plan());

        String result = strategy.executeSchemaBuilderNode(node, "schema-1", "test-model");

        assertNotNull(result);
        assertTrue(result.contains("2 nodes"));
        verify(planService, atLeast(2)).addTask(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull());
    }
}
