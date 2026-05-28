package com.agent.orchestrator.service;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.DraftResult;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DraftNodeStrategyTest {

    @Mock ExecutionUtilityService utilityService;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock Neo4jSchemaRepository schemaRepository;

    DraftNodeStrategy strategy;
    ObjectMapper mapper = new ObjectMapper();

    private Node node;
    private WorkflowSchema schema;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        strategy = new DraftNodeStrategy(utilityService, llmService, webSocketHandler,                 schemaRepository,
                null); // ReasoningCapture

        node = new Node();
        node.setId("d1");
        node.setName("DraftSpec");
        node.setType("draft");
        Node.NodeData data = new Node.NodeData();
        data.setModel("test-model");
        data.setConfig(new ConcurrentHashMap<>(Map.of("draftType", "spec")));
        node.setData(data);

        schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setName("Test Schema");
        schema.setTargetPath(tempDir.toString());
    }

    // ── Basic execution ──

    @Test
    void executeDraftNode_specType_writesArtifactAndReturnsDraftResult() throws Exception {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("# Spec Document\n\nThis is a test specification."));

        String result = strategy.executeDraftNode(node, "schema-1", "resolved-model");

        // Should return valid DraftResult JSON
        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("spec", dr.getDraftType());
        assertTrue(dr.getFilePath().contains(".axolotl/spec.md"));
        assertTrue(dr.getSummary().contains("spec draft written"));

        // Artifact file should exist on disk
        Path artifactPath = Path.of(dr.getFilePath());
        assertTrue(Files.exists(artifactPath));
        String content = Files.readString(artifactPath);
        assertTrue(content.contains("# Spec Document"));

        verify(llmService).chat(eq("resolved-model"), anyString(), anyString(), isNull());
        verify(webSocketHandler, atLeastOnce()).sendProgress(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void executeDraftNode_defaultsToSpecTypeWhenConfigMissing() throws Exception {
        Node emptyNode = new Node();
        emptyNode.setId("d2");
        emptyNode.setName("EmptyDraft");
        emptyNode.setType("draft");
        Node.NodeData data = new Node.NodeData();
        data.setModel("test-model");
        data.setConfig(new ConcurrentHashMap<>());
        emptyNode.setData(data);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d2")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("# Default spec"));

        String result = strategy.executeDraftNode(emptyNode, "schema-1", "model");

        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("spec", dr.getDraftType());
        assertTrue(dr.getFilePath().contains("spec.md"));
    }

    @Test
    void executeDraftNode_planType_writesPlanFile() throws Exception {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("draftType", "plan");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("# Implementation Plan\n\nPhase 1: ..."));

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("plan", dr.getDraftType());
        assertTrue(dr.getFilePath().contains("plan.md"));
    }

    @Test
    void executeDraftNode_uiType_writesOpenuiYaml() throws Exception {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("draftType", "ui");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("- name: LoginForm\n  template: |\n    <form>...</form>"));

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("ui", dr.getDraftType());
        assertTrue(dr.getFilePath().contains("openui.yaml"));
    }

    @Test
    void executeDraftNode_backendType_writesModulesMd() throws Exception {
        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("draftType", "backend");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("# Backend Modules\n\n## API Layer\n..."));

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("backend", dr.getDraftType());
        assertTrue(dr.getFilePath().contains("modules.md"));
    }

    // ── Artifact file resolution from predecessor ──

    @Test
    void executeDraftNode_readsPredecessorArtifactFile() throws Exception {
        // Write a predecessor artifact file
        Path draftsDir = tempDir.resolve(".axolotl");
        Files.createDirectories(draftsDir);
        Path specFile = draftsDir.resolve("spec.md");
        Files.writeString(specFile, "# Spec Content\n\nThis is the predecessor spec.");

        // Mock predecessor returning DraftResult JSON pointing to that file
        String predJson = mapper.writeValueAsString(
                new DraftResult("spec", specFile.toString(), "spec draft written to " + specFile + " (42 chars)"));

        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("draftType", "plan");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of("spec", predJson));
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenAnswer(invocation -> {
                    String input = invocation.getArgument(2);
                    // Verify the LLM gets the file content, not the DraftResult JSON
                    assertTrue(input.contains("# Spec Content"),
                            "LLM input should contain artifact file content, got: " + input);
                    assertFalse(input.contains("draftType"),
                            "LLM input should NOT contain DraftResult JSON");
                    return textOnly("# Implementation Plan\n\nBased on spec...");
                });

        Map<String, Object> planConfig = new ConcurrentHashMap<>();
        planConfig.put("draftType", "plan");
        node.getData().setConfig(planConfig);

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        DraftResult dr = mapper.readValue(result, DraftResult.class);
        assertEquals("plan", dr.getDraftType());
    }

    @Test
    void executeDraftNode_fallsBackToRawStringWhenFileMissing() {
        // Predecessor DraftResult points to a non-existent file
        String predJson = "{\"draftType\":\"spec\",\"filePath\":\"/nonexistent/spec.md\",\"summary\":\"spec draft\"}";

        Map<String, Object> config = new ConcurrentHashMap<>();
        config.put("draftType", "plan");
        node.getData().setConfig(config);

        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of("spec", predJson));
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenAnswer(invocation -> {
                    String input = invocation.getArgument(2);
                    // Should contain the raw DraftResult JSON since file doesn't exist
                    assertTrue(input.contains("draftType") || input.contains("filePath"),
                            "LLM input should contain raw string when file missing, got: " + input);
                    return textOnly("# Plan from fallback");
                });

        String result = strategy.executeDraftNode(node, "schema-1", "model");
        assertNotNull(result);
    }

    // ── LLM error handling ──

    @Test
    void executeDraftNode_returnsErrorOnLlmFailure() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("Error: LLM quota exceeded"));

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        assertTrue(result.startsWith("Draft failed:"));
        assertTrue(result.contains("LLM quota exceeded"));
    }

    @Test
    void executeDraftNode_returnsErrorOnEmptyLlmResponse() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(textOnly(""));

        String result = strategy.executeDraftNode(node, "schema-1", "model");

        assertTrue(result.startsWith("Draft failed:"));
    }

    // ── Model resolution ──

    @Test
    void executeDraftNode_resolvesModelWhenNull() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(utilityService.resolveModel(eq("test-model"), isNull(), isNull(), isNull())).thenReturn("resolved-default");
        when(llmService.chat(eq("resolved-default"), anyString(), anyString(), isNull()))
                .thenReturn(textOnly("# Spec with resolved model"));

        String result = strategy.executeDraftNode(node, "schema-1", null);

        assertNotNull(result);
        verify(utilityService).resolveModel(eq("test-model"), isNull(), isNull(), isNull());
    }

    // ── Empty input from predecessors ──

    @Test
    void executeDraftNode_usesSourceDataWhenNoPredecessors() {
        when(schemaRepository.findById("schema-1")).thenReturn(schema);
        when(utilityService.collectPredecessorResults(schema, "d1")).thenReturn(Map.of());
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenAnswer(invocation -> {
                    String input = invocation.getArgument(2);
                    assertEquals("No input provided", input,
                            "Should use fallback when no predecessors");
                    return textOnly("# Spec from empty input");
                });

        strategy.executeDraftNode(node, "schema-1", "model");
        verify(llmService).chat(anyString(), anyString(), eq("No input provided"), isNull());
    }
}
